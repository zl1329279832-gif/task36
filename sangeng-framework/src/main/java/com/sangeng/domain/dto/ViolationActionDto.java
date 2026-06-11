package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ViolationActionDto {
    /** 文章ID */
    private Long articleId;
    /** 违规原因（必填） */
    private String violationReason;
}
