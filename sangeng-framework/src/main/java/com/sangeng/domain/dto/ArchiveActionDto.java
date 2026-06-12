package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArchiveActionDto {
    /** 文章ID */
    private Long articleId;
    /** 归档原因 */
    private String archiveReason;
}
