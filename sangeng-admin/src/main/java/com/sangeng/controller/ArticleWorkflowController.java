package com.sangeng.controller;

import com.sangeng.annotation.SystemLog;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.dto.ReviewActionDto;
import com.sangeng.domain.dto.ViolationActionDto;
import com.sangeng.domain.dto.RepublishActionDto;
import com.sangeng.domain.dto.ArchiveActionDto;
import com.sangeng.service.ArticleWorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/content/article/workflow")
public class ArticleWorkflowController {

    @Autowired
    private ArticleWorkflowService workflowService;

    @PostMapping("/submit/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:submit')")
    @SystemLog(businessName = "提交文章审核")
    public ResponseResult submitForReview(@PathVariable Long articleId) {
        return workflowService.submitForReview(articleId);
    }

    @PostMapping("/approve")
    @PreAuthorize("@ps.hasPermission('content:article:approve')")
    @SystemLog(businessName = "审核通过文章")
    public ResponseResult approve(@RequestBody ReviewActionDto dto) {
        return workflowService.approve(dto);
    }

    @PostMapping("/reject")
    @PreAuthorize("@ps.hasPermission('content:article:reject')")
    @SystemLog(businessName = "审核驳回文章")
    public ResponseResult reject(@RequestBody ReviewActionDto dto) {
        return workflowService.reject(dto);
    }

    @PostMapping("/withdraw/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:withdraw')")
    @SystemLog(businessName = "撤回文章")
    public ResponseResult withdraw(@PathVariable Long articleId) {
        return workflowService.withdraw(articleId);
    }

    @PostMapping("/reEdit/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:submit')")
    @SystemLog(businessName = "重新编辑文章")
    public ResponseResult reEdit(@PathVariable Long articleId) {
        return workflowService.reEdit(articleId);
    }

    @PostMapping("/violation")
    @PreAuthorize("@ps.hasPermission('content:article:violation')")
    @SystemLog(businessName = "违规下架文章")
    public ResponseResult violationOffline(@RequestBody ViolationActionDto dto) {
        return workflowService.violationOffline(dto);
    }

    @PostMapping("/forcePublish/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:forcePublish')")
    @SystemLog(businessName = "强制发布文章")
    public ResponseResult forcePublish(@PathVariable Long articleId) {
        return workflowService.forcePublish(articleId);
    }

    @GetMapping("/pending")
    @PreAuthorize("@ps.hasPermission('content:article:review')")
    public ResponseResult getPendingReviewArticles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return workflowService.getPendingReviewArticles(pageNum, pageSize);
    }

    @GetMapping("/scheduled")
    @PreAuthorize("@ps.hasPermission('content:article:schedule')")
    public ResponseResult getScheduledArticles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return workflowService.getScheduledArticles(pageNum, pageSize);
    }

    @GetMapping("/violation")
    @PreAuthorize("@ps.hasPermission('content:article:violation')")
    public ResponseResult getViolationArticles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return workflowService.getViolationArticles(pageNum, pageSize);
    }

    @GetMapping("/audit/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:review')")
    public ResponseResult getAuditHistory(@PathVariable Long articleId) {
        return workflowService.getAuditHistory(articleId);
    }

    @PostMapping("/fullPublish/{articleId}")
    @PreAuthorize("@ps.hasPermission('content:article:fullPublish')")
    @SystemLog(businessName = "灰度转完全发布")
    public ResponseResult fullPublish(@PathVariable Long articleId) {
        return workflowService.fullPublish(articleId);
    }

    @PostMapping("/republish")
    @PreAuthorize("@ps.hasPermission('content:article:republish')")
    @SystemLog(businessName = "重新发布文章")
    public ResponseResult republish(@RequestBody RepublishActionDto dto) {
        return workflowService.republish(dto);
    }

    @PostMapping("/archive")
    @PreAuthorize("@ps.hasPermission('content:article:archive')")
    @SystemLog(businessName = "归档文章")
    public ResponseResult archive(@RequestBody ArchiveActionDto dto) {
        return workflowService.archive(dto);
    }

    @GetMapping("/gray")
    @PreAuthorize("@ps.hasPermission('content:article:grayPublish')")
    public ResponseResult getGrayArticles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return workflowService.getGrayArticles(pageNum, pageSize);
    }

    @GetMapping("/archived")
    @PreAuthorize("@ps.hasPermission('content:article:archive')")
    public ResponseResult getArchivedArticles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return workflowService.getArchivedArticles(pageNum, pageSize);
    }
}
