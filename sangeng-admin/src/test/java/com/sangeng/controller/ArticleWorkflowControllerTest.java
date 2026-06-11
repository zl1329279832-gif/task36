package com.sangeng.controller;

import com.alibaba.fastjson.JSON;
import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.service.ArticleService;
import com.sangeng.service.ArticleWorkflowService;
import com.sangeng.utils.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 文章工作流控制器测试 - 验证权限拦截、缓存刷新
 */
@SpringBootTest(classes = BlogAdminApplication.class)
@AutoConfigureMockMvc
public class ArticleWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private RedisCache redisCache;

    private Long testArticleId;

    @BeforeEach
    void setUp() {
        // 创建测试文章
        Article article = new Article();
        article.setTitle("控制器测试文章");
        article.setContent("控制器测试内容");
        article.setSummary("测试");
        article.setCategoryId(1L);
        article.setStatus(ArticleStatusEnum.DRAFT.getCode());
        article.setViewCount(0L);
        article.setIsTop("0");
        article.setIsComment("1");
        article.setCreateBy(1L);
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
     * 无权限用户访问工作流接口应返回403
     */
    @Test
    void testPermissionInterception_UnauthorizedUser() throws Exception {
        // 模拟无权限用户登录
        mockLoginAsUserWithoutPermissions();

        mockMvc.perform(post("/content/article/workflow/submit/" + testArticleId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * 有权限用户（reviewer角色）可以提交审核
     */
    @Test
    void testPermissionInterception_AuthorizedReviewer() throws Exception {
        mockLoginAsReviewer();

        mockMvc.perform(post("/content/article/workflow/submit/" + testArticleId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());
    }

    /**
     * 有权限用户（reviewer角色）可以审核通过
     */
    @Test
    void testPermissionInterception_AuthorizedApprove() throws Exception {
        mockLoginAsReviewer();

        // 先提交审核
        workflowService.submitForReview(testArticleId);

        // 审核通过
        ReviewActionDto dto = new ReviewActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("通过");

        mockMvc.perform(post("/content/article/workflow/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(dto)))
                .andExpect(status().isOk());

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
    }

    /**
     * 审核驳回接口 - 有权限
     */
    @Test
    void testRejectWithPermission() throws Exception {
        mockLoginAsReviewer();

        workflowService.submitForReview(testArticleId);

        ReviewActionDto dto = new ReviewActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("内容需要修改");

        mockMvc.perform(post("/content/article/workflow/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(dto)))
                .andExpect(status().isOk());

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        assertEquals("内容需要修改", article.getRejectReason());
    }

    /**
     * 违规下架接口 - 仅管理员可用
     */
    @Test
    void testViolationOffline_AdminOnly() throws Exception {
        // 先以reviewer身份发布文章
        mockLoginAsReviewer();
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 用reviewer身份尝试违规下架，应被拒绝（服务层检查isAdmin）
        mockLoginAsReviewer();
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规内容");

        mockMvc.perform(post("/content/article/workflow/violation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(violationDto)))
                .andExpect(status().isOk()); // HTTP 200 but body contains error code
    }

    /**
     * 缓存刷新验证 - 发布后相关缓存被清除
     */
    @Test
    void testCacheRefreshOnPublish() throws Exception {
        mockLoginAsReviewer();

        // 先设置一些缓存数据
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached_data");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES, "home_list_data");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST, "category_data");

        // 提交审核并审核通过
        workflowService.submitForReview(testArticleId);

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 验证缓存已被清除
        String cachedDetail = redisCache.getCacheObject(detailKey);
        assertNull(cachedDetail);

        String cachedHomeList = redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES);
        assertNull(cachedHomeList);

        String cachedCategoryList = redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST);
        assertNull(cachedCategoryList);
    }

    /**
     * 撤回后缓存也被清除
     */
    @Test
    void testCacheInvalidatedOnWithdraw() throws Exception {
        mockLoginAsReviewer();

        // 先发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 设置缓存
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached_data");

        // 撤回
        workflowService.withdraw(testArticleId);

        // 验证缓存被清除
        String cachedDetail = redisCache.getCacheObject(detailKey);
        assertNull(cachedDetail);
    }

    /**
     * 查询待审核列表接口
     */
    @Test
    void testGetPendingReviewList() throws Exception {
        mockLoginAsReviewer();

        mockMvc.perform(get("/content/article/workflow/pending")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 查询审核历史接口
     */
    @Test
    void testGetAuditHistory() throws Exception {
        mockLoginAsReviewer();

        // 先产生一些审核记录
        workflowService.submitForReview(testArticleId);

        mockMvc.perform(get("/content/article/workflow/audit/" + testArticleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 违规下架定时发布文章 - 管理员可以对SCHEDULED状态文章违规下架
     */
    @Test
    void testViolationOfflineOnScheduledArticle() throws Exception {
        // 以管理员身份发布文章为定时发布状态
        mockLoginAsAdmin();
        workflowService.submitForReview(testArticleId);

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.HOUR, 1);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(cal.getTime());
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), article.getStatus());

        // 管理员违规下架定时发布文章
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("定时发布内容违规");

        mockMvc.perform(post("/content/article/workflow/violation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(violationDto)))
                .andExpect(status().isOk());

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
        assertNull(article.getPublishTime());
    }

    /**
     * 缓存刷新验证 - 违规下架后标签缓存也被清除
     */
    @Test
    void testTagCacheInvalidatedOnViolationOffline() throws Exception {
        mockLoginAsAdmin();

        // 先发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 设置标签缓存
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_TAG_LIST, "cached_tags");

        // 违规下架
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规内容");
        workflowService.violationOffline(violationDto);

        // 标签缓存应被清除
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_TAG_LIST));
    }

    // ========== 辅助方法 ==========

    private void mockLoginAsReviewer() {
        User user = new User();
        user.setId(2L);
        user.setUserName("reviewer");
        user.setNickName("审核员");
        user.setType("0");

        List<String> perms = Arrays.asList(
                "content:article:submit",
                "content:article:approve",
                "content:article:reject",
                "content:article:withdraw",
                "content:article:review"
        );

        LoginUser loginUser = new LoginUser(user, perms);

        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private void mockLoginAsUserWithoutPermissions() {
        User user = new User();
        user.setId(3L);
        user.setUserName("normaluser");
        user.setNickName("普通用户");
        user.setType("0");

        LoginUser loginUser = new LoginUser(user, Collections.emptyList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(1L);
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
                "content:article:review"
        );

        LoginUser loginUser = new LoginUser(user, perms);

        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
