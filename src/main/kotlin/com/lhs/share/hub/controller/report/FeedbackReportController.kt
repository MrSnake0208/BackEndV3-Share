package com.lhs.share.hub.controller.report

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.report.request.FeedbackMessageAppendRequest
import com.lhs.share.hub.controller.report.request.FeedbackReportCreateRequest
import com.lhs.share.hub.controller.report.request.FeedbackStatusUpdateRequest
import com.lhs.share.hub.controller.report.response.FeedbackReportListResponse
import com.lhs.share.hub.controller.report.response.FeedbackReportResponse
import com.lhs.share.hub.service.report.FeedbackReportService
import com.lhs.share.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 反馈工单接口
 *
 * 提供反馈工单的 CRUD 操作, 包括创建、列表、详情、追加消息、状态变更。
 */
@Tag(name = "Feedback Reports", description = "反馈工单")
@RequestMapping("/v1/reports")
@RestController
class FeedbackReportController(
    private val feedbackReportService: FeedbackReportService,
    private val helper: AuthenticationHelper,
    private val userService: UserService,
) {
    /**
     * 创建反馈工单
     */
    @Operation(summary = "创建反馈工单")
    @RequireJwt
    @PostMapping
    fun create(@Valid @RequestBody request: FeedbackReportCreateRequest): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        return success(feedbackReportService.create(userId, request))
    }

    /**
     * 查询反馈工单列表
     */
    @Operation(summary = "查询反馈工单列表")
    @RequireJwt
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "true") mine: Boolean,
        @RequestParam(required = false) reporterUserId: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "createdAt") sortBy: String,
        @RequestParam(defaultValue = "desc") sortOrder: String,
    ): ApiResult<FeedbackReportListResponse> {
        val userId = helper.requireUserId()
        val isAdmin = userService.hasAdminPrivileges(userId)

        // 非管理员请求查看全部(mine=false) → 403
        if (!mine && !isAdmin) {
            return ApiResult.fail(403, "无权查看全部工单")
        }

        return success(
            feedbackReportService.list(
                currentUserId = userId,
                isAdmin = isAdmin,
                page = page,
                pageSize = pageSize,
                status = status,
                type = type,
                mine = mine,
                reporterUserId = reporterUserId,
                keyword = q,
                sortBy = sortBy,
                sortOrder = sortOrder,
            ),
        )
    }

    /**
     * 获取工单详情
     */
    @Operation(summary = "获取工单详情")
    @RequireJwt
    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        return success(feedbackReportService.getById(userId, id))
    }

    /**
     * 追加消息
     */
    @Operation(summary = "追加消息到工单")
    @RequireJwt
    @PostMapping("/{id}/messages")
    fun appendMessage(
        @PathVariable id: String,
        @Valid @RequestBody request: FeedbackMessageAppendRequest,
    ): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        val isAdmin = userService.hasAdminPrivileges(userId)
        return success(feedbackReportService.appendMessage(userId, id, request, isAdmin))
    }

    /**
     * 更新工单状态
     */
    @Operation(summary = "更新工单状态")
    @RequireJwt
    @PatchMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: String,
        @RequestBody request: FeedbackStatusUpdateRequest,
    ): ApiResult<FeedbackReportResponse> {
        val userId = helper.requireUserId()
        val isAdmin = userService.hasAdminPrivileges(userId)
        return success(feedbackReportService.updateStatus(userId, id, request, isAdmin))
    }
}