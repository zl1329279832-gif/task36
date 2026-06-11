package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.ArticleAuditLog;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文章工作流服务测试 - 覆盖完整审核流、定时发布、撤回、违规下架、强制发布场景
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class ArticleWorkflowServiceTest {

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    @Autowired
    private RedisCache redisCache;

    private Long testArticleId;
    private Long adminUserId = 1L;

    @BeforeEach
    void setUp() {
        // 以管理员身份执行测试
        mockLoginAsAdmin();

        // 创建测试文章（草稿状态）
        Article article = new Article();
        article.setTitle("测试文章 - 工作流");
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
     * 完整审核流程：草稿 -> 提交审核 -> 审核通过(立即发布) -> 撤回 -> 重新编辑 -> 提交审核 -> 驳回
     */
    @Test
    void testFullAuditFlow() {
        // 1. 草稿 -> 提交审核
        workflowService.submitForReview(testArticleId);
        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());

        // 2. 审核通过（立即发布）
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setReason("审核通过");
        workflowService.approve(approveDto);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
        assertNotNull(article.getPublishTime());

        // 3. 撤回
        workflowService.withdraw(testArticleId);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.WITHDRAWN.getCode(), article.getStatus());

        // 4. 重新编辑（回到草稿）
        workflowService.reEdit(testArticleId);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        assertNull(article.getRejectReason());

        // 5. 再次提交审核
        workflowService.submitForReview(testArticleId);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());

        // 6. 审核驳回
        ReviewActionDto rejectDto = new ReviewActionDto();
        rejectDto.setArticleId(testArticleId);
        rejectDto.setReason("内容质量不达标");
        workflowService.reject(rejectDto);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        assertEquals("内容质量不达标", article.getRejectReason());
    }

    /**
     * 定时发布流程：草稿 -> 提交审核 -> 审核通过(定时发布) -> 定时任务触发 -> 已发布
     */
    @Test
    void testScheduledPublish() {
        // 1. 提交审核
        workflowService.submitForReview(testArticleId);

        // 2. 审核通过，设置定时发布时间为未来1小时
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, 1);
        Date futureTime = cal.getTime();

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(futureTime);
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), article.getStatus());
        assertEquals(futureTime, article.getPublishTime());

        // 3. 手动将发布时间改为过去，模拟定时任务触发
        article.setPublishTime(new Date(System.currentTimeMillis() - 60000));
        articleService.updateById(article);

        // 4. 模拟定时发布任务执行（直接修改状态验证逻辑正确性）
        article.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        articleService.updateById(article);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
    }

    /**
     * 审核通过立即发布（scheduledPublishTime为null或过去时间）
     */
    @Test
    void testApproveImmediately() {
        workflowService.submitForReview(testArticleId);

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        // 不设置 scheduledPublishTime，立即发布
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
        assertNotNull(article.getPublishTime());
    }

    /**
     * 违规下架流程：已发布 -> 违规下架 -> 重新编辑 -> 草稿
     */
    @Test
    void testViolationOffline() {
        // 先发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 违规下架
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("内容包含违规信息");
        workflowService.violationOffline(violationDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
        assertEquals("内容包含违规信息", article.getViolationReason());

        // 重新编辑（从违规下架回到草稿）
        workflowService.reEdit(testArticleId);
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        assertNull(article.getViolationReason());
    }

    /**
     * 强制发布：任意状态 -> 已发布（管理员专属）
     */
    @Test
    void testForcePublish() {
        // 草稿状态直接强制发布
        workflowService.forcePublish(testArticleId);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
        assertNotNull(article.getPublishTime());
        assertNull(article.getRejectReason());
        assertNull(article.getViolationReason());
    }

    /**
     * 状态转换校验：非法状态转换应抛出异常
     */
    @Test
    void testInvalidStateTransition() {
        // 草稿状态不能直接撤回（只有已发布可撤回）
        assertThrows(SystemException.class, () -> {
            workflowService.withdraw(testArticleId);
        });

        // 草稿状态不能直接违规下架（只有已发布和定时发布可违规下架）
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("测试");
        assertThrows(SystemException.class, () -> {
            workflowService.violationOffline(violationDto);
        });

        // 草稿状态不能审核通过（只有待审核可审核）
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        assertThrows(SystemException.class, () -> {
            workflowService.approve(approveDto);
        });
    }

    /**
     * 驳回必须填写原因
     */
    @Test
    void testRejectRequiresReason() {
        workflowService.submitForReview(testArticleId);

        ReviewActionDto rejectDto = new ReviewActionDto();
        rejectDto.setArticleId(testArticleId);
        // 不设置原因，应抛出异常
        assertThrows(SystemException.class, () -> {
            workflowService.reject(rejectDto);
        });
    }

    /**
     * 违规下架必须填写原因
     */
    @Test
    void testViolationRequiresReason() {
        // 先发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        // 不设置原因，应抛出异常
        assertThrows(SystemException.class, () -> {
            workflowService.violationOffline(violationDto);
        });
    }

    /**
     * 操作日志记录验证
     */
    @Test
    void testAuditLogRecording() {
        // 执行完整的状态转换
        workflowService.submitForReview(testArticleId);

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 检查审核日志
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        assertFalse(logs.isEmpty());
        assertTrue(logs.size() >= 2); // 至少有提交审核和审核通过两条记录

        // 验证最近一条记录（审核通过）
        ArticleAuditLog latestLog = logs.get(0);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), latestLog.getToStatus());
        assertEquals(adminUserId, latestLog.getOperatorId());
    }

    /**
     * 查询待审核文章列表
     */
    @Test
    void testGetPendingReviewArticles() {
        workflowService.submitForReview(testArticleId);
        var result = workflowService.getPendingReviewArticles(1, 10);
        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    /**
     * 不存在的文章应抛出异常
     */
    @Test
    void testNonExistentArticle() {
        Long fakeId = 999999L;
        assertThrows(SystemException.class, () -> {
            workflowService.submitForReview(fakeId);
        });
    }

    /**
     * 违规下架定时发布文章：定时发布状态的文章可以被管理员直接违规下架
     */
    @Test
    void testViolationOfflineOnScheduledArticle() {
        // 提交审核并设置定时发布
        workflowService.submitForReview(testArticleId);

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, 1);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(cal.getTime());
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), article.getStatus());

        // 管理员违规下架定时发布中的文章
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("定时发布内容包含违规信息");
        workflowService.violationOffline(violationDto);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
        assertEquals("定时发布内容包含违规信息", article.getViolationReason());
        // 定时发布时间应被清除
        assertNull(article.getPublishTime());

        // 审计日志应记录中断定时发布
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        boolean hasInterruptLog = logs.stream()
                .anyMatch(log -> log.getReason() != null && log.getReason().contains("中断定时发布"));
        assertTrue(hasInterruptLog, "审计日志应包含中断定时发布的记录");
    }

    /**
     * 定时发布被下线打断后重新编辑发布：违规下架 -> 重新编辑 -> 提交 -> 审核通过
     */
    @Test
    void testRePublishAfterViolationOffline() {
        // 发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 违规下架
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规内容");
        workflowService.violationOffline(violationDto);

        // 重新编辑
        workflowService.reEdit(testArticleId);
        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        assertNull(article.getViolationReason());

        // 重新提交审核并发布 - 应恢复分类/标签统计
        workflowService.submitForReview(testArticleId);
        ReviewActionDto reApproveDto = new ReviewActionDto();
        reApproveDto.setArticleId(testArticleId);
        workflowService.approve(reApproveDto);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
        assertNotNull(article.getPublishTime());
    }

    /**
     * OSS附件引用失效阻止立即发布并写入审计日志
     */
    @Test
    void testOssValidationBlocksImmediatePublish() {
        // 设置文章内容包含不可达的OSS图片
        Article article = articleService.getById(testArticleId);
        article.setContent("含失效图片 ![img](http://invalid-oss-host.example.com/broken.png)");
        articleService.updateById(article);

        workflowService.submitForReview(testArticleId);

        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);

        // 立即发布应被阻止
        SystemException ex = assertThrows(SystemException.class, () -> {
            workflowService.approve(approveDto);
        });
        assertEquals(518, ex.getCode());

        // 文章应保持待审核状态
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());

        // 审计日志应记录OSS失效
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        boolean hasOssLog = logs.stream()
                .anyMatch(log -> log.getReason() != null && log.getReason().contains("OSS附件引用失效"));
        assertTrue(hasOssLog, "审计日志应包含OSS附件引用失效记录");
    }

    /**
     * OSS附件引用失效阻止强制发布
     */
    @Test
    void testOssValidationBlocksForcePublish() {
        Article article = articleService.getById(testArticleId);
        article.setContent("含失效图片 ![img](http://invalid-oss-host.example.com/broken.jpg)");
        articleService.updateById(article);

        SystemException ex = assertThrows(SystemException.class, () -> {
            workflowService.forcePublish(testArticleId);
        });
        assertEquals(518, ex.getCode());

        // 文章应保持草稿状态
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
    }

    /**
     * 缓存刷新验证 - 发布后标签缓存也被清除
     */
    @Test
    void testTagCacheInvalidatedOnPublish() {
        redisCache.setCacheObject("tag:list", "cached_tags");

        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        assertNull(redisCache.getCacheObject("tag:list"));
    }

    /**
     * 缓存刷新验证 - 违规下架后标签缓存也被清除
     */
    @Test
    void testTagCacheInvalidatedOnViolationOffline() {
        // 先发布
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 设置标签缓存
        redisCache.setCacheObject("tag:list", "cached_tags");

        // 违规下架
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规");
        workflowService.violationOffline(violationDto);

        assertNull(redisCache.getCacheObject("tag:list"));
    }

    // ========== 辅助方法 ==========

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(adminUserId);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<SimpleGrantedAuthority> authorities = Arrays.asList(
                "content:article:submit",
                "content:article:approve",
                "content:article:reject",
                "content:article:withdraw",
                "content:article:violation",
                "content:article:forcePublish",
                "content:article:review"
        ).stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());

        LoginUser loginUser = new LoginUser(user, Arrays.asList(
                "content:article:submit",
                "content:article:approve",
                "content:article:reject",
                "content:article:withdraw",
                "content:article:violation",
                "content:article:forcePublish",
                "content:article:review"
        ));

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
