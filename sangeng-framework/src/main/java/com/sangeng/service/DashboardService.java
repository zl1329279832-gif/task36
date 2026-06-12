package com.sangeng.service;

import com.sangeng.domain.ResponseResult;

/**
 * 运营看板统计服务
 */
public interface DashboardService {

    /** 获取运营看板统计数据 */
    ResponseResult getDashboardStats();
}
