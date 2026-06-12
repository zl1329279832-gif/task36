package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ArchiveActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.enums.AppHttpCodeEnum;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import com.sangeng.utils.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 灰度可见（GRAY_VISIBLE）工作流测试 - 覆盖灰度发布、全量发布、权限控制、受众隔离、缓存等场景
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class GrayVisibleFlowTest {

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ArticleAuditLogService auditLogService;

    private Long testArticleId;
    private Long adminUserId = 1L;

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();

        // 创建测试文章（草稿状态）
        Article article = new Article();
        article.setTitle("测试文章 - 灰度可见");
        article.setContent("测试内容");
        article.setSummary("测试摘要");
        article.setCategoryId(1L);
        article.setStatus(ArticleStatusEnum.DRAFT.getCode());
        article.setViewCount(0L);
        article.setIsTop("0");
        article.setIsComment("1");
        article.setCreateBy(adminUserId);
        articleService.save(article);
        testArticleId = article.getId();
    }

    @AfterEach
    void tearDown() {
        if (testArticleId != null) {
            articleService.removeById(testArticleId);
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * 灰度发布：待审核 -> 灰度可见（grayVisible=true, grayAudience="1,2,3"）
     */
    @Test
    void testApproveWithGrayVisible() {
        // 1. 草稿 -> 提交审核
        workflowService.submitForReview(testArticleId);
        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());

        // 2. 审核通过（灰度发布）
        ReviewActionDto dto = new ReviewActionDto();
        dto.setArticleId(testArticleId);
        dto.setGrayVisible(true);
        dto.setGrayAudience("1,2,3");
        workflowService.approve(dto);

        // 3. 验证文章状态为灰度可见
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());
        assertEquals("1,2,3", article.getGrayAudience());
        assertNotNull(article.getGrayPublishTime());
    }

    /**
     * 灰度发布必须指定受众：grayVisible=true 但 grayAudience 为空应抛出 SystemException
     */
    @Test
    void testGrayRequiresAudience() {
        workflowService.submitForReview(testArticleId);

        ReviewActionDto dto = new ReviewActionDto();
        dto.setArticleId(testArticleId);
        dto.setGrayVisible(true);
        // 不设置 grayAudience

        SystemException ex = assertThrows(SystemException.class, () -> {
            workflowService.approve(dto);
        });
        assertEquals(AppHttpCodeEnum.GRAY_AUDIENCE_REQUIRED.getCode(), ex.getCode());
    }

    /**
     * 灰度全量发布：灰度可见 -> 已发布
     */
    @Test
    void testFullPublishFromGray() {
        // 1. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 全量发布
        workflowService.fullPublish(testArticleId);

        // 3. 验证文章状态为已发布
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
        assertNotNull(article.getPublishTime());
    }

    /**
     * 全量发布权限控制：非管理员不能执行 fullPublish
     */
    @Test
    void testFullPublishRequiresAdmin() {
        // 1. 管理员先将文章推进到灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 切换为非管理员
        mockLoginAsNonAdmin();

        // 3. 非管理员尝试全量发布应被拒绝
        assertThrows(SystemException.class, () -> {
            workflowService.fullPublish(testArticleId);
        });

        // 4. 验证文章状态未变
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());
    }

    /**
     * 灰度可见 -> 违规下架（管理员操作）
     */
    @Test
    void testViolationOfflineFromGray() {
        // 1. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 管理员执行违规下线
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("灰度期间发现违规内容");
        workflowService.violationOffline(violationDto);

        // 3. 验证文章已变为违规下线状态
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
        assertEquals("灰度期间发现违规内容", article.getViolationReason());
    }

    /**
     * 灰度可见 -> 已归档
     */
    @Test
    void testArchiveFromGray() {
        // 1. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 归档
        ArchiveActionDto archiveDto = new ArchiveActionDto();
        archiveDto.setArticleId(testArticleId);
        archiveDto.setArchiveReason("灰度测试完成，内容过时");
        workflowService.archive(archiveDto);

        // 3. 验证文章已归档
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.ARCHIVED.getCode(), article.getStatus());
    }

    /**
     * 灰度文章不应出现在公开文章列表中
     */
    @Test
    void testGrayNotInPublicList() {
        // 1. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 查询公开文章列表
        ResponseResult result = articleService.articleList(1, 10, null);
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // 3. 灰度文章不应出现在公开列表中
        if (result.getData() != null) {
            String dataStr = result.getData().toString();
            assertFalse(dataStr.contains(testArticleId.toString()),
                    "灰度文章不应出现在公开文章列表中");
        }
    }

    /**
     * 灰度受众内用户可以查看文章详情
     */
    @Test
    void testGrayDetailForAudience() {
        // 1. 草稿 -> 提交审核 -> 灰度可见（受众包含用户1）
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 以受众内用户（id=1）登录查看详情
        mockLoginAsUser(1L, "1");

        ResponseResult result = articleService.getArticleDetail(testArticleId);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
    }

    /**
     * 灰度受众外用户不能查看文章详情
     */
    @Test
    void testGrayDetailDeniedForNonAudience() {
        // 1. 草稿 -> 提交审核 -> 灰度可见（受众仅为"1"）
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 以非受众用户（id=99）登录查看详情应被拒绝
        mockLoginAsUser(99L, "0");

        assertThrows(SystemException.class, () -> {
            articleService.getArticleDetail(testArticleId);
        });
    }

    /**
     * 灰度发布后缓存应被刷新（详情缓存、首页列表缓存、分类列表缓存均被删除）
     */
    @Test
    void testGrayCacheRefreshed() {
        // 1. 预设缓存数据
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "stale_detail");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES, "stale_home");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST, "stale_category");

        // 2. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        // 3. 验证缓存已被清除
        assertNull(redisCache.getCacheObject(detailKey), "灰度发布后文章详情缓存应被清除");
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES),
                "灰度发布后首页列表缓存应被清除");
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST),
                "灰度发布后分类列表缓存应被清除");
    }

    /**
     * 灰度文章的浏览量可以正常追踪更新
     */
    @Test
    void testGrayViewCountTracked() {
        // 1. 草稿 -> 提交审核 -> 灰度可见
        workflowService.submitForReview(testArticleId);

        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1,2,3");
        workflowService.approve(grayDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.GRAY_VISIBLE.getCode(), article.getStatus());

        // 2. 更新浏览量（应成功，不抛异常）
        ResponseResult result = articleService.updateViewCount(testArticleId);
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // 3. 验证浏览量已增加
        article = articleService.getById(testArticleId);
        assertTrue(article.getViewCount() > 0, "灰度文章浏览量应被更新");
    }

    // ========== 辅助方法 ==========

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(adminUserId);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<String> perms = Arrays.asList(
                "content:article:submit",
                "content:article:approve",
                "content:article:reject",
                "content:article:withdraw",
                "content:article:violation",
                "content:article:forcePublish",
                "content:article:fullPublish",
                "content:article:grayPublish",
                "content:article:archive",
                "content:article:review",
                "content:article:republish"
        );

        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        LoginUser loginUser = new LoginUser(user, perms);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private void mockLoginAsNonAdmin() {
        User user = new User();
        user.setId(99L);
        user.setUserName("normaluser");
        user.setNickName("普通用户");
        user.setType("0");

        List<String> perms = Arrays.asList(
                "content:article:submit"
        );

        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        LoginUser loginUser = new LoginUser(user, perms);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private void mockLoginAsUser(Long userId, String type) {
        User user = new User();
        user.setId(userId);
        user.setUserName("user" + userId);
        user.setNickName("用户" + userId);
        user.setType(type);

        List<String> perms = Arrays.asList(
                "content:article:submit"
        );

        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        LoginUser loginUser = new LoginUser(user, perms);

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
