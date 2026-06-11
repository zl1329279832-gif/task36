package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.SystemConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.Comment;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.enums.AppHttpCodeEnum;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评论冻结测试 - 验证违规下架文章的评论入口被冻结，已发布文章评论正常
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class CommentServiceTest {

    @Autowired
    private CommentService commentService;

    @Autowired
    private ArticleService articleService;

    private Long publishedArticleId;
    private Long violationArticleId;

    @BeforeEach
    void setUp() {
        mockLoginAsUser();

        // 创建一篇已发布的文章
        Article publishedArticle = new Article();
        publishedArticle.setTitle("已发布文章");
        publishedArticle.setContent("已发布内容");
        publishedArticle.setSummary("已发布");
        publishedArticle.setCategoryId(1L);
        publishedArticle.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        publishedArticle.setViewCount(0L);
        publishedArticle.setIsTop("0");
        publishedArticle.setIsComment("1");
        articleService.save(publishedArticle);
        publishedArticleId = publishedArticle.getId();

        // 创建一篇违规下架的文章
        Article violationArticle = new Article();
        violationArticle.setTitle("违规文章");
        violationArticle.setContent("违规内容");
        violationArticle.setSummary("违规");
        violationArticle.setCategoryId(1L);
        violationArticle.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        violationArticle.setViewCount(0L);
        violationArticle.setIsTop("0");
        violationArticle.setIsComment("1");
        violationArticle.setViolationReason("违规原因");
        articleService.save(violationArticle);
        violationArticleId = violationArticle.getId();
    }

    @AfterEach
    void tearDown() {
        if (publishedArticleId != null) {
            articleService.removeById(publishedArticleId);
        }
        if (violationArticleId != null) {
            articleService.removeById(violationArticleId);
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * 已发布文章可以正常评论
     */
    @Test
    void testCommentAllowedOnPublishedArticle() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(publishedArticleId);
        comment.setRootId(-1L);
        comment.setContent("这是一条正常评论");
        comment.setCreateBy(1L);

        // 不应抛出异常
        assertDoesNotThrow(() -> commentService.addComment(comment));
    }

    /**
     * 违规下架文章评论被冻结 - 新增评论应抛出 COMMENT_FROZEN 异常
     */
    @Test
    void testCommentFrozenOnViolationArticle() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(violationArticleId);
        comment.setRootId(-1L);
        comment.setContent("尝试在违规文章下评论");
        comment.setCreateBy(1L);

        SystemException exception = assertThrows(SystemException.class, () -> {
            commentService.addComment(comment);
        });

        assertEquals(AppHttpCodeEnum.COMMENT_FROZEN.getCode(), exception.getCode());
    }

    /**
     * 友链评论不受文章状态影响（type=1，不关联文章）
     */
    @Test
    void testLinkCommentNotAffected() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.LINK_COMMENT);
        comment.setRootId(-1L);
        comment.setContent("友链评论");
        comment.setCreateBy(1L);

        // 友链评论不应受文章状态影响
        assertDoesNotThrow(() -> commentService.addComment(comment));
    }

    /**
     * 评论内容为空应抛出异常（原有逻辑不受影响）
     */
    @Test
    void testEmptyCommentRejected() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(publishedArticleId);
        comment.setRootId(-1L);
        comment.setContent("");
        comment.setCreateBy(1L);

        SystemException exception = assertThrows(SystemException.class, () -> {
            commentService.addComment(comment);
        });

        assertEquals(AppHttpCodeEnum.CONTENT_NOT_NULL.getCode(), exception.getCode());
    }

    /**
     * 历史评论在违规下架后仍可查询（保留历史评论）
     */
    @Test
    void testHistoricalCommentsPreservedOnViolation() {
        // 先在已发布文章上添加评论
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(publishedArticleId);
        comment.setRootId(-1L);
        comment.setContent("这是一条历史评论");
        comment.setCreateBy(1L);
        commentService.addComment(comment);

        // 将文章改为违规下架状态
        Article article = articleService.getById(publishedArticleId);
        article.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        article.setViolationReason("测试违规");
        articleService.updateById(article);

        // 历史评论列表应仍可查询
        ResponseResult result = commentService.commentList(
                SystemConstants.ARTICLE_COMMENT, publishedArticleId, 1, 10);
        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    /**
     * 文章重新发布后评论功能恢复
     */
    @Test
    void testCommentRestoredAfterRePublish() {
        // 将违规文章重新发布
        Article article = articleService.getById(violationArticleId);
        article.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        article.setViolationReason(null);
        articleService.updateById(article);

        // 评论功能应恢复正常
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(violationArticleId);
        comment.setRootId(-1L);
        comment.setContent("重新发布后的评论");
        comment.setCreateBy(1L);

        assertDoesNotThrow(() -> commentService.addComment(comment));
    }

    // ========== 辅助方法 ==========

    private void mockLoginAsUser() {
        User user = new User();
        user.setId(1L);
        user.setUserName("testuser");
        user.setNickName("测试用户");
        user.setType("0");

        LoginUser loginUser = new LoginUser(user, Collections.emptyList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
