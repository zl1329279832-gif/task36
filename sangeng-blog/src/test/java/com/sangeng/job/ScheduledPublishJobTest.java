package com.sangeng.job;

import com.sangeng.SanGengBlogApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.ArticleAuditLog;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.ArticleAuditLogService;
import com.sangeng.service.ArticleService;
import com.sangeng.utils.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时发布任务测试 - 覆盖竞态条件、OSS校验、缓存刷新
 */
@SpringBootTest(classes = SanGengBlogApplication.class)
public class ScheduledPublishJobTest {

    @Autowired
    private ScheduledPublishJob scheduledPublishJob;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    @Autowired
    private RedisCache redisCache;

    private Long testArticleId;

    @BeforeEach
    void setUp() {
        Article article = new Article();
        article.setTitle("定时发布测试文章");
        article.setContent("这是测试内容，没有图片引用");
        article.setSummary("测试摘要");
        article.setCategoryId(1L);
        article.setStatus(ArticleStatusEnum.SCHEDULED.getCode());
        article.setViewCount(0L);
        article.setIsTop("0");
        article.setIsComment("1");
        article.setCreateBy(1L);
        // 设置发布时间为过去，触发定时发布
        article.setPublishTime(new Date(System.currentTimeMillis() - 120000));
        articleService.save(article);
        testArticleId = article.getId();
    }

    @AfterEach
    void tearDown() {
        if (testArticleId != null) {
            articleService.removeById(testArticleId);
        }
    }

    /**
     * 正常定时发布：SCHEDULED状态的文章到期后自动发布
     */
    @Test
    void testNormalScheduledPublish() {
        scheduledPublishJob.publishScheduledArticles();

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());

        // 验证审核日志
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        assertFalse(logs.isEmpty());
        ArticleAuditLog latestLog = logs.get(0);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), latestLog.getToStatus());
        assertEquals("System", latestLog.getOperatorName());
    }

    /**
     * 定时发布被违规下线打断：文章已被管理员下架，定时任务应跳过发布
     */
    @Test
    void testScheduledPublishSkippedWhenViolationOffline() {
        // 模拟管理员在定时发布前将文章违规下架
        Article article = articleService.getById(testArticleId);
        article.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        article.setViolationReason("包含违规内容");
        articleService.updateById(article);

        // 执行定时发布任务
        scheduledPublishJob.publishScheduledArticles();

        // 文章应保持违规下架状态，不应被发布
        Article result = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), result.getStatus());
    }

    /**
     * 定时发布时OSS附件引用失效：阻止发布并写入审计日志
     */
    @Test
    void testScheduledPublishBlockedByInvalidOss() {
        // 设置文章内容包含不可达的OSS图片URL
        Article article = articleService.getById(testArticleId);
        article.setContent("内容包含失效图片 ![img](http://invalid-oss-host.example.com/not-exist.jpg)");
        articleService.updateById(article);

        // 执行定时发布任务
        scheduledPublishJob.publishScheduledArticles();

        // 文章应保持SCHEDULED状态，不应被发布
        Article result = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), result.getStatus());

        // 验证审计日志记录了OSS失效
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        assertFalse(logs.isEmpty());
        boolean hasOssLog = logs.stream()
                .anyMatch(log -> log.getReason() != null && log.getReason().contains("OSS附件引用失效"));
        assertTrue(hasOssLog, "应记录OSS附件引用失效的审计日志");
    }

    /**
     * 缓存刷新验证：定时发布后相关缓存被清除（包含标签缓存）
     */
    @Test
    void testCacheRefreshedAfterScheduledPublish() {
        // 预设缓存
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached_detail");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES, "cached_home");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST, "cached_category");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_TAG_LIST, "cached_tag");

        // 执行定时发布
        scheduledPublishJob.publishScheduledArticles();

        // 验证所有相关缓存已清除
        assertNull(redisCache.getCacheObject(detailKey));
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES));
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST));
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_TAG_LIST));
    }

    /**
     * 定时发布状态竞态：模拟文章在查询后被其他操作修改状态
     * publishSingleArticle应重新读取状态并跳过非SCHEDULED文章
     */
    @Test
    void testPublishSingleArticleReChecksStatus() {
        // 将文章改为已撤回状态（模拟竞态）
        Article article = articleService.getById(testArticleId);
        article.setStatus(ArticleStatusEnum.WITHDRAWN.getCode());
        articleService.updateById(article);

        // 构造一个仍显示SCHEDULED的旧快照
        Article staleSnapshot = new Article();
        staleSnapshot.setId(testArticleId);
        staleSnapshot.setStatus(ArticleStatusEnum.SCHEDULED.getCode());

        // publishSingleArticle应重新读取并跳过
        scheduledPublishJob.publishSingleArticle(staleSnapshot);

        Article result = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.WITHDRAWN.getCode(), result.getStatus());
    }
}
