package com.sangeng.job;

import com.sangeng.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledPublishJob {

    @Autowired
    private ArticleService articleService;

    /**
     * 每分钟检查是否有到期的定时发布文章
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void publishScheduledArticles() {
        articleService.publishScheduledArticles();
    }
}
