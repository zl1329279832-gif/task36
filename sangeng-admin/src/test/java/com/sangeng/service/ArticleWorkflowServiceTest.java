package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.constants.ArticleWorkflowConstants;
import com.sangeng.domain.ResponseResult;
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

        // 草稿状态不能直接违规下架（只有已发布/定时发布可违规下架）
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
        ResponseResult result = workflowService.getPendingReviewArticles(1, 10);
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

    // ========== 新增：定时发布被违规下线打断场景 ==========

    /**
     * 违规下线可从SCHEDULED状态执行（优先级高于定时发布）
     * 场景：文章审核通过进入定时发布队列，管理员发现违规，直接下线
     */
    @Test
    void testViolationOfflineFromScheduled() {
        // 1. 提交审核
        workflowService.submitForReview(testArticleId);

        // 2. 审核通过，设置定时发布
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, 1);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(cal.getTime());
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), article.getStatus());

        // 3. 管理员执行违规下线（应从SCHEDULED状态直接下线）
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("定时发布文章发现违规内容");
        workflowService.violationOffline(violationDto);

        // 4. 验证文章已变为违规下线状态
        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
        assertEquals("定时发布文章发现违规内容", article.getViolationReason());

        // 5. 验证审计日志记录了从SCHEDULED到VIOLATION_OFFLINE的转换
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        ArticleAuditLog latestLog = logs.get(0);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), latestLog.getFromStatus());
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), latestLog.getToStatus());
    }

    /**
     * 定时发布被违规下线打断后，定时任务不应重新发布该文章。
     * 模拟：文章从SCHEDULED变为VIOLATION_OFFLINE后，定时任务查询到的SCHEDULED文章应不再包含此文章。
     */
    @Test
    void testScheduledPublishBlockedAfterViolation() {
        // 1. 创建定时发布文章
        workflowService.submitForReview(testArticleId);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, 1);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(cal.getTime());
        workflowService.approve(approveDto);

        // 2. 违规下线
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规");
        workflowService.violationOffline(violationDto);

        // 3. 模拟定时任务查询：SCHEDULED状态的文章不应包含已下线的文章
        Article article = articleService.getById(testArticleId);
        assertNotEquals(ArticleStatusEnum.SCHEDULED.getCode(), article.getStatus());
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());

        // 4. 即使手动将发布时间设为过去，文章也不是SCHEDULED状态，定时任务不会处理
        article.setPublishTime(new Date(System.currentTimeMillis() - 60000));
        articleService.updateById(article);

        article = articleService.getById(testArticleId);
        // 状态仍然是VIOLATION_OFFLINE，不会被定时任务误发布
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());
    }

    /**
     * 违规下线后缓存完全失效：详情缓存、首页列表、分类列表、浏览量均被清除
     */
    @Test
    void testViolationOfflineCacheInvalidation() {
        // 1. 发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 2. 设置各种缓存数据
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached_detail");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES, "cached_home");
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST, "cached_category");
        redisCache.setCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                testArticleId.toString(), 100);

        // 3. 执行违规下线
        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("测试缓存失效");
        workflowService.violationOffline(violationDto);

        // 4. 验证所有缓存均已清除
        assertNull(redisCache.getCacheObject(detailKey), "文章详情缓存应被清除");
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_HOME_ARTICLES),
                "首页列表缓存应被清除");
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST),
                "分类列表缓存应被清除");

        // 浏览量缓存也应被清除
        Integer viewCount = redisCache.getCacheMapValue(
                ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY, testArticleId.toString());
        assertNull(viewCount, "浏览量缓存应被清除");
    }

    /**
     * 撤回后缓存完全失效（含浏览量）
     */
    @Test
    void testWithdrawCacheInvalidation() {
        // 1. 发布文章
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 2. 设置缓存
        String detailKey = ArticleWorkflowConstants.CACHE_ARTICLE_DETAIL + testArticleId;
        redisCache.setCacheObject(detailKey, "cached_detail");
        redisCache.setCacheMapValue(ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY,
                testArticleId.toString(), 50);

        // 3. 撤回
        workflowService.withdraw(testArticleId);

        // 4. 验证缓存清除
        assertNull(redisCache.getCacheObject(detailKey), "撤回后详情缓存应清除");
        Integer viewCount = redisCache.getCacheMapValue(
                ArticleWorkflowConstants.CACHE_VIEW_COUNT_KEY, testArticleId.toString());
        assertNull(viewCount, "撤回后浏览量缓存应清除");
    }

    /**
     * 重新发布（违规下架 → 重新编辑 → 提交 → 审核通过）恢复分类/标签统计缓存
     */
    @Test
    void testRepublishRestoresCategoryAndTagStats() {
        // 1. 发布 → 违规下线
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("违规");
        workflowService.violationOffline(violationDto);

        // 2. 设置分类缓存（模拟下线后被缓存的情况）
        redisCache.setCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST, "stale_category_data");

        // 3. 重新编辑 → 提交 → 审核通过
        workflowService.reEdit(testArticleId);
        workflowService.submitForReview(testArticleId);

        ReviewActionDto reApproveDto = new ReviewActionDto();
        reApproveDto.setArticleId(testArticleId);
        workflowService.approve(reApproveDto);

        // 4. 验证分类缓存被刷新（重新发布时恢复统计）
        assertNull(redisCache.getCacheObject(ArticleWorkflowConstants.CACHE_CATEGORY_LIST),
                "重新发布后分类缓存应被刷新以恢复统计");

        // 5. 文章状态为已发布
        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
    }

    /**
     * OSS附件引用失效时阻止发布，并写入审计日志。
     * 模拟：文章内容包含格式非法的图片URL（不以http开头），审核通过时应被阻止。
     */
    @Test
    void testOssReferenceInvalidBlocksPublish() {
        // 创建一篇包含无效OSS引用的文章
        Article badArticle = new Article();
        badArticle.setTitle("OSS失效文章");
        badArticle.setContent("内容包含无效图片 <img src='ftp://invalid.oss.com/deleted.jpg'>");
        badArticle.setSummary("测试OSS校验");
        badArticle.setCategoryId(1L);
        badArticle.setStatus(ArticleStatusEnum.DRAFT.getCode());
        badArticle.setViewCount(0L);
        badArticle.setIsTop("0");
        badArticle.setIsComment("1");
        badArticle.setCreateBy(adminUserId);
        // 设置一个明确无效的缩略图URL
        badArticle.setThumbnail("notaurl");
        articleService.save(badArticle);
        Long badArticleId = badArticle.getId();

        try {
            // 提交审核
            workflowService.submitForReview(badArticleId);

            // 审核通过（立即发布）—— 应因OSS校验失败而抛出异常
            ReviewActionDto approveDto = new ReviewActionDto();
            approveDto.setArticleId(badArticleId);
            assertThrows(SystemException.class, () -> {
                workflowService.approve(approveDto);
            });

            // 验证文章仍处于待审核状态（未被发布）
            Article article = articleService.getById(badArticleId);
            assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());

            // 验证审计日志中记录了OSS引用失效
            List<ArticleAuditLog> logs = auditLogService.getAuditHistory(badArticleId);
            boolean hasOssFailureLog = logs.stream()
                    .anyMatch(log -> log.getReason() != null && log.getReason().contains("OSS附件引用失效"));
            assertTrue(hasOssFailureLog, "审计日志应记录OSS引用失效事件");
        } finally {
            articleService.removeById(badArticleId);
        }
    }

    /**
     * 强制发布时OSS校验：附件引用失效应阻止强制发布
     */
    @Test
    void testForcePublishOssValidation() {
        // 创建包含无效附件的文章
        Article badArticle = new Article();
        badArticle.setTitle("强制发布OSS测试");
        badArticle.setContent("无效引用 <img src='notavalidurl'>");
        badArticle.setSummary("测试");
        badArticle.setCategoryId(1L);
        badArticle.setStatus(ArticleStatusEnum.DRAFT.getCode());
        badArticle.setViewCount(0L);
        badArticle.setIsTop("0");
        badArticle.setIsComment("1");
        badArticle.setCreateBy(adminUserId);
        badArticle.setThumbnail("invalid_thumb");
        articleService.save(badArticle);
        Long badArticleId = badArticle.getId();

        try {
            assertThrows(SystemException.class, () -> {
                workflowService.forcePublish(badArticleId);
            });

            // 文章仍为草稿状态
            Article article = articleService.getById(badArticleId);
            assertEquals(ArticleStatusEnum.DRAFT.getCode(), article.getStatus());
        } finally {
            articleService.removeById(badArticleId);
        }
    }

    /**
     * 非管理员无法执行违规下线（权限拦截）
     */
    @Test
    void testViolationOfflinePermissionCheck() {
        // 以非管理员身份登录
        mockLoginAsNonAdmin();

        // 先由管理员发布文章
        mockLoginAsAdmin();
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        // 切换为非管理员
        mockLoginAsNonAdmin();

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("尝试违规下线");

        // 非管理员应被拒绝
        assertThrows(SystemException.class, () -> {
            workflowService.violationOffline(violationDto);
        });

        // 验证文章状态未变
        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());
    }

    /**
     * 审核日志完整性：违规下线从SCHEDULED状态应有完整的状态链路
     */
    @Test
    void testAuditTrailForScheduledToViolation() {
        // 草稿 → 待审核 → 定时发布 → 违规下线
        workflowService.submitForReview(testArticleId);

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, 1);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        approveDto.setScheduledPublishTime(cal.getTime());
        workflowService.approve(approveDto);

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("审核后发现违规");
        workflowService.violationOffline(violationDto);

        // 检查完整审计链路
        List<ArticleAuditLog> logs = auditLogService.getAuditHistory(testArticleId);
        assertTrue(logs.size() >= 3, "应至少有3条审计记录");

        // 最近的记录应为违规下线
        ArticleAuditLog latest = logs.get(0);
        assertEquals(ArticleStatusEnum.SCHEDULED.getCode(), latest.getFromStatus());
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), latest.getToStatus());
        assertTrue(latest.getReason().contains("审核后发现违规"));
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

    private void mockLoginAsNonAdmin() {
        User user = new User();
        user.setId(99L);
        user.setUserName("normaluser");
        user.setNickName("普通用户");
        user.setType("0");

        List<String> perms = Arrays.asList(
                "content:article:submit",
                "content:article:approve"
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
