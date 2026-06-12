package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReviewActionDto {
    /** 文章ID */
    private Long articleId;
    /** 驳回原因（驳回时必填） */
    private String reason;
    /** 定时发布时间（不为空且在未来则为定时发布） */
    private Date scheduledPublishTime;
    /** 是否灰度发布 */
    private Boolean grayVisible;
    /** 灰度受众（逗号分隔的用户ID，grayVisible为true时必填） */
    private String grayAudience;
}
