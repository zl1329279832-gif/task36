package com.sangeng.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.entity.Article;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.ArticleAuditLogService;
import com.sangeng.service.ArticleService;
import com.sangeng.utils.OssValidationUtil;
import com.sangeng.utils.RedisCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 定时发布任务：每分钟检查是否有到期的定时发布文章，将其发布
 * 违规下线优先级高于定时发布：若文章已被管理员下架，跳过发布
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

    @Scheduled(cron = "0 * * * * ?")
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
            try {
                publishSingleArticle(article);
            } catch (Exception e) {
                log.error("定时发布文章失败: id={}, error={}", article.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void publishSingleArticle(Article article) {
        // 重新读取文章状态，防止违规下线与定时发布的竞态条件
        Article freshArticle = articleService.getById(article.getId());
        if (freshArticle == null) {
            log.warn("定时发布文章不存在，跳过: id={}", article.getId());
            return;
        }

        // 若文章已不是SCHEDULED状态（如已被违规下架），跳过发布
        if (!ArticleStatusEnum.SCHEDULED.getCode().equals(freshArticle.getStatus())) {
            log.info("文章状态已变更，跳过定时发布: id={}, currentStatus={}",
                    freshArticle.getId(), freshArticle.getStatus());
            auditLogService.logOperation(
                    freshArticle.getId(),
                    freshArticle.getStatus(),
                    freshArticle.getStatus(),
                    0L,
                    "System",
                    "定时发布跳过：文章状态已变更为" + freshArticle.getStatus()
            );
            return;
        }

        // OSS附件引用校验：引用失效时阻止发布并写入审计日志
        List<String> invalidUrls = OssValidationUtil.findInvalidOssUrls(
                freshArticle.getContent(), freshArticle.getThumbnail());
        if (!invalidUrls.isEmpty()) {
            log.warn("文章OSS附件引用失效，阻止定时发布: id={}, invalidUrls={}",
                    freshArticle.getId(), invalidUrls);
            auditLogService.logOperation(
                    freshArticle.getId(),
                    ArticleStatusEnum.SCHEDULED.getCode(),
                    ArticleStatusEnum.SCHEDULED.getCode(),
                    0L,
                    "System",
                    "定时发布阻止：OSS附件引用失效 " + String.join(", ", invalidUrls)
            );
            return;
        }

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
        refreshArticleCaches(freshArticle);

        log.info("文章已自动发布: id={}", freshArticle.getId());
    }

    private void refreshArticleCaches(Article article) {
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + article.getId();
        redisCache.deleteObject(detailKey);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_TAG_LIST);
    }
}
