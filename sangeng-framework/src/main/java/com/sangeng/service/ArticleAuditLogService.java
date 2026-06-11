package com.sangeng.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sangeng.domain.entity.ArticleAuditLog;

import java.util.List;

public interface ArticleAuditLogService extends IService<ArticleAuditLog> {

    /**
     * 记录操作日志
     */
    void logOperation(Long articleId, String fromStatus, String toStatus,
                      Long operatorId, String operatorName, String reason);

    /**
     * 获取文章的审核操作历史
     */
    List<ArticleAuditLog> getAuditHistory(Long articleId);
}
