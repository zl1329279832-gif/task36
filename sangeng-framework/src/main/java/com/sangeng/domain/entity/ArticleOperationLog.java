package com.sangeng.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("sg_article_operation_log")
public class ArticleOperationLog {
    @TableId
    private Long id;
    private Long articleId;
    private Long operatorId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private Date createTime;
    private Integer delFlag;
}
