package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.domain.dto.*;
import com.sangeng.domain.entity.*;
import com.sangeng.enums.*;
import com.sangeng.exception.SystemException;
import com.sangeng.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重新发布工作流测试 - 覆盖从撤回/违规下架/归档状态重新发布的场景
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class RepublishFlowTest {

    @Autowired
    private ArticleWorkflowService workflowService;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleAuditLogService auditLogService;

    private Long testArticleId;
    private Long adminUserId = 1L;

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();

        Article article = new Article();
        article.setTitle("测试文章 - 重新发布");
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
     * 从撤回状态重新发布：已撤回 -> 重新发布 -> 待审核
     */
    @Test
    void testRepublishFromWithdrawn() {
        // 草稿 -> 提交审核 -> 审核通过 -> 撤回
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);
        workflowService.withdraw(testArticleId);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.WITHDRAWN.getCode(), article.getStatus());

        // 重新发布
        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        workflowService.republish(dto);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());
    }

    /**
     * 从违规下架状态重新发布：违规下架 -> 重新发布 -> 待审核
     */
    @Test
    void testRepublishFromViolation() {
        // 草稿 -> 提交审核 -> 审核通过 -> 违规下架
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("内容违规");
        workflowService.violationOffline(violationDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.VIOLATION_OFFLINE.getCode(), article.getStatus());

        // 重新发布
        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        workflowService.republish(dto);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());
    }

    /**
     * 从归档状态重新发布：已归档 -> 重新发布 -> 待审核
     */
    @Test
    void testRepublishFromArchived() {
        // 草稿 -> 提交审核 -> 审核通过 -> 归档
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ArchiveActionDto archiveDto = new ArchiveActionDto();
        archiveDto.setArticleId(testArticleId);
        archiveDto.setArchiveReason("内容过时");
        workflowService.archive(archiveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.ARCHIVED.getCode(), article.getStatus());

        // 重新发布
        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        workflowService.republish(dto);

        article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PENDING_REVIEW.getCode(), article.getStatus());
    }

    /**
     * 重新发布次数累加：两次完整的撤回-重新发布循环后 republishCount 应为 2
     */
    @Test
    void testRepublishIncrementsCount() {
        // 第一轮：提交审核 -> 审核通过 -> 撤回 -> 重新发布
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);
        workflowService.withdraw(testArticleId);

        RepublishActionDto dto1 = new RepublishActionDto();
        dto1.setArticleId(testArticleId);
        dto1.setReason("test republish");
        workflowService.republish(dto1);

        // 审核通过使其再次发布
        ReviewActionDto approveDto2 = new ReviewActionDto();
        approveDto2.setArticleId(testArticleId);
        workflowService.approve(approveDto2);

        // 第二轮：撤回 -> 重新发布
        workflowService.withdraw(testArticleId);

        RepublishActionDto dto2 = new RepublishActionDto();
        dto2.setArticleId(testArticleId);
        dto2.setReason("test republish");
        workflowService.republish(dto2);

        Article article = articleService.getById(testArticleId);
        assertEquals(2, article.getRepublishCount());
    }

    /**
     * 从违规下架重新发布后，违规原因应被清除
     */
    @Test
    void testRepublishClearsViolationReason() {
        // 草稿 -> 提交审核 -> 审核通过 -> 违规下架
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        ViolationActionDto violationDto = new ViolationActionDto();
        violationDto.setArticleId(testArticleId);
        violationDto.setViolationReason("内容包含违规信息");
        workflowService.violationOffline(violationDto);

        Article article = articleService.getById(testArticleId);
        assertEquals("内容包含违规信息", article.getViolationReason());

        // 重新发布
        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        workflowService.republish(dto);

        article = articleService.getById(testArticleId);
        assertNull(article.getViolationReason());
    }

    /**
     * 非法状态重新发布：已发布和草稿状态不允许重新发布，应抛出 SystemException
     */
    @Test
    void testRepublishInvalidStatus() {
        // 测试 PUBLISHED 状态不能重新发布
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);

        Article article = articleService.getById(testArticleId);
        assertEquals(ArticleStatusEnum.PUBLISHED.getCode(), article.getStatus());

        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        assertThrows(SystemException.class, () -> {
            workflowService.republish(dto);
        });

        // 测试 DRAFT 状态不能重新发布
        Article draftArticle = new Article();
        draftArticle.setTitle("草稿文章 - 重发布测试");
        draftArticle.setContent("测试内容");
        draftArticle.setSummary("测试摘要");
        draftArticle.setCategoryId(1L);
        draftArticle.setStatus(ArticleStatusEnum.DRAFT.getCode());
        draftArticle.setViewCount(0L);
        draftArticle.setIsTop("0");
        draftArticle.setIsComment("1");
        draftArticle.setCreateBy(adminUserId);
        articleService.save(draftArticle);
        Long draftArticleId = draftArticle.getId();

        try {
            RepublishActionDto draftDto = new RepublishActionDto();
            draftDto.setArticleId(draftArticleId);
            draftDto.setReason("test republish");
            assertThrows(SystemException.class, () -> {
                workflowService.republish(draftDto);
            });
        } finally {
            articleService.removeById(draftArticleId);
        }
    }

    /**
     * 重新发布审计日志：republish 操作应产生 2 条审计记录（REPUBLISH + PENDING_REVIEW）
     */
    @Test
    void testRepublishAuditTrail() {
        // 草稿 -> 提交审核 -> 审核通过 -> 撤回
        workflowService.submitForReview(testArticleId);
        ReviewActionDto approveDto = new ReviewActionDto();
        approveDto.setArticleId(testArticleId);
        workflowService.approve(approveDto);
        workflowService.withdraw(testArticleId);

        // 记录重新发布前的日志数量
        List<ArticleAuditLog> logsBefore = auditLogService.getAuditHistory(testArticleId);
        int countBefore = logsBefore.size();

        // 重新发布
        RepublishActionDto dto = new RepublishActionDto();
        dto.setArticleId(testArticleId);
        dto.setReason("test republish");
        workflowService.republish(dto);

        // 验证新增 2 条审计记录
        List<ArticleAuditLog> logsAfter = auditLogService.getAuditHistory(testArticleId);
        int newLogCount = logsAfter.size() - countBefore;
        assertEquals(2, newLogCount, "重新发布应产生2条审计记录（REPUBLISH + PENDING_REVIEW）");

        // 验证最新两条记录分别对应 REPUBLISH 和 PENDING_REVIEW 状态转换
        ArticleAuditLog latestLog = logsAfter.get(0);
        ArticleAuditLog secondLog = logsAfter.get(1);

        boolean hasRepublishEntry = logsAfter.stream()
                .anyMatch(log -> ArticleStatusEnum.REPUBLISH.getCode().equals(log.getToStatus())
                        || ArticleStatusEnum.REPUBLISH.getCode().equals(log.getFromStatus()));
        boolean hasPendingReviewEntry = logsAfter.stream()
                .anyMatch(log -> ArticleStatusEnum.PENDING_REVIEW.getCode().equals(log.getToStatus()));

        assertTrue(hasRepublishEntry, "审计日志应包含 REPUBLISH 状态转换记录");
        assertTrue(hasPendingReviewEntry, "审计日志应包含 PENDING_REVIEW 状态转换记录");
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
                "content:article:review",
                "content:article:republish",
                "content:article:archive"
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
