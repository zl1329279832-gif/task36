package com.sangeng.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sangeng.domain.entity.ArticleOperationLog;

import java.util.List;

public interface ArticleOperationLogService extends IService<ArticleOperationLog> {

    void logTransition(Long articleId, Long operatorId, String fromStatus, String toStatus, String reason);

    List<ArticleOperationLog> getLogsByArticleId(Long articleId);
}
