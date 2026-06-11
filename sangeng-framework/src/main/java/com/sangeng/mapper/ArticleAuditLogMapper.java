package com.sangeng.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sangeng.domain.entity.ArticleAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleAuditLogMapper extends BaseMapper<ArticleAuditLog> {
}
