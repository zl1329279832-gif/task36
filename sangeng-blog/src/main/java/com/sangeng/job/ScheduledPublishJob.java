package com.sangeng.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.entity.Article;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.ArticleAuditLogService;
import com.sangeng.service.ArticleService;
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
            String oldStatus = article.getStatus();
            article.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
            articleService.updateById(article);

            // 记录审核日志
            auditLogService.logOperation(
                    article.getId(),
                    oldStatus,
                    ArticleStatusEnum.PUBLISHED.getCode(),
                    0L,
                    "System",
                    "定时发布任务自动发布"
            );

            // 刷新缓存
            refreshArticleCaches(article);

            log.info("文章已自动发布: id={}", article.getId());
        }
    }

    private void refreshArticleCaches(Article article) {
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + article.getId();
        redisCache.deleteObject(detailKey);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);
    }
}
