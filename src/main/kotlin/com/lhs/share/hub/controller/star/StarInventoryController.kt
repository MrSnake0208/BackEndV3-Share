package com.lhs.share.hub.controller.star

import com.lhs.share.config.doc.InventoryReadResponses
import com.lhs.share.config.doc.InventoryWriteResponses
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.star.request.StarInventorySnapshotRequest
import com.lhs.share.hub.controller.star.response.StarInventorySnapshotResponse
import com.lhs.share.hub.service.star.StarInventoryService
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

@Tag(name = "星石库存", description = "YuanStar 当前星石背包快照")
@RequestMapping("/v1/star-inventory", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class StarInventoryController(
    private val service: StarInventoryService,
    private val helper: AuthenticationHelper,
) {
    @Operation(
        summary = "读取当前星石库存快照",
        description = "无快照时仍返回 HTTP 200 和空 entries；用户身份来自 JWT，account_id 必须属于当前用户。",
    )
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/current")
    fun current(@RequestParam(name = "account_id") accountId: String): ApiResult<StarInventorySnapshotResponse> = success(
        service.current(helper.requireUserId(), accountId),
    )

    @Operation(
        summary = "替换当前星石库存快照",
        description = "PUT 是完整替换；规范化内容重复提交幂等，旧时间和同时间不同内容返回 409。",
    )
    @InventoryWriteResponses
    @RequireJwt
    @PutMapping("/current", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun putCurrent(
        @RequestParam(name = "account_id") accountId: String,
        @Valid @RequestBody request: StarInventorySnapshotRequest,
    ): ApiResult<StarInventorySnapshotResponse> = success(
        service.putCurrent(helper.requireUserId(), accountId, request),
    )
}
