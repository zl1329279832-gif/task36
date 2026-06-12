package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RepublishActionDto {
    /** 文章ID */
    private Long articleId;
    /** 重新发布原因 */
    private String reason;
}
