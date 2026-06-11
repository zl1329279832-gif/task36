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
 * 评论冻结测试 - 验证违规下架/撤回文章的评论入口被冻结，已发布文章评论正常，
 * 历史评论保留可查
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class CommentServiceTest {

    @Autowired
    private CommentService commentService;

    @Autowired
    private ArticleService articleService;

    private Long publishedArticleId;
    private Long violationArticleId;
    private Long withdrawnArticleId;
    private Long draftArticleId;

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

        // 创建一篇已撤回的文章
        Article withdrawnArticle = new Article();
        withdrawnArticle.setTitle("撤回文章");
        withdrawnArticle.setContent("撤回内容");
        withdrawnArticle.setSummary("撤回");
        withdrawnArticle.setCategoryId(1L);
        withdrawnArticle.setStatus(ArticleStatusEnum.WITHDRAWN.getCode());
        withdrawnArticle.setViewCount(0L);
        withdrawnArticle.setIsTop("0");
        withdrawnArticle.setIsComment("1");
        articleService.save(withdrawnArticle);
        withdrawnArticleId = withdrawnArticle.getId();

        // 创建一篇草稿文章
        Article draftArticle = new Article();
        draftArticle.setTitle("草稿文章");
        draftArticle.setContent("草稿内容");
        draftArticle.setSummary("草稿");
        draftArticle.setCategoryId(1L);
        draftArticle.setStatus(ArticleStatusEnum.DRAFT.getCode());
        draftArticle.setViewCount(0L);
        draftArticle.setIsTop("0");
        draftArticle.setIsComment("1");
        articleService.save(draftArticle);
        draftArticleId = draftArticle.getId();
    }

    @AfterEach
    void tearDown() {
        if (publishedArticleId != null) {
            articleService.removeById(publishedArticleId);
        }
        if (violationArticleId != null) {
            articleService.removeById(violationArticleId);
        }
        if (withdrawnArticleId != null) {
            articleService.removeById(withdrawnArticleId);
        }
        if (draftArticleId != null) {
            articleService.removeById(draftArticleId);
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
     * 已撤回文章评论也被冻结 - 新增评论应抛出 COMMENT_FROZEN 异常
     */
    @Test
    void testCommentFrozenOnWithdrawnArticle() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(withdrawnArticleId);
        comment.setRootId(-1L);
        comment.setContent("尝试在撤回文章下评论");
        comment.setCreateBy(1L);

        SystemException exception = assertThrows(SystemException.class, () -> {
            commentService.addComment(comment);
        });

        assertEquals(AppHttpCodeEnum.COMMENT_FROZEN.getCode(), exception.getCode());
    }

    /**
     * 草稿文章不允许评论
     */
    @Test
    void testCommentBlockedOnDraftArticle() {
        Comment comment = new Comment();
        comment.setType(SystemConstants.ARTICLE_COMMENT);
        comment.setArticleId(draftArticleId);
        comment.setRootId(-1L);
        comment.setContent("尝试在草稿文章下评论");
        comment.setCreateBy(1L);

        SystemException exception = assertThrows(SystemException.class, () -> {
            commentService.addComment(comment);
        });

        assertEquals(AppHttpCodeEnum.ARTICLE_STATUS_INVALID.getCode(), exception.getCode());
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
     * 违规下架文章的历史评论仍然可以查询（保留历史评论）
     * 先在已发布时添加评论，再模拟下线，评论列表应仍能返回
     */
    @Test
    void testHistoricalCommentsPreservedOnViolationArticle() {
        // 先在已发布文章下添加一条历史评论
        Comment historicalComment = new Comment();
        historicalComment.setType(SystemConstants.ARTICLE_COMMENT);
        historicalComment.setArticleId(publishedArticleId);
        historicalComment.setRootId(-1L);
        historicalComment.setContent("这是一条历史评论");
        historicalComment.setCreateBy(1L);
        commentService.addComment(historicalComment);

        // 模拟将文章改为违规下线状态（直接修改DB状态，模拟管理员操作）
        Article article = articleService.getById(publishedArticleId);
        article.setStatus(ArticleStatusEnum.VIOLATION_OFFLINE.getCode());
        article.setViolationReason("测试历史评论保留");
        articleService.updateById(article);

        // 评论列表仍应返回历史评论（不抛异常）
        final Long articleId = publishedArticleId;
        ResponseResult result = assertDoesNotThrow(() ->
                commentService.commentList(SystemConstants.ARTICLE_COMMENT, articleId, 1, 10)
        );
        assertNotNull(result);
        assertEquals(200, result.getCode());

        // 但新增评论应被冻结
        Comment newComment = new Comment();
        newComment.setType(SystemConstants.ARTICLE_COMMENT);
        newComment.setArticleId(publishedArticleId);
        newComment.setRootId(-1L);
        newComment.setContent("尝试在已下线文章下新增评论");
        newComment.setCreateBy(1L);

        assertThrows(SystemException.class, () -> commentService.addComment(newComment));
    }

    /**
     * isComment="0" 的文章不允许评论
     */
    @Test
    void testCommentBlockedWhenIsCommentDisabled() {
        // 创建一个isComment=0的已发布文章
        Article noCommentArticle = new Article();
        noCommentArticle.setTitle("禁止评论文章");
        noCommentArticle.setContent("禁止评论内容");
        noCommentArticle.setSummary("禁止评论");
        noCommentArticle.setCategoryId(1L);
        noCommentArticle.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        noCommentArticle.setViewCount(0L);
        noCommentArticle.setIsTop("0");
        noCommentArticle.setIsComment("0");  // 禁止评论
        articleService.save(noCommentArticle);

        try {
            Comment comment = new Comment();
            comment.setType(SystemConstants.ARTICLE_COMMENT);
            comment.setArticleId(noCommentArticle.getId());
            comment.setRootId(-1L);
            comment.setContent("尝试在禁止评论的文章下评论");
            comment.setCreateBy(1L);

            SystemException exception = assertThrows(SystemException.class, () -> {
                commentService.addComment(comment);
            });

            assertEquals(AppHttpCodeEnum.COMMENT_FROZEN.getCode(), exception.getCode());
        } finally {
            articleService.removeById(noCommentArticle.getId());
        }
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
