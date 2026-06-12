package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.vo.DashboardVo;
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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运维仪表盘测试
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class DashboardServiceTest {

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private RedisCache redisCache;

    private Long testArticleId;

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();

        Article article = new Article();
        article.setTitle("仪表盘测试文章");
        article.setContent("测试内容");
        article.setSummary("测试摘要");
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
     * 仪表盘返回所有状态的文章数量（包含新增的灰度和归档状态）
     */
    @Test
    void testDashboardReturnsStatusCounts() {
        ResponseResult result = workflowService.getDashboardStats();
        assertNotNull(result);
        assertEquals(200, result.getCode());

        DashboardVo dashboard = (DashboardVo) result.getData();
        assertNotNull(dashboard.getStatusCounts());
        // 应包含8种状态
        assertEquals(8, dashboard.getStatusCounts().size());
    }

    /**
     * 仪表盘返回冻结评论数
     */
    @Test
    void testDashboardFrozenCommentCount() {
        ResponseResult result = workflowService.getDashboardStats();
        DashboardVo dashboard = (DashboardVo) result.getData();
        assertNotNull(dashboard.getFrozenCommentCount());
    }

    /**
     * 仪表盘缓存刷新计数在状态转换后递增
     */
    @Test
    void testDashboardCacheRefreshCount() {
        redisCache.deleteObject(ArticleWorkflowConstants.CACHE_REFRESH_COUNT_KEY);

        // 执行一次状态转换触发缓存刷新
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ResponseResult result = workflowService.getDashboardStats();
        DashboardVo dashboard = (DashboardVo) result.getData();
        assertTrue(dashboard.getCacheRefreshCount() > 0, "缓存刷新计数应大于0");
    }

    /**
     * 仪表盘平均审核耗时不为null
     */
    @Test
    void testDashboardAverageReviewTime() {
        // 执行一个审核周期
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ResponseResult result = workflowService.getDashboardStats();
        DashboardVo dashboard = (DashboardVo) result.getData();
        assertNotNull(dashboard.getAverageReviewTimeMs());
    }

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(1L);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<String> perms = Arrays.asList(
                "content:article:submit", "content:article:approve",
                "content:article:reject", "content:article:withdraw",
                "content:article:violation", "content:article:forcePublish",
                "content:article:review", "content:article:grayscale",
                "content:article:archive", "content:article:republish",
                "content:article:dashboard"
        );

        LoginUser loginUser = new LoginUser(user, perms);
        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
