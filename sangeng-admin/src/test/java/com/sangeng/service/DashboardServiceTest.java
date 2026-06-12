package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.Comment;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.domain.vo.DashboardStatsVo;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.utils.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运营看板统计服务测试
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RedisCache redisCache;

    private Long adminUserId = 1L;
    private List<Long> cleanupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();
    }

    @AfterEach
    void tearDown() {
        for (Long id : cleanupIds) {
            articleService.removeById(id);
        }
        cleanupIds.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDashboardStatusCounts() {
        Article draft = createArticle("draft_count_test", ArticleStatusEnum.DRAFT.getCode());
        Article pub = createArticle("pub_count_test", ArticleStatusEnum.PUBLISHED.getCode());

        ResponseResult result = dashboardService.getDashboardStats();
        assertEquals(200, result.getCode());

        DashboardStatsVo stats = (DashboardStatsVo) result.getData();
        assertNotNull(stats.getStatusCounts());
        assertTrue(stats.getStatusCounts().containsKey(ArticleStatusEnum.DRAFT.getCode()));
        assertTrue(stats.getStatusCounts().get(ArticleStatusEnum.DRAFT.getCode()) >= 1);
    }

    @Test
    void testDashboardFrozenCommentCount() {
        Article article = createArticle("frozen_comment_test", ArticleStatusEnum.PUBLISHED.getCode());

        Comment c1 = new Comment();
        c1.setType("0");
        c1.setArticleId(article.getId());
        c1.setRootId(-1L);
        c1.setContent("评论1");
        c1.setCreateBy(1L);
        commentService.addComment(c1);

        Comment c2 = new Comment();
        c2.setType("0");
        c2.setArticleId(article.getId());
        c2.setRootId(-1L);
        c2.setContent("评论2");
        c2.setCreateBy(1L);
        commentService.addComment(c2);

        article.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        articleService.updateById(article);

        ResponseResult result = dashboardService.getDashboardStats();
        DashboardStatsVo stats = (DashboardStatsVo) result.getData();
        assertTrue(stats.getFrozenCommentCount() >= 2, "冻结评论数应至少为2");
    }

    @Test
    void testDashboardCacheCounters() {
        ResponseResult result = dashboardService.getDashboardStats();
        DashboardStatsVo stats = (DashboardStatsVo) result.getData();

        assertNotNull(stats.getCacheRefreshCount());
        assertNotNull(stats.getCacheInvalidateCount());
        assertTrue(stats.getCacheRefreshCount() >= 0);
        assertTrue(stats.getCacheInvalidateCount() >= 0);
    }

    @Test
    void testDashboardReviewDuration() {
        Article article = createArticle("review_duration_test", ArticleStatusEnum.DRAFT.getCode());
        workflowService.submitForReview(article.getId());

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(article.getId());
        workflowService.approve(approveDto);

        ResponseResult result = dashboardService.getDashboardStats();
        DashboardStatsVo stats = (DashboardStatsVo) result.getData();

        assertTrue(stats.getTotalReviewsCompleted() >= 1, "应有至少1次完成的审核");
    }

    @Test
    void testDashboardEmptyState() {
        ResponseResult result = dashboardService.getDashboardStats();
        assertEquals(200, result.getCode());

        DashboardStatsVo stats = (DashboardStatsVo) result.getData();
        assertNotNull(stats);
        assertNotNull(stats.getStatusCounts());
    }

    // ========== 辅助方法 ==========

    private Article createArticle(String title, String status) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("测试内容");
        article.setSummary("测试");
        article.setCategoryId(1L);
        article.setStatus(status);
        article.setViewCount(0L);
        article.setIsTop("0");
        article.setIsComment("1");
        article.setCreateBy(adminUserId);
        articleService.save(article);
        cleanupIds.add(article.getId());
        return article;
    }

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(adminUserId);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<String> perms = Arrays.asList(
                "content:article:submit", "content:article:approve",
                "content:article:review", "content:dashboard:view"
        );

        LoginUser loginUser = new LoginUser(user, perms);
        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
