package com.sangeng;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.constants.SystemConstants;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ArticleTransitionDto;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.Comment;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.enums.AppHttpCodeEnum;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import com.sangeng.mapper.ArticleMapper;
import com.sangeng.service.ArticleOperationLogService;
import com.sangeng.service.ArticleService;
import com.sangeng.service.ArticleTagService;
import com.sangeng.service.CategoryService;
import com.sangeng.service.impl.ArticleServiceImpl;
import com.sangeng.service.impl.CommentServiceImpl;
import com.sangeng.service.impl.PermissionService;
import com.sangeng.utils.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("文章生命周期工作流测试")
public class ArticleWorkflowTest {

    @InjectMocks
    private ArticleServiceImpl articleService;

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private CategoryService categoryService;

    @Mock
    private RedisCache redisCache;

    @Mock
    private ArticleTagService articleTagService;

    @Mock
    private ArticleOperationLogService articleOperationLogService;

    @Mock
    private PermissionService permissionService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Article createArticle(Long id, String status, Long createBy) {
        Article article = new Article();
        article.setId(id);
        article.setStatus(status);
        article.setCreateBy(createBy);
        article.setContent("test content");
        article.setViewCount(100L);
        article.setIsComment(SystemConstants.COMMENT_ALLOWED);
        return article;
    }

    private void setCurrentUser(Long userId, List<String> permissions) {
        User user = new User();
        user.setId(userId);
        LoginUser loginUser = new LoginUser(user, permissions);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, null);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void setAdminUser() {
        setCurrentUser(1L, Collections.singletonList("admin"));
    }

    private void setNormalUser(Long userId) {
        setCurrentUser(userId, Collections.emptyList());
    }

    // ========== 审核流测试 ==========

    @Nested
    @DisplayName("审核流测试")
    class ReviewFlowTest {

        @Test
        @DisplayName("草稿→提交审核→通过发布：完整审核流程")
        void testSubmitAndApprovePublish() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_DRAFT_STR, 10L);

            // 作者提交审核
            setCurrentUser(10L, Collections.emptyList());
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto submitDto = new ArticleTransitionDto();
            submitDto.setArticleId(1L);
            submitDto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PENDING_REVIEW);

            ResponseResult result = articleService.transitionStatus(submitDto);
            assertEquals(200, result.getCode());

            // 模拟状态已变更
            article.setStatus(SystemConstants.ARTICLE_STATUS_PENDING_REVIEW);

            // 审核员通过发布
            setCurrentUser(2L, Collections.singletonList("content:article:approve"));
            when(permissionService.hasPermission("content:article:approve")).thenReturn(true);

            ArticleTransitionDto approveDto = new ArticleTransitionDto();
            approveDto.setArticleId(1L);
            approveDto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PUBLISHED);

            result = articleService.transitionStatus(approveDto);
            assertEquals(200, result.getCode());

            // 验证操作日志记录了2次
            verify(articleOperationLogService, times(2)).logTransition(
                    anyLong(), anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("草稿→提交审核→驳回回草稿")
        void testSubmitAndRejectBackToDraft() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PENDING_REVIEW, 10L);
            setCurrentUser(2L, Collections.singletonList("content:article:reject"));
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);
            when(permissionService.hasPermission("content:article:reject")).thenReturn(true);

            ArticleTransitionDto rejectDto = new ArticleTransitionDto();
            rejectDto.setArticleId(1L);
            rejectDto.setTargetStatus(SystemConstants.ARTICLE_STATUS_DRAFT_STR);
            rejectDto.setReason("内容不符合规范");

            ResponseResult result = articleService.transitionStatus(rejectDto);
            assertEquals(200, result.getCode());

            verify(articleOperationLogService).logTransition(
                    eq(1L), eq(2L),
                    eq(SystemConstants.ARTICLE_STATUS_PENDING_REVIEW),
                    eq(SystemConstants.ARTICLE_STATUS_DRAFT_STR),
                    eq("内容不符合规范"));
        }
    }

    // ========== 定时发布测试 ==========

    @Nested
    @DisplayName("定时发布测试")
    class ScheduledPublishTest {

        @Test
        @DisplayName("审核通过为定时发布：设置未来时间")
        void testApproveToScheduled() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PENDING_REVIEW, 10L);
            setCurrentUser(2L, Collections.singletonList("content:article:approve"));
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);
            when(permissionService.hasPermission("content:article:approve")).thenReturn(true);

            Date futureTime = new Date(System.currentTimeMillis() + 3600000);
            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_SCHEDULED);
            dto.setPublishTime(futureTime);

            ResponseResult result = articleService.transitionStatus(dto);
            assertEquals(200, result.getCode());
            assertEquals(futureTime, article.getPublishTime());
        }

        @Test
        @DisplayName("定时发布自动执行：到期文章自动发布并刷新缓存")
        void testScheduledAutoPublish() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_SCHEDULED, 10L);
            article.setPublishTime(new Date(System.currentTimeMillis() - 60000));

            when(articleMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.singletonList(article));
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            articleService.publishScheduledArticles();

            assertEquals(SystemConstants.ARTICLE_STATUS_PUBLISHED, article.getStatus());
            assertNull(article.getPublishTime());

            verify(redisCache).setCacheMapValue(
                    eq(SystemConstants.ARTICLE_VIEW_COUNT_KEY),
                    eq("1"), eq(100));

            verify(articleOperationLogService).logTransition(
                    eq(1L), eq(-1L),
                    eq(SystemConstants.ARTICLE_STATUS_SCHEDULED),
                    eq(SystemConstants.ARTICLE_STATUS_PUBLISHED),
                    eq("定时发布自动执行"));
        }
    }

    // ========== 违规下线测试 ==========

    @Nested
    @DisplayName("违规下线测试")
    class ViolationOfflineTest {

        @Test
        @DisplayName("已发布→违规下线：冻结评论并记录原因")
        void testViolationOffline() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            article.setIsComment(SystemConstants.COMMENT_ALLOWED);
            setAdminUser();

            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE);
            dto.setReason("涉及违规内容");

            ResponseResult result = articleService.transitionStatus(dto);
            assertEquals(200, result.getCode());
            assertEquals(SystemConstants.COMMENT_DISALLOWED, article.getIsComment());
            assertEquals("涉及违规内容", article.getViolationReason());
        }

        @Test
        @DisplayName("违规下线后Redis缓存被清除")
        void testViolationOfflineCacheCleared() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            setAdminUser();

            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE);
            dto.setReason("违规");

            articleService.transitionStatus(dto);

            verify(redisCache).delCacheMapValue(
                    eq(SystemConstants.ARTICLE_VIEW_COUNT_KEY), eq("1"));
        }
    }

    // ========== 评论冻结测试 ==========

    @Nested
    @DisplayName("评论冻结测试")
    class CommentFreezeTest {

        @InjectMocks
        private CommentServiceImpl commentService;

        @Mock
        private ArticleService mockArticleService;

        @Mock
        private com.sangeng.mapper.CommentMapper commentMapper;

        @Test
        @DisplayName("违规下线文章评论被拒绝")
        void testCommentBlockedOnViolationOffline() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE, 10L);
            when(mockArticleService.getById(1L)).thenReturn(article);

            Comment comment = new Comment();
            comment.setContent("test comment");
            comment.setType(SystemConstants.ARTICLE_COMMENT);
            comment.setArticleId(1L);

            SystemException exception = assertThrows(SystemException.class,
                    () -> commentService.addComment(comment));
            assertEquals(AppHttpCodeEnum.ARTICLE_COMMENT_DISABLED.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("评论关闭的文章评论被拒绝")
        void testCommentBlockedWhenDisabled() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            article.setIsComment(SystemConstants.COMMENT_DISALLOWED);
            when(mockArticleService.getById(1L)).thenReturn(article);

            Comment comment = new Comment();
            comment.setContent("test comment");
            comment.setType(SystemConstants.ARTICLE_COMMENT);
            comment.setArticleId(1L);

            SystemException exception = assertThrows(SystemException.class,
                    () -> commentService.addComment(comment));
            assertEquals(AppHttpCodeEnum.ARTICLE_COMMENT_DISABLED.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("友链评论不检查文章状态")
        void testLinkCommentNotAffected() {
            Comment comment = new Comment();
            comment.setContent("link comment");
            comment.setType(SystemConstants.LINK_COMMENT);
            comment.setArticleId(1L);

            // 友链评论不应查询文章状态
            try {
                commentService.addComment(comment);
            } catch (NullPointerException e) {
                // save() 因无真实 Mapper 抛 NPE 是预期的，关键是不应抛 ARTICLE_COMMENT_DISABLED
            }
            // 验证 articleService.getById 从未被调用（友链评论跳过文章检查）
            verify(mockArticleService, never()).getById(anyLong());
        }
    }

    // ========== 权限拦截测试 ==========

    @Nested
    @DisplayName("权限拦截测试")
    class PermissionTest {

        @Test
        @DisplayName("非审核员无法通过文章")
        void testNonReviewerCannotApprove() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PENDING_REVIEW, 10L);
            setNormalUser(20L);
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(permissionService.hasPermission("content:article:approve")).thenReturn(false);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PUBLISHED);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("管理员可执行任何合法转换")
        void testAdminCanDoAnyTransition() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            setAdminUser();
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE);
            dto.setReason("admin操作");

            ResponseResult result = articleService.transitionStatus(dto);
            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("非作者无法提交他人文章审核")
        void testNonOwnerCannotSubmitOthersArticle() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_DRAFT_STR, 10L);
            setNormalUser(20L);
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(permissionService.hasPermission("content:article:submitReview")).thenReturn(false);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PENDING_REVIEW);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.ARTICLE_NOT_OWNED.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("非管理员无法违规下线文章")
        void testNonAdminCannotViolationOffline() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            setNormalUser(20L);
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(permissionService.hasPermission("content:article:violationOffline")).thenReturn(false);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_VIOLATION_OFFLINE);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), exception.getCode());
        }
    }

    // ========== 缓存刷新测试 ==========

    @Nested
    @DisplayName("缓存刷新测试")
    class CacheRefreshTest {

        @Test
        @DisplayName("文章发布时Redis缓存被设置")
        void testRedisUpdatedOnPublish() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PENDING_REVIEW, 10L);
            article.setViewCount(50L);
            setAdminUser();
            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PUBLISHED);

            articleService.transitionStatus(dto);

            verify(redisCache).setCacheMapValue(
                    eq(SystemConstants.ARTICLE_VIEW_COUNT_KEY), eq("1"), eq(50));
        }

        @Test
        @DisplayName("文章撤回时Redis缓存被清除")
        void testRedisCleanedOnWithdraw() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            setCurrentUser(10L, Collections.emptyList());

            when(articleMapper.selectById(1L)).thenReturn(article);
            when(articleMapper.updateById(any(Article.class))).thenReturn(1);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_WITHDRAWN);

            articleService.transitionStatus(dto);

            verify(redisCache).delCacheMapValue(
                    eq(SystemConstants.ARTICLE_VIEW_COUNT_KEY), eq("1"));
        }
    }

    // ========== 无效转换测试 ==========

    @Nested
    @DisplayName("无效转换测试")
    class InvalidTransitionTest {

        @Test
        @DisplayName("草稿不能直接发布")
        void testDraftCannotDirectlyPublish() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_DRAFT_STR, 10L);
            setAdminUser();
            when(articleMapper.selectById(1L)).thenReturn(article);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PUBLISHED);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.INVALID_STATE_TRANSITION.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("已发布不能直接回到草稿")
        void testPublishedCannotDirectlyBackToDraft() {
            Article article = createArticle(1L, SystemConstants.ARTICLE_STATUS_PUBLISHED, 10L);
            setAdminUser();
            when(articleMapper.selectById(1L)).thenReturn(article);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(1L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_DRAFT_STR);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.INVALID_STATE_TRANSITION.getCode(), exception.getCode());
        }

        @Test
        @DisplayName("文章不存在时抛出异常")
        void testArticleNotFound() {
            setAdminUser();
            when(articleMapper.selectById(999L)).thenReturn(null);

            ArticleTransitionDto dto = new ArticleTransitionDto();
            dto.setArticleId(999L);
            dto.setTargetStatus(SystemConstants.ARTICLE_STATUS_PENDING_REVIEW);

            SystemException exception = assertThrows(SystemException.class,
                    () -> articleService.transitionStatus(dto));
            assertEquals(AppHttpCodeEnum.ARTICLE_NOT_FOUND.getCode(), exception.getCode());
        }
    }

    // ========== 状态枚举测试 ==========

    @Nested
    @DisplayName("状态枚举测试")
    class StatusEnumTest {

        @Test
        @DisplayName("验证所有合法转换")
        void testAllValidTransitions() {
            assertTrue(ArticleStatusEnum.isValidTransition("1", "2"));
            assertTrue(ArticleStatusEnum.isValidTransition("2", "0"));
            assertTrue(ArticleStatusEnum.isValidTransition("2", "3"));
            assertTrue(ArticleStatusEnum.isValidTransition("2", "1"));
            assertTrue(ArticleStatusEnum.isValidTransition("3", "0"));
            assertTrue(ArticleStatusEnum.isValidTransition("0", "4"));
            assertTrue(ArticleStatusEnum.isValidTransition("0", "5"));
            assertTrue(ArticleStatusEnum.isValidTransition("4", "2"));
            assertTrue(ArticleStatusEnum.isValidTransition("5", "2"));
        }

        @Test
        @DisplayName("验证所有非法转换")
        void testAllInvalidTransitions() {
            assertFalse(ArticleStatusEnum.isValidTransition("1", "0"));
            assertFalse(ArticleStatusEnum.isValidTransition("0", "1"));
            assertFalse(ArticleStatusEnum.isValidTransition("3", "1"));
            assertFalse(ArticleStatusEnum.isValidTransition("4", "0"));
        }

        @Test
        @DisplayName("fromCode 正确解析")
        void testFromCode() {
            assertEquals(ArticleStatusEnum.DRAFT, ArticleStatusEnum.fromCode("1"));
            assertEquals(ArticleStatusEnum.PUBLISHED, ArticleStatusEnum.fromCode("0"));
            assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE, ArticleStatusEnum.fromCode("5"));
            assertThrows(IllegalArgumentException.class, () -> ArticleStatusEnum.fromCode("9"));
        }
    }
}
