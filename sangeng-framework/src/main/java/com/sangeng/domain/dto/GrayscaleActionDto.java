package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GrayscaleActionDto {
    /** 文章ID */
    private Long articleId;
    /** 灰度目标用户组IDs (逗号分隔) */
    private String grayscaleGroups;
}
