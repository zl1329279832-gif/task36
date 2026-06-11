package com.sangeng.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sangeng.domain.entity.ArticleAuditLog;
import com.sangeng.mapper.ArticleAuditLogMapper;
import com.sangeng.service.ArticleAuditLogService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ArticleAuditLogServiceImpl extends ServiceImpl<ArticleAuditLogMapper, ArticleAuditLog>
        implements ArticleAuditLogService {

    @Override
    public void logOperation(Long articleId, String fromStatus, String toStatus,
                             Long operatorId, String operatorName, String reason) {
        ArticleAuditLog log = new ArticleAuditLog();
        log.setArticleId(articleId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setReason(reason);
        save(log);
    }

    @Override
    public List<ArticleAuditLog> getAuditHistory(Long articleId) {
        LambdaQueryWrapper<ArticleAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ArticleAuditLog::getArticleId, articleId)
               .orderByDesc(ArticleAuditLog::getCreateTime);
        return list(wrapper);
    }
}
