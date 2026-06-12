package com.sangeng.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.ArticleAuditLog;
import com.sangeng.domain.entity.Comment;
import com.sangeng.domain.vo.DashboardStatsVo;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    @Autowired
    private CacheOperationTracker cacheOperationTracker;

    @Override
    public ResponseResult getDashboardStats() {
        DashboardStatsVo stats = new DashboardStatsVo();

        // 1. 各状态文章数量
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (ArticleStatusEnum statusEnum : ArticleStatusEnum.values()) {
            LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Article::getStatus, statusEnum.getCode());
            long count = articleService.count(wrapper);
            statusCounts.put(statusEnum.getCode(), count);
        }
        stats.setStatusCounts(statusCounts);

        // 2. 冻结评论数（违规/撤回/归档文章下的评论总数）
        List<String> frozenStatuses = Arrays.asList(
                ArticleStatusEnum.VIOLATION_OFFLINE.getCode(),
                ArticleStatusEnum.WITHDRAWN.getCode(),
                ArticleStatusEnum.ARCHIVED.getCode());
        LambdaQueryWrapper<Article> frozenArticleWrapper = new LambdaQueryWrapper<>();
        frozenArticleWrapper.in(Article::getStatus, frozenStatuses);
        List<Article> frozenArticles = articleService.list(frozenArticleWrapper);

        long frozenCommentCount = 0L;
        if (!frozenArticles.isEmpty()) {
            List<Long> frozenArticleIds = frozenArticles.stream()
                    .map(Article::getId)
                    .collect(Collectors.toList());
            LambdaQueryWrapper<Comment> commentWrapper = new LambdaQueryWrapper<>();
            commentWrapper.in(Comment::getArticleId, frozenArticleIds);
            frozenCommentCount = commentService.count(commentWrapper);
        }
        stats.setFrozenCommentCount(frozenCommentCount);

        // 3. 缓存操作计数
        stats.setCacheRefreshCount(cacheOperationTracker.getRefreshCount());
        stats.setCacheInvalidateCount(cacheOperationTracker.getInvalidateCount());

        // 4. 审核耗时统计
        computeReviewDurationStats(stats);

        return ResponseResult.okResult(stats);
    }

    /**
     * 从审计日志计算审核耗时：匹配 toStatus=PENDING_REVIEW (审核开始)
     * 和 toStatus IN (PUBLISHED, SCHEDULED, GRAY_VISIBLE) (审核结束)
     */
    private void computeReviewDurationStats(DashboardStatsVo stats) {
        // 获取所有审核开始记录 (toStatus = PENDING_REVIEW)
        LambdaQueryWrapper<ArticleAuditLog> startWrapper = new LambdaQueryWrapper<>();
        startWrapper.eq(ArticleAuditLog::getToStatus, ArticleStatusEnum.PENDING_REVIEW.getCode());
        List<ArticleAuditLog> startLogs = auditLogService.list(startWrapper);

        if (startLogs.isEmpty()) {
            stats.setAvgReviewDurationMs(0L);
            stats.setMinReviewDurationMs(0L);
            stats.setMaxReviewDurationMs(0L);
            stats.setTotalReviewsCompleted(0L);
            return;
        }

        // 获取所有审核结束记录 (toStatus IN PUBLISHED, SCHEDULED, GRAY_VISIBLE)
        List<String> endStatuses = Arrays.asList(
                ArticleStatusEnum.PUBLISHED.getCode(),
                ArticleStatusEnum.SCHEDULED.getCode(),
                ArticleStatusEnum.GRAY_VISIBLE.getCode());
        LambdaQueryWrapper<ArticleAuditLog> endWrapper = new LambdaQueryWrapper<>();
        endWrapper.in(ArticleAuditLog::getToStatus, endStatuses);
        List<ArticleAuditLog> endLogs = auditLogService.list(endWrapper);

        // 按 articleId 分组结束记录
        Map<Long, ArticleAuditLog> endMap = new HashMap<>();
        for (ArticleAuditLog endLog : endLogs) {
            endMap.put(endLog.getArticleId(), endLog);
        }

        // 匹配并计算耗时
        List<Long> durations = new ArrayList<>();
        for (ArticleAuditLog startLog : startLogs) {
            ArticleAuditLog endLog = endMap.get(startLog.getArticleId());
            if (endLog != null && endLog.getCreateTime() != null && startLog.getCreateTime() != null) {
                long durationMs = endLog.getCreateTime().getTime() - startLog.getCreateTime().getTime();
                if (durationMs >= 0) {
                    durations.add(durationMs);
                }
            }
        }

        if (durations.isEmpty()) {
            stats.setAvgReviewDurationMs(0L);
            stats.setMinReviewDurationMs(0L);
            stats.setMaxReviewDurationMs(0L);
            stats.setTotalReviewsCompleted(0L);
            return;
        }

        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long d : durations) {
            sum += d;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }

        stats.setAvgReviewDurationMs(sum / durations.size());
        stats.setMinReviewDurationMs(min);
        stats.setMaxReviewDurationMs(max);
        stats.setTotalReviewsCompleted((long) durations.size());
    }
}
