package com.lhs.share.hub.controller.star

import com.lhs.share.config.doc.InventoryReadResponses
import com.lhs.share.config.doc.InventoryWriteResponses
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.star.request.StarWorkspaceCurrentRequest
import com.lhs.share.hub.controller.star.response.StarWorkspaceCurrentResponse
import com.lhs.share.hub.service.star.StarWorkspaceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "星石工作区", description = "YuanStar 云端计划目标、背包计数和经验数量")
@RequestMapping("/v1/star-workspace", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class StarWorkspaceController(
    private val service: StarWorkspaceService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "读取当前星石工作区", description = "无工作区时返回 revision=0 的空状态。")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/current")
    fun current(@RequestParam(name = "account_id") accountId: String): ApiResult<StarWorkspaceCurrentResponse> = success(
        service.current(helper.requireUserId(), accountId),
    )

    @Operation(summary = "整体替换当前星石工作区", description = "expected_revision 为 account 级 CAS，不会静默覆盖。")
    @InventoryWriteResponses
    @RequireJwt
    @PutMapping("/current", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putCurrent(
        @RequestParam(name = "account_id") accountId: String,
        @Valid @RequestBody request: StarWorkspaceCurrentRequest,
    ): ApiResult<StarWorkspaceCurrentResponse> = success(
        service.putCurrent(helper.requireUserId(), accountId, request),
    )
}
