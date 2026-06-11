package com.sangeng.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sangeng.domain.entity.ArticleOperationLog;
import com.sangeng.mapper.ArticleOperationLogMapper;
import com.sangeng.service.ArticleOperationLogService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service("articleOperationLogService")
public class ArticleOperationLogServiceImpl
        extends ServiceImpl<ArticleOperationLogMapper, ArticleOperationLog>
        implements ArticleOperationLogService {

    @Override
    public void logTransition(Long articleId, Long operatorId,
                              String fromStatus, String toStatus, String reason) {
        ArticleOperationLog log = new ArticleOperationLog();
        log.setArticleId(articleId);
        log.setOperatorId(operatorId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setReason(reason);
        log.setCreateTime(new Date());
        log.setDelFlag(0);
        save(log);
    }

    @Override
    public List<ArticleOperationLog> getLogsByArticleId(Long articleId) {
        LambdaQueryWrapper<ArticleOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ArticleOperationLog::getArticleId, articleId);
        wrapper.orderByDesc(ArticleOperationLog::getCreateTime);
        return list(wrapper);
    }
}
