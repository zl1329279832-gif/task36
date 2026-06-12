package com.sangeng.service;

import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ArchiveActionDto;
import com.sangeng.domain.dto.GrayscaleActionDto;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;

/**
 * 文章生命周期工作流服务
 */
public interface ArticleWorkflowService {

    // ========== 作者操作 ==========

    /** 提交审核：草稿 -> 待审核 */
    ResponseResult submitForReview(Long articleId);

    /** 撤回文章：已发布 -> 已撤回 */
    ResponseResult withdraw(Long articleId);

    /** 重新编辑：已撤回/违规下架 -> 草稿 */
    ResponseResult reEdit(Long articleId);

    // ========== 审核员操作 ==========

    /** 审核通过：待审核 -> 已发布/定时发布 */
    ResponseResult approve(ReviewActionDto dto);

    /** 审核驳回：待审核 -> 草稿 */
    ResponseResult reject(ReviewActionDto dto);

    // ========== 管理员操作 ==========

    /** 违规下架：已发布 -> 违规下架 */
    ResponseResult violationOffline(ViolationActionDto dto);

    /** 强制发布：任意状态 -> 已发布 */
    ResponseResult forcePublish(Long articleId);

    // ========== 查询方法 ==========

    /** 获取待审核文章列表 */
    ResponseResult getPendingReviewArticles(Integer pageNum, Integer pageSize);

    /** 获取定时发布文章列表 */
    ResponseResult getScheduledArticles(Integer pageNum, Integer pageSize);

    /** 获取违规文章列表 */
    ResponseResult getViolationArticles(Integer pageNum, Integer pageSize);

    /** 获取文章审核历史 */
    ResponseResult getAuditHistory(Long articleId);

    // ========== 灰度发布操作 ==========

    /** 设置灰度可见：已发布 -> 灰度可见 */
    ResponseResult setGrayscale(GrayscaleActionDto dto);

    /** 全量发布：灰度可见 -> 已发布 */
    ResponseResult fullPublish(Long articleId);

    // ========== 归档操作 ==========

    /** 归档文章：已发布/已撤回 -> 归档 */
    ResponseResult archive(ArchiveActionDto dto);

    // ========== 管理员快速恢复 ==========

    /** 重新发布：违规下架/已撤回 -> 已发布（管理员，绕过审核） */
    ResponseResult republish(Long articleId);

    // ========== 仪表盘 ==========

    /** 获取运维仪表盘统计数据 */
    ResponseResult getDashboardStats();

    // ========== 查询方法扩展 ==========

    /** 获取灰度可见文章列表 */
    ResponseResult getGrayscaleArticles(Integer pageNum, Integer pageSize);

    /** 获取归档文章列表 */
    ResponseResult getArchivedArticles(Integer pageNum, Integer pageSize);
}
