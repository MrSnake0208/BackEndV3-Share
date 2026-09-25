package com.lhs.share.hub.controller.report

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.report.request.FeedbackMergeRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublicStatusRequest
import com.lhs.share.hub.controller.report.request.FeedbackPublishRequest
import com.lhs.share.hub.controller.report.request.FeedbackTypeUpdateRequest
import com.lhs.share.hub.controller.report.request.FeedbackVersionRequest
import com.lhs.share.hub.controller.report.response.FeedbackReportResponse
import com.lhs.share.hub.controller.report.response.FeedbackVersionOptionResponse
import com.lhs.share.hub.service.report.FeedbackPublicAdministrationService
import com.lhs.share.hub.service.report.FeedbackReportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 反馈公开管理接口(管理员)。
 *
 * 权限沿用反馈板块管理权限;非管理员返回业务 status_code=403。
 * 所有接口返回更新后的内部工单详情,便于工作台直接刷新面板。
 */
@Tag(name = "Feedback Admin", description = "反馈公开管理与重复合并")
@RequireJwt
@RestController
@RequestMapping("/v1/admin/feedback")
class AdminFeedbackController(
    private val administrationService: FeedbackPublicAdministrationService,
    private val feedbackReportService: FeedbackReportService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "发布反馈到反馈广场")
    @PatchMapping("/{id}/publish")
    fun publish(@PathVariable id: String, @Valid @RequestBody request: FeedbackPublishRequest): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.publish(userId, id, request)
        return success(feedbackReportService.getById(userId, id))
    }

    @Operation(summary = "取消公开反馈")
    @PatchMapping("/{id}/unpublish")
    fun unpublish(@PathVariable id: String): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.unpublish(userId, id)
        return success(feedbackReportService.getById(userId, id))
    }

    @Operation(summary = "修改公开开发状态")
    @PatchMapping("/{id}/public-status")
    fun updatePublicStatus(
        @PathVariable id: String,
        @Valid @RequestBody request: FeedbackPublicStatusRequest,
    ): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.updatePublicStatus(userId, id, request)
        return success(feedbackReportService.getById(userId, id))
    }

    @Operation(summary = "合并重复反馈")
    @PostMapping("/{id}/merge")
    fun merge(@PathVariable id: String, @Valid @RequestBody request: FeedbackMergeRequest): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.merge(userId, id, request)
        return success(feedbackReportService.getById(userId, id))
    }

    @Operation(summary = "修改反馈类型")
    @PatchMapping("/{id}/type")
    fun updateType(@PathVariable id: String, @Valid @RequestBody request: FeedbackTypeUpdateRequest): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.updateType(userId, id, request)
        return success(feedbackReportService.getById(userId, id))
    }

    @Operation(summary = "获取反馈可关联的版本")
    @GetMapping("/version-options")
    fun versionOptions(): ApiResult<List<FeedbackVersionOptionResponse>> =
        success(administrationService.versionOptions(helper.requireUserId()))

    @Operation(summary = "关联目标/完成版本")
    @PatchMapping("/{id}/versions")
    fun updateVersions(@PathVariable id: String, @Valid @RequestBody request: FeedbackVersionRequest): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        administrationService.updateVersions(userId, id, request)
        return success(feedbackReportService.getById(userId, id))
    }
}
