package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ArchiveActionDto;
import com.sangeng.domain.dto.RepublishActionDto;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.Comment;
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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归档流程测试
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class ArchiveFlowTest {

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private RedisCache redisCache;

    private Long testArticleId;
    private Long adminUserId = 1L;

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();

        Article article = new Article();
        article.setTitle("归档测试文章");
        article.setContent("归档测试内容");
        article.setSummary("归档测试");
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

    private void publishArticle() {
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);
    }

    @Test
    void testArchiveFromPublished() {
        publishArticle();

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("内容过时");
        workflowService.archive(dto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.ARCHIVED.getCode(), article.getStatus());
        assertNotNull(article.getArchivedTime());
    }

    @Test
    void testArchiveFromWithdrawn() {
        publishArticle();
        workflowService.withdraw(testArticleId);

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("撤回后归档");
        workflowService.archive(dto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.ARCHIVED.getCode(), article.getStatus());
    }

    @Test
    void testArchiveFromGray() {
        workflowService.submitForReview(testArticleId);
        ReviewActionDto grayDto = new ReviewActionDto();
        grayDto.setArticleId(testArticleId);
        grayDto.setGrayVisible(true);
        grayDto.setGrayAudience("1");
        workflowService.approve(grayDto);

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("灰度后归档");
        workflowService.archive(dto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.ARCHIVED.getCode(), article.getStatus());
    }

    @Test
    void testArchiveRequiresReason() {
        publishArticle();

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);

        assertThrows(SystemException.class, () -> {
            workflowService.archive(dto);
        });
    }

    @Test
    void testArchiveRequiresAdmin() {
        publishArticle();
        mockLoginAsNonAdmin();

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("非管理员尝试归档");

        assertThrows(SystemException.class, () -> {
            workflowService.archive(dto);
        });
    }

    @Test
    void testArchiveInvalidStatus() {
        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("草稿归档");

        assertThrows(SystemException.class, () -> {
            workflowService.archive(dto);
        });
    }

    @Test
    void testArchiveInvalidatesCache() {
        publishArticle();

        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached");
        redisCache.setCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                testArticleId.toString(), 100);

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("归档测试缓存");
        workflowService.archive(dto);

        assertNull(redisCache.getCacheObject(detailKey));
        assertNull(redisCache.getCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                testArticleId.toString()));
    }

    @Test
    void testArchivedArticleCommentFrozen() {
        publishArticle();

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("归档");
        workflowService.archive(dto);

        Comment comment = new Comment();
        comment.setType("0");
        comment.setArticleId(testArticleId);
        comment.setRootId(-1L);
        comment.setContent("归档后评论");
        comment.setCreateBy(1L);

        SystemException ex = assertThrows(SystemException.class, () -> {
            commentService.addComment(comment);
        });
        assertEquals(AppHttpCodeEnum.COMMENT_FROZEN.getCode(), ex.getCode());
    }

    @Test
    void testArchivedArticleHistoricalCommentsReadable() {
        publishArticle();

        Comment comment = new Comment();
        comment.setType("0");
        comment.setArticleId(testArticleId);
        comment.setRootId(-1L);
        comment.setContent("历史评论");
        comment.setCreateBy(1L);
        commentService.addComment(comment);

        ArchiveActionDto dto = new ArchiveActionDto();
        dto.setArticleId(testArticleId);
        dto.setArchiveReason("归档");
        workflowService.archive(dto);

        ResponseResult result = commentService.commentList("0", testArticleId, 1, 10);
        assertEquals(200, result.getCode());
    }

    // ========== 辅助方法 ==========

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(adminUserId);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<String> perms = Arrays.asList(
                "content:article:submit", "content:article:approve",
                "content:article:withdraw", "content:article:archive",
                "content:article:grayPublish", "content:article:fullPublish",
                "content:article:republish", "content:article:review"
        );

        LoginUser loginUser = new LoginUser(user, perms);
        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
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

        List<String> perms = Arrays.asList("content:article:submit");
        LoginUser loginUser = new LoginUser(user, perms);
        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
