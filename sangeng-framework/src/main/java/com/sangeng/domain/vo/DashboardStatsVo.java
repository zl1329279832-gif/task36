package com.sangeng.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsVo {
    /** 各状态文章数量 (key: status code 0-8, value: count) */
    private Map<String, Long> statusCounts;
    /** 冻结评论数（违规/撤回/归档文章下的评论） */
    private Long frozenCommentCount;
    /** 缓存刷新次数 */
    private Long cacheRefreshCount;
    /** 缓存失效次数 */
    private Long cacheInvalidateCount;
    /** 平均审核耗时(毫秒) */
    private Long avgReviewDurationMs;
    /** 最短审核耗时(毫秒) */
    private Long minReviewDurationMs;
    /** 最长审核耗时(毫秒) */
    private Long maxReviewDurationMs;
    /** 完成的审核总数 */
    private Long totalReviewsCompleted;
}
