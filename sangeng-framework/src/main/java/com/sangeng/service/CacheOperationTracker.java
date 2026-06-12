package com.sangeng.service;

/**
 * 缓存操作计数器，用于运营看板统计缓存刷新/失效次数
 */
public interface CacheOperationTracker {

    /** 记录一次缓存刷新操作 */
    void trackRefresh(Long articleId);

    /** 记录一次缓存失效操作 */
    void trackInvalidate(Long articleId);

    /** 获取累计刷新次数 */
    long getRefreshCount();

    /** 获取累计失效次数 */
    long getInvalidateCount();
}
