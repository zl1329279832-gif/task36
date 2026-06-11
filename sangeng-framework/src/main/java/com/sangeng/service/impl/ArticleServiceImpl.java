package com.sangeng.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sangeng.constants.SystemConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.AddArticleDto;
import com.sangeng.domain.dto.ArticleDto;
import com.sangeng.domain.dto.ArticleTransitionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.ArticleOperationLog;
import com.sangeng.domain.entity.ArticleTag;
import com.sangeng.domain.entity.Category;
import com.sangeng.domain.vo.*;
import com.sangeng.enums.AppHttpCodeEnum;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import com.sangeng.mapper.ArticleMapper;
import com.sangeng.service.ArticleOperationLogService;
import com.sangeng.service.ArticleService;
import com.sangeng.service.ArticleTagService;
import com.sangeng.service.CategoryService;
import com.sangeng.utils.BeanCopyUtils;
import com.sangeng.utils.OssReferenceValidator;
import com.sangeng.utils.RedisCache;
import com.sangeng.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ArticleTagService articleTagService;

    @Autowired
    private ArticleOperationLogService articleOperationLogService;

    @Autowired
    private PermissionService permissionService;

    @Override
    public ResponseResult hotArticleList() {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Article::getStatus, SystemConstants.ARTICLE_STATUS_NORMAL);
        queryWrapper.orderByDesc(Article::getViewCount);
        Page<Article> page = new Page(1,10);
        page(page,queryWrapper);

        List<Article> articles = page.getRecords();
        List<HotArticleVo> vs = BeanCopyUtils.copyBeanList(articles, HotArticleVo.class);
        return ResponseResult.okResult(vs);
    }

    @Override
    public ResponseResult articleList(Integer pageNum, Integer pageSize, Long categoryId) {
        LambdaQueryWrapper<Article> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Objects.nonNull(categoryId)&&categoryId>0 ,Article::getCategoryId,categoryId);
        lambdaQueryWrapper.eq(Article::getStatus,SystemConstants.ARTICLE_STATUS_NORMAL);
        lambdaQueryWrapper.orderByDesc(Article::getIsTop);

        Page<Article> page = new Page<>(pageNum,pageSize);
        page(page,lambdaQueryWrapper);

        List<Article> articles = page.getRecords();
        articles.stream()
                .map(article -> article.setCategoryName(categoryService.getById(article.getCategoryId()).getName()))
                .collect(Collectors.toList());

        List<ArticleListVo> articleListVos = BeanCopyUtils.copyBeanList(page.getRecords(), ArticleListVo.class);

        PageVo pageVo = new PageVo(articleListVos,page.getTotal());
        return ResponseResult.okResult(pageVo);
    }

    @Override
    public ResponseResult getArticleDetail(Long id) {
        Article article = getById(id);
        Integer viewCount = redisCache.getCacheMapValue(SystemConstants.ARTICLE_VIEW_COUNT_KEY, id.toString());
        article.setViewCount(viewCount.longValue());
        ArticleDetailVo articleDetailVo = BeanCopyUtils.copyBean(article, ArticleDetailVo.class);
        Long categoryId = articleDetailVo.getCategoryId();
        Category category = categoryService.getById(categoryId);
        if(category!=null){
            articleDetailVo.setCategoryName(category.getName());
        }
        return ResponseResult.okResult(articleDetailVo);
    }

    @Override
    public ResponseResult updateViewCount(Long id) {
        redisCache.incrementCacheMapValue(SystemConstants.ARTICLE_VIEW_COUNT_KEY,id.toString(),1);
        return ResponseResult.okResult();
    }

    @Override
    @Transactional
    public ResponseResult add(AddArticleDto articleDto) {
        Article article = BeanCopyUtils.copyBean(articleDto, Article.class);
        // 新文章强制为草稿状态
        article.setStatus(SystemConstants.ARTICLE_STATUS_DRAFT_STR);
        save(article);

        List<ArticleTag> articleTags = articleDto.getTags().stream()
                .map(tagId -> new ArticleTag(article.getId(), tagId))
                .collect(Collectors.toList());

        articleTagService.saveBatch(articleTags);
        return ResponseResult.okResult();
    }

    @Override
    public PageVo selectArticlePage(Article article, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper();

        queryWrapper.like(StringUtils.hasText(article.getTitle()),Article::getTitle, article.getTitle());
        queryWrapper.like(StringUtils.hasText(article.getSummary()),Article::getSummary, article.getSummary());

        Page<Article> page = new Page<>();
        page.setCurrent(pageNum);
        page.setSize(pageSize);
        page(page,queryWrapper);

        List<Article> articles = page.getRecords();

        PageVo pageVo = new PageVo();
        pageVo.setTotal(page.getTotal());
        pageVo.setRows(articles);
        return pageVo;
    }

    @Override
    public ArticleVo getInfo(Long id) {
        Article article = getById(id);
        LambdaQueryWrapper<ArticleTag> articleTagLambdaQueryWrapper = new LambdaQueryWrapper<>();
        articleTagLambdaQueryWrapper.eq(ArticleTag::getArticleId,article.getId());
        List<ArticleTag> articleTags = articleTagService.list(articleTagLambdaQueryWrapper);
        List<Long> tags = articleTags.stream().map(articleTag -> articleTag.getTagId()).collect(Collectors.toList());

        ArticleVo articleVo = BeanCopyUtils.copyBean(article,ArticleVo.class);
        articleVo.setTags(tags);
        return articleVo;
    }

    @Override
    public void edit(ArticleDto articleDto) {
        // 仅允许草稿状态的文章编辑内容
        Article existingArticle = getById(articleDto.getId());
        if (existingArticle == null) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        if (!SystemConstants.ARTICLE_STATUS_DRAFT_STR.equals(existingArticle.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.INVALID_STATE_TRANSITION);
        }

        Article article = BeanCopyUtils.copyBean(articleDto, Article.class);
        // 编辑时忽略status字段，保持当前状态
        article.setStatus(null);
        updateById(article);

        // 删除原有的标签和博客的关联
        LambdaQueryWrapper<ArticleTag> articleTagLambdaQueryWrapper = new LambdaQueryWrapper<>();
        articleTagLambdaQueryWrapper.eq(ArticleTag::getArticleId,article.getId());
        articleTagService.remove(articleTagLambdaQueryWrapper);
        // 添加新的博客和标签的关联信息
        List<ArticleTag> articleTags = articleDto.getTags().stream()
                .map(tagId -> new ArticleTag(articleDto.getId(), tagId))
                .collect(Collectors.toList());
        articleTagService.saveBatch(articleTags);
    }

    @Override
    @Transactional
    public ResponseResult transitionStatus(ArticleTransitionDto dto) {
        // 1. 加载文章
        Article article = getById(dto.getArticleId());
        if (article == null) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }

        String currentStatus = article.getStatus();
        String targetStatus = dto.getTargetStatus();
        Long currentUserId = SecurityUtils.getUserId();

        // 2. 校验状态转换合法性
        if (!ArticleStatusEnum.isValidTransition(currentStatus, targetStatus)) {
            throw new SystemException(AppHttpCodeEnum.INVALID_STATE_TRANSITION);
        }

        // 3. 按目标状态做权限校验
        checkTransitionPermission(article, currentStatus, targetStatus, currentUserId);

        // 4. 执行特殊转换逻辑
        handleTransitionLogic(article, targetStatus, dto);

        // 5. 更新文章状态
        article.setStatus(targetStatus);
        updateById(article);

        // 6. 记录操作日志
        articleOperationLogService.logTransition(
                article.getId(), currentUserId, currentStatus, targetStatus, dto.getReason());

        // 7. 缓存处理
        handleCacheOnTransition(article, targetStatus);

        return ResponseResult.okResult();
    }

    @Override
    public void publishScheduledArticles() {
        // 查询所有到期的定时发布文章
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Article::getStatus, SystemConstants.ARTICLE_STATUS_SCHEDULED);
        queryWrapper.le(Article::getPublishTime, new Date());

        List<Article> scheduledArticles = list(queryWrapper);
        for (Article article : scheduledArticles) {
            String fromStatus = article.getStatus();
            article.setStatus(SystemConstants.ARTICLE_STATUS_PUBLISHED);
            article.setPublishTime(null);
            article.setUpdateTime(new Date());
            article.setUpdateBy(-1L);
            // 使用baseMapper直接更新，避免MetaObjectHandler在无SecurityContext时出错
            getBaseMapper().updateById(article);

            // 刷新 Redis 浏览量缓存
            Long viewCount = article.getViewCount();
            if (viewCount == null) {
                viewCount = 0L;
            }
            redisCache.setCacheMapValue(SystemConstants.ARTICLE_VIEW_COUNT_KEY,
                    article.getId().toString(), viewCount.intValue());

            // 记录系统自动操作日志
            articleOperationLogService.logTransition(
                    article.getId(), -1L, fromStatus,
                    SystemConstants.ARTICLE_STATUS_PUBLISHED, "定时发布自动执行");
        }
    }

    @Override
    public ResponseResult getOperationLog(Long articleId) {
        List<ArticleOperationLog> logs = articleOperationLogService.getLogsByArticleId(articleId);
        return ResponseResult.okResult(logs);
    }

    /**
     * 校验当前用户是否有权限执行该状态转换
     */
    private void checkTransitionPermission(Article article, String currentStatus,
                                           String targetStatus, Long currentUserId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        // 管理员拥有所有权限
        if (isAdmin) {
            return;
        }

        boolean isOwner = currentUserId.equals(article.getCreateBy());

        switch (targetStatus) {
            case SystemConstants.ARTICLE_STATUS_PENDING_REVIEW:
                // 提交审核：作者本人可提交
                if (!isOwner && !permissionService.hasPermission("content:article:submitReview")) {
                    throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_OWNED);
                }
                break;
            case SystemConstants.ARTICLE_STATUS_PUBLISHED:
            case SystemConstants.ARTICLE_STATUS_SCHEDULED:
                // 审核通过：需要审核权限
                if (!permissionService.hasPermission("content:article:approve")) {
                    throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
                }
                break;
            case SystemConstants.ARTICLE_STATUS_DRAFT_STR:
                // 驳回：需要驳回权限
                if (!permissionService.hasPermission("content:article:reject")) {
                    throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
                }
                break;
            case SystemConstants.ARTICLE_STATUS_WITHDRAWN:
                // 撤回：作者本人或拥有撤回权限
                if (!isOwner && !permissionService.hasPermission("content:article:withdraw")) {
                    throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_OWNED);
                }
                break;
            case SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE:
                // 违规下线：需要专门权限
                if (!permissionService.hasPermission("content:article:violationOffline")) {
                    throw new SystemException(AppHttpCodeEnum.NO_OPERATOR_AUTH);
                }
                break;
            default:
                throw new SystemException(AppHttpCodeEnum.INVALID_STATE_TRANSITION);
        }
    }

    /**
     * 处理特定转换的业务逻辑
     */
    private void handleTransitionLogic(Article article, String targetStatus,
                                       ArticleTransitionDto dto) {
        switch (targetStatus) {
            case SystemConstants.ARTICLE_STATUS_PUBLISHED:
                // 发布时进行 OSS 附件引用校验
                List<String> brokenRefs = OssReferenceValidator.findBrokenReferences(article.getContent());
                if (!brokenRefs.isEmpty()) {
                    throw new SystemException(AppHttpCodeEnum.OSS_REFERENCE_INVALID);
                }
                // 发布时清空定时发布时间
                article.setPublishTime(null);
                break;
            case SystemConstants.ARTICLE_STATUS_SCHEDULED:
                // 定时发布：校验 publishTime 必须在未来
                if (dto.getPublishTime() == null || !dto.getPublishTime().after(new Date())) {
                    throw new SystemException(AppHttpCodeEnum.SYSTEM_ERROR);
                }
                // OSS 校验
                List<String> brokenRefsScheduled = OssReferenceValidator.findBrokenReferences(article.getContent());
                if (!brokenRefsScheduled.isEmpty()) {
                    throw new SystemException(AppHttpCodeEnum.OSS_REFERENCE_INVALID);
                }
                article.setPublishTime(dto.getPublishTime());
                break;
            case SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE:
                // 违规下线：冻结评论入口，记录违规原因
                article.setIsComment(SystemConstants.COMMENT_DISALLOWED);
                article.setViolationReason(dto.getReason());
                break;
            case SystemConstants.ARTICLE_STATUS_PENDING_REVIEW:
                // 重新提交审核时，清空违规原因
                article.setViolationReason(null);
                break;
            default:
                break;
        }
    }

    /**
     * 状态转换后的缓存处理
     */
    private void handleCacheOnTransition(Article article, String targetStatus) {
        if (SystemConstants.ARTICLE_STATUS_PUBLISHED.equals(targetStatus)) {
            // 发布时：确保 Redis 浏览量缓存中有该文章
            Long viewCount = article.getViewCount();
            if (viewCount == null) {
                viewCount = 0L;
            }
            redisCache.setCacheMapValue(SystemConstants.ARTICLE_VIEW_COUNT_KEY,
                    article.getId().toString(), viewCount.intValue());
        } else if (SystemConstants.ARTICLE_STATUS_WITHDRAWN.equals(targetStatus)
                || SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE.equals(targetStatus)) {
            // 下线时：移除 Redis 浏览量缓存
            redisCache.delCacheMapValue(SystemConstants.ARTICLE_VIEW_COUNT_KEY,
                    article.getId().toString());
        }
    }
}
