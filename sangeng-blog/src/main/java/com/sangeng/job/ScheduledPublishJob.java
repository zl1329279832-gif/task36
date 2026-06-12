package com.sangeng.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.entity.Article;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.ArticleAuditLogService;
import com.sangeng.service.ArticleService;
import com.sangeng.service.CacheOperationTracker;
import com.sangeng.utils.OssValidationUtil;
import com.sangeng.utils.RedisCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 定时发布任务：每分钟检查是否有到期的定时发布文章，将其发布。
 * <p>
 * 重要：违规下线优先级高于定时发布。在发布前必须重新读取DB状态，
 * 若文章已被管理员违规下线，则跳过发布并记录审计日志。
 */
@Component
public class ScheduledPublishJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublishJob.class);

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private CacheOperationTracker cacheOperationTracker;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void publishScheduledArticles() {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Article::getStatus, ArticleStatusEnum.SCHEDULED.getCode())
               .le(Article::getPublishTime, new Date());

        List<Article> articles = articleService.list(wrapper);

        if (articles.isEmpty()) {
            return;
        }

        log.info("发现 {} 篇定时发布文章需要处理", articles.size());

        for (Article article : articles) {
            publishSingleArticle(article);
        }
    }

    /**
     * 处理单篇定时发布文章。
     * 重新从DB读取最新状态，若已被违规下线则跳过并记录审计日志。
     */
    private void publishSingleArticle(Article staleArticle) {
        // 重新从DB获取最新状态，防止覆盖并发操作（如违规下线）
        Article freshArticle = articleService.getById(staleArticle.getId());
        if (freshArticle == null) {
            log.warn("文章[id={}]已被删除，跳过定时发布", staleArticle.getId());
            return;
        }

        String currentStatus = freshArticle.getStatus();

        // 违规下线优先级高于定时发布：若文章已不在SCHEDULED状态则跳过
        if (!ArticleStatusEnum.SCHEDULED.getCode().equals(currentStatus)) {
            log.warn("文章[id={}]状态已变更为{}（非SCHEDULED），跳过定时发布",
                    freshArticle.getId(), currentStatus);

            // 写入审计日志记录定时发布被打断
            auditLogService.logOperation(
                    freshArticle.getId(),
                    currentStatus,
                    currentStatus,  // 状态未变更
                    0L,
                    "System",
                    "定时发布被中断：文章状态已变更为 " +
                            ArticleStatusEnum.fromCode(currentStatus).getDesc()
            );
            return;
        }

        // OSS附件引用校验：发布前检查附件是否仍可用
        List<String> invalidUrls = checkOssReferences(freshArticle);
        if (!invalidUrls.isEmpty()) {
            log.warn("文章[id={}] OSS附件引用失效，阻止定时发布。失效URL: {}",
                    freshArticle.getId(), invalidUrls);

            // 将文章置为违规下线状态
            freshArticle.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
            freshArticle.setViolationReason("定时发布前OSS附件引用校验失败");
            articleService.updateById(freshArticle);

            auditLogService.logOperation(
                    freshArticle.getId(),
                    ArticleStatusEnum.SCHEDULED.getCode(),
                    ArticleStatusEnum.VIOLATION_OFFLINE.getCode(),
                    0L,
                    "System",
                    "定时发布OSS校验失败，自动违规下线。失效URL: " + String.join("; ", invalidUrls)
            );

            // 失效缓存
            invalidateArticleCaches(freshArticle);
            return;
        }

        // 状态正确且OSS有效，执行发布
        String oldStatus = freshArticle.getStatus();
        freshArticle.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        articleService.updateById(freshArticle);

        // 记录审核日志
        auditLogService.logOperation(
                freshArticle.getId(),
                oldStatus,
                ArticleStatusEnum.PUBLISHED.getCode(),
                0L,
                "System",
                "定时发布任务自动发布"
        );

        // 刷新缓存
        invalidateArticleCaches(freshArticle);

        log.info("文章已自动发布: id={}", freshArticle.getId());
    }

    /**
     * 检查文章OSS附件引用是否有效
     */
    private List<String> checkOssReferences(Article article) {
        List<String> invalidUrls = new ArrayList<>();

        List<String> contentUrls = OssValidationUtil.extractImageUrls(article.getContent());
        for (String url : contentUrls) {
            if (!OssValidationUtil.verifyOssReference(url)) {
                invalidUrls.add(url);
            }
        }

        if (article.getThumbnail() != null && !article.getThumbnail().isEmpty()) {
            if (!OssValidationUtil.verifyOssReference(article.getThumbnail())) {
                invalidUrls.add(article.getThumbnail());
            }
        }

        return invalidUrls;
    }

    /**
     * 清除文章相关缓存（含浏览量）
     */
    private void invalidateArticleCaches(Article article) {
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + article.getId();
        redisCache.deleteObject(detailKey);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);

        // 下线或归档时同时清除浏览量缓存
        if (ArticleStatusEnum.VIOLATION_OFFLINE.getCode().equals(article.getStatus()) ||
                ArticleStatusEnum.ARCHIVED.getCode().equals(article.getStatus())) {
            redisCache.delCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                    article.getId().toString());
        }

        cacheOperationTracker.trackInvalidate(article.getId());
    }
}
