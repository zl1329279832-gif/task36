package com.sangeng.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArticleTransitionDto {
    private Long articleId;
    private String targetStatus;
    private String reason;
    private Date publishTime;
}
