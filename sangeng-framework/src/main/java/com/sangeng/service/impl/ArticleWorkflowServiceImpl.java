package com.sangeng.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.User;
import com.sangeng.domain.vo.ArticleListVo;
import com.sangeng.domain.vo.PageVo;
import com.sangeng.enums.AppHttpCodeEnum;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import com.sangeng.service.*;
import com.sangeng.utils.BeanCopyUtils;
import com.sangeng.utils.OssValidationUtil;
import com.sangeng.utils.RedisCache;
import com.sangeng.utils.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class ArticleWorkflowServiceImpl implements ArticleWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ArticleWorkflowServiceImpl.class);

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private CategoryService categoryService;

    @Override
    @Transactional
    public ResponseResult submitForReview(Long articleId) {
        Article article = getArticleOrThrow(articleId);

        // 仅草稿状态可提交审核
        assertStatus(article, ArticleStatusEnum.DRAFT);

        // 作者本人或管理员可操作
        checkAuthorOrAdmin(article);

        String oldStatus = article.getStatus();
        article.setStatus(ArticleStatusEnum.PENDING_REVIEW.getCode());
        articleService.updateById(article);

        logTransition(articleId, oldStatus, ArticleStatusEnum.PENDING_REVIEW.getCode(), "提交审核");

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult approve(ReviewActionDto dto) {
        Article article = getArticleOrThrow(dto.getArticleId());

        // 仅待审核状态可审批
        assertStatus(article, ArticleStatusEnum.PENDING_REVIEW);

        String oldStatus = article.getStatus();

        Date scheduledTime = dto.getScheduledPublishTime();
        if (scheduledTime != null && scheduledTime.after(new Date())) {
            // 定时发布 —— 不需要OSS校验（尚未发布）
            article.setStatus(ArticleStatusEnum.SCHEDULED.getCode());
            article.setPublishTime(scheduledTime);
            articleService.updateById(article);

            logTransition(dto.getArticleId(), oldStatus,
                    ArticleStatusEnum.SCHEDULED.getCode(),
                    "审核通过，定时发布: " + scheduledTime);
        } else {
            // 立即发布 —— 发布前校验OSS附件引用
            validateOssReferences(article);

            article.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
            article.setPublishTime(new Date());
            articleService.updateById(article);

            logTransition(dto.getArticleId(), oldStatus,
                    ArticleStatusEnum.PUBLISHED.getCode(),
                    "审核通过，立即发布");

            // 刷新缓存（先更新DB再清缓存，保证缓存一致性）
            refreshArticleCaches(article);
        }

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult reject(ReviewActionDto dto) {
        if (dto.getReason() == null || dto.getReason().trim().isEmpty()) {
            throw new SystemException(AppHttpCodeEnum.REVIEW_REASON_REQUIRED);
        }

        Article article = getArticleOrThrow(dto.getArticleId());
        assertStatus(article, ArticleStatusEnum.PENDING_REVIEW);

        String oldStatus = article.getStatus();

        article.setStatus(ArticleStatusEnum.DRAFT.getCode());
        article.setRejectReason(dto.getReason());
        articleService.updateById(article);

        logTransition(dto.getArticleId(), oldStatus,
                ArticleStatusEnum.DRAFT.getCode(),
                "审核驳回: " + dto.getReason());

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult withdraw(Long articleId) {
        Article article = getArticleOrThrow(articleId);
        assertStatus(article, ArticleStatusEnum.PUBLISHED);

        // 作者本人或管理员可撤回
        checkAuthorOrAdmin(article);

        String oldStatus = article.getStatus();
        article.setStatus(ArticleStatusEnum.WITHDRAWN.getCode());
        articleService.updateById(article);

        logTransition(articleId, oldStatus,
                ArticleStatusEnum.WITHDRAWN.getCode(), "撤回文章");

        // 清除缓存（含浏览量）
        invalidateArticleCaches(article);

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult reEdit(Long articleId) {
        Article article = getArticleOrThrow(articleId);

        String currentStatus = article.getStatus();
        if (!ArticleStatusEnum.WITHDRAWN.getCode().equals(currentStatus) &&
                !ArticleStatusEnum.VIOLATION_OFFLINE.getCode().equals(currentStatus)) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_STATUS_INVALID);
        }

        checkAuthorOrAdmin(article);

        article.setStatus(ArticleStatusEnum.DRAFT.getCode());
        article.setRejectReason(null);
        article.setViolationReason(null);
        articleService.updateById(article);

        logTransition(articleId, currentStatus,
                ArticleStatusEnum.DRAFT.getCode(), "重新编辑");

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult violationOffline(ViolationActionDto dto) {
        if (dto.getViolationReason() == null || dto.getViolationReason().trim().isEmpty()) {
            throw new SystemException(AppHttpCodeEnum.VIOLATION_REASON_REQUIRED);
        }

        Article article = getArticleOrThrow(dto.getArticleId());

        // 违规下线优先级高于定时发布：允许从 PUBLISHED 或 SCHEDULED 状态执行违规下线
        String currentStatus = article.getStatus();
        if (!ArticleStatusEnum.PUBLISHED.getCode().equals(currentStatus) &&
                !ArticleStatusEnum.SCHEDULED.getCode().equals(currentStatus)) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_STATUS_INVALID);
        }

        // 仅管理员可执行违规下线
        if (!SecurityUtils.isAdmin()) {
            throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }

        article.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        article.setViolationReason(dto.getViolationReason());
        articleService.updateById(article);

        logTransition(dto.getArticleId(), currentStatus,
                ArticleStatusEnum.VIOLATION_OFFLINE.getCode(),
                "违规下线: " + dto.getViolationReason());

        // 先落库、再按顺序失效缓存：详情 → 首页列表 → 分类 → 浏览量
        invalidateArticleCaches(article);

        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult forcePublish(Long articleId) {
        Article article = getArticleOrThrow(articleId);

        // 仅管理员可强制发布
        if (!SecurityUtils.isAdmin()) {
            throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }

        // 强制发布前必须通过OSS附件引用校验
        validateOssReferences(article);

        String oldStatus = article.getStatus();

        article.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        article.setPublishTime(new Date());
        article.setRejectReason(null);
        article.setViolationReason(null);
        articleService.updateById(article);

        logTransition(articleId, oldStatus,
                ArticleStatusEnum.PUBLISHED.getCode(), "强制发布");

        // 刷新缓存（重新发布时恢复分类/标签统计）
        refreshArticleCaches(article);

        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult getPendingReviewArticles(Integer pageNum, Integer pageSize) {
        return getArticlesByStatus(ArticleStatusEnum.PENDING_REVIEW.getCode(), pageNum, pageSize);
    }

    @Override
    public ResponseResult getScheduledArticles(Integer pageNum, Integer pageSize) {
        return getArticlesByStatus(ArticleStatusEnum.SCHEDULED.getCode(), pageNum, pageSize);
    }

    @Override
    public ResponseResult getViolationArticles(Integer pageNum, Integer pageSize) {
        return getArticlesByStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), pageNum, pageSize);
    }

    @Override
    public ResponseResult getAuditHistory(Long articleId) {
        return ResponseResult.okResult(auditLogService.getAuditHistory(articleId));
    }

    // ========== 私有辅助方法 ==========

    private Article getArticleOrThrow(Long articleId) {
        Article article = articleService.getById(articleId);
        if (article == null) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        return article;
    }

    private void assertStatus(Article article, ArticleStatusEnum expected) {
        if (!expected.getCode().equals(article.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_STATUS_INVALID);
        }
    }

    private void checkAuthorOrAdmin(Article article) {
        Long currentUserId = SecurityUtils.getUserId();
        if (!SecurityUtils.isAdmin() && !article.getCreateBy().equals(currentUserId)) {
            throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
        }
    }

    private ResponseResult getArticlesByStatus(String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Article::getStatus, status)
               .orderByDesc(Article::getCreateTime);

        Page<Article> page = new Page<>(pageNum, pageSize);
        articleService.page(page, wrapper);

        List<Article> articles = page.getRecords();
        // 设置分类名称
        articles.forEach(article -> {
            if (article.getCategoryId() != null) {
                com.sangeng.domain.entity.Category category = categoryService.getById(article.getCategoryId());
                if (category != null) {
                    article.setCategoryName(category.getName());
                }
            }
        });

        List<ArticleListVo> voList = BeanCopyUtils.copyBeanList(articles, ArticleListVo.class);
        PageVo pageVo = new PageVo(voList, page.getTotal());

        return ResponseResult.okResult(pageVo);
    }

    private void logTransition(Long articleId, String fromStatus, String toStatus, String reason) {
        Long operatorId = SecurityUtils.getUserId();
        User operator = userService.getById(operatorId);
        String operatorName = operator != null ? operator.getNickName() : "Unknown";

        auditLogService.logOperation(articleId, fromStatus, toStatus, operatorId, operatorName, reason);
    }

    /**
     * 校验文章内容和缩略图中的OSS附件引用是否有效。
     * 若任一引用失效，写入审计日志并抛出异常阻止发布。
     */
    private void validateOssReferences(Article article) {
        List<String> invalidUrls = new ArrayList<>();

        // 校验内容中的图片引用
        List<String> contentUrls = OssValidationUtil.extractImageUrls(article.getContent());
        for (String url : contentUrls) {
            if (!OssValidationUtil.verifyOssReference(url)) {
                invalidUrls.add(url);
            }
        }

        // 校验缩略图引用
        if (article.getThumbnail() != null && !article.getThumbnail().isEmpty()) {
            if (!OssValidationUtil.verifyOssReference(article.getThumbnail())) {
                invalidUrls.add(article.getThumbnail());
            }
        }

        if (!invalidUrls.isEmpty()) {
            // 写入审计日志：记录OSS引用校验失败
            auditLogService.logOperation(
                    article.getId(),
                    article.getStatus(),
                    article.getStatus(),  // 状态未变更
                    SecurityUtils.getUserId(),
                    getOperatorName(),
                    "OSS附件引用失效，阻止发布。失效URL: " + String.join("; ", invalidUrls)
            );

            log.warn("文章[id={}] OSS附件引用失效，阻止发布。失效URL: {}", article.getId(), invalidUrls);
            throw new SystemException(AppHttpCodeEnum.OSS_REFERENCE_INVALID);
        }
    }

    private String getOperatorName() {
        try {
            Long operatorId = SecurityUtils.getUserId();
            User operator = userService.getById(operatorId);
            return operator != null ? operator.getNickName() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * 刷新文章相关缓存（发布/重新发布时调用）。
     * 按依赖顺序失效：详情 → 首页列表 → 分类列表，保证重新查询时数据一致。
     */
    private void refreshArticleCaches(Article article) {
        // 1. 删除文章详情缓存（使下次查询获取最新状态）
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + article.getId();
        redisCache.deleteObject(detailKey);

        // 2. 删除首页文章列表缓存
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);

        // 3. 删除分类列表缓存（重新发布时恢复分类/标签统计）
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);
    }

    /**
     * 清除文章相关缓存（下线/撤回时调用）。
     * 失效顺序：DB已更新 → 详情缓存 → 首页列表 → 分类列表 → 浏览量哈希。
     * 先落库再清缓存，避免清缓存后查询到旧数据回填缓存。
     */
    private void invalidateArticleCaches(Article article) {
        // 1. 删除文章详情缓存
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + article.getId();
        redisCache.deleteObject(detailKey);

        // 2. 删除首页文章列表缓存
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);

        // 3. 删除分类列表缓存（下线后分类统计不再计入该文章）
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);

        // 4. 清除该文章的浏览量缓存（下线文章不再追踪浏览量）
        redisCache.delCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                article.getId().toString());
    }
}
