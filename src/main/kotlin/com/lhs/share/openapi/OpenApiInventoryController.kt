package com.lhs.share.openapi

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.inventory.response.InventoryCurrentResponse
import com.lhs.share.hub.service.inventory.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 第三方 API 示例接口(使用 API Token 认证,非 JWT)
 *
 * 从 Authorization 请求头取 "Bearer <token>" 校验,复用 InventoryService。
 */
@Tag(name = "OpenAPI Inventory")
@RequestMapping("/open-api")
@RestController
class OpenApiInventoryController(
    private val tokenService: OpenApiTokenService,
    private val inventoryService: InventoryService,
) {
    /**
     * 当前库存(第三方只读示例,需 API Token 且具备 inventory:read 权限)
     */
    @Operation(summary = "当前库存")
    @GetMapping("/inventory/current")
    fun inventoryCurrent(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestParam(name = "entity_type", required = false) entityType: String?,
    ): ApiResult<List<InventoryCurrentResponse>> {
        val token = authorization?.removePrefix("Bearer ")?.trim()
        val userId = tokenService.validate(token, OpenApiPermission.INVENTORY_READ.code)
        return success(inventoryService.current(userId, entityType))
    }
}
