package com.sangeng.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文章审核操作日志(ArticleAuditLog)实体类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("sg_article_audit_log")
public class ArticleAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文章ID */
    private Long articleId;

    /** 原状态 */
    private String fromStatus;

    /** 新状态 */
    private String toStatus;

    /** 操作人ID */
    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;

    /** 操作原因/备注 */
    private String reason;

    /** 操作时间 */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
