package com.sangeng.service.impl;

import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.service.CacheOperationTracker;
import com.sangeng.utils.RedisCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis HINCRBY 的缓存操作计数器实现
 */
@Service
public class CacheOperationTrackerImpl implements CacheOperationTracker {

    @Autowired
    private RedisCache redisCache;

    @Override
    public void trackRefresh(Long articleId) {
        redisCache.incrementCacheMapValue(
                ArticleWorkflowConstants.CACHE_DASHBOARD_REFRESH_COUNT, "total", 1);
    }

    @Override
    public void trackInvalidate(Long articleId) {
        redisCache.incrementCacheMapValue(
                ArticleWorkflowConstants.CACHE_DASHBOARD_INVALIDATE_COUNT, "total", 1);
    }

    @Override
    public long getRefreshCount() {
        Integer count = redisCache.getCacheMapValue(
                ArticleWorkflowConstants.CACHE_DASHBOARD_REFRESH_COUNT, "total");
        return count != null ? count.longValue() : 0L;
    }

    @Override
    public long getInvalidateCount() {
        Integer count = redisCache.getCacheMapValue(
                ArticleWorkflowConstants.CACHE_DASHBOARD_INVALIDATE_COUNT, "total");
        return count != null ? count.longValue() : 0L;
    }
}
