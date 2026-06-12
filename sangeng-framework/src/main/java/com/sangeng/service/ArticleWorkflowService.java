package com.sangeng.service;

import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ArchiveActionDto;
import com.sangeng.domain.dto.RepublishActionDto;
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

    /** 灰度完全发布：灰度可见 -> 已发布 */
    ResponseResult fullPublish(Long articleId);

    /** 重新发布：已撤回/违规下架/已归档 -> 重新发布 -> 待审核 */
    ResponseResult republish(RepublishActionDto dto);

    /** 归档：已发布/已撤回/灰度可见 -> 已归档 */
    ResponseResult archive(ArchiveActionDto dto);

    // ========== 查询方法 ==========

    /** 获取待审核文章列表 */
    ResponseResult getPendingReviewArticles(Integer pageNum, Integer pageSize);

    /** 获取定时发布文章列表 */
    ResponseResult getScheduledArticles(Integer pageNum, Integer pageSize);

    /** 获取违规文章列表 */
    ResponseResult getViolationArticles(Integer pageNum, Integer pageSize);

    /** 获取灰度可见文章列表 */
    ResponseResult getGrayArticles(Integer pageNum, Integer pageSize);

    /** 获取已归档文章列表 */
    ResponseResult getArchivedArticles(Integer pageNum, Integer pageSize);

    /** 获取文章审核历史 */
    ResponseResult getAuditHistory(Long articleId);
}
