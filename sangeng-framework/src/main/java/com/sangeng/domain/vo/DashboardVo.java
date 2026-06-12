package com.sangeng.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DashboardVo {
    /** 各状态文章数量统计 */
    private List<StatusCountVo> statusCounts;
    /** 冻结评论数（非已发布文章上的评论总数） */
    private Long frozenCommentCount;
    /** 缓存刷新次数 */
    private Long cacheRefreshCount;
    /** 平均审核耗时（毫秒） */
    private Double averageReviewTimeMs;
}
