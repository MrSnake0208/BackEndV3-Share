package com.lhs.share.hub.controller.inventory

import com.lhs.share.config.accesslimit.AccessLimit
import com.lhs.share.config.doc.InventoryDeleteResponses
import com.lhs.share.config.doc.InventoryPublicResponses
import com.lhs.share.config.doc.InventoryReadResponses
import com.lhs.share.config.doc.InventoryWriteResponses
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.inventory.request.InventoryAccountRequest
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.response.InventoryAccountResponse
import com.lhs.share.hub.controller.inventory.response.InventoryAcquiredResponse
import com.lhs.share.hub.controller.inventory.response.InventoryAgentFavoriteListResponse
import com.lhs.share.hub.controller.inventory.response.InventoryAgentFavoriteResponse
import com.lhs.share.hub.controller.inventory.response.InventoryCatalogResponse
import com.lhs.share.hub.controller.inventory.response.InventoryCurrentResponse
import com.lhs.share.hub.controller.inventory.response.InventoryExportResponse
import com.lhs.share.hub.controller.inventory.response.InventoryImportResult
import com.lhs.share.hub.controller.inventory.response.InventoryRecordPageResponse
import com.lhs.share.hub.service.inventory.EntityCatalogService
import com.lhs.share.hub.service.inventory.InventoryAccountService
import com.lhs.share.hub.service.inventory.InventoryAgentFavoriteService
import com.lhs.share.hub.service.inventory.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * 库存与奖励接口(数据存 HubBackend.inventory_current / inventory_records)
 *
 * 安全:/v1/inventory/catalog 公开(见 SecurityConfig.URL_PERMIT_ALL);
 * 其余接口需登录,userId 一律取自当前 JWT(authenticateHelper.requireUserId()),
 * 库存为私有数据,绝不由前端传入用户身份。
 *
 * 协议:规范/5.0 后端设计、规范/5.1 交换协议 v2。
 */
@Tag(name = "库存", description = "库存与奖励数据(HubBackend)")
@RequestMapping("/v1/inventory", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class InventoryController(
    private val inventoryService: InventoryService,
    private val accountService: InventoryAccountService,
    private val favoriteService: InventoryAgentFavoriteService,
    private val catalogService: EntityCatalogService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "创建库存子账号")
    @InventoryWriteResponses
    @RequireJwt
    @PostMapping("/accounts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createAccount(@Valid @RequestBody request: InventoryAccountRequest): ApiResult<InventoryAccountResponse> =
        success(accountService.create(helper.requireUserId(), request.name))

    @Operation(summary = "库存子账号列表")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/accounts")
    fun accounts(): ApiResult<List<InventoryAccountResponse>> = success(accountService.list(helper.requireUserId()))

    @Operation(summary = "修改库存子账号名称")
    @InventoryWriteResponses
    @RequireJwt
    @PatchMapping("/accounts/{accountId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun renameAccount(
        @PathVariable accountId: String,
        @Valid @RequestBody request: InventoryAccountRequest,
    ): ApiResult<InventoryAccountResponse> = success(accountService.rename(helper.requireUserId(), accountId, request.name))

    @Operation(summary = "删除库存子账号及其库存和流水")
    @InventoryDeleteResponses
    @RequireJwt
    @DeleteMapping("/accounts/{accountId}")
    fun deleteAccount(@PathVariable accountId: String): ApiResult<Boolean> {
        accountService.delete(helper.requireUserId(), accountId)
        return success(true)
    }

    @Operation(summary = "密探特别关注列表")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/agent-favorites")
    fun agentFavorites(@RequestParam(name = "account_id") accountId: String): ApiResult<InventoryAgentFavoriteListResponse> =
        success(favoriteService.list(helper.requireUserId(), accountId))

    @Operation(summary = "特别关注密探")
    @InventoryWriteResponses
    @RequireJwt
    @PutMapping("/agent-favorites/{agentId}")
    fun addAgentFavorite(
        @PathVariable agentId: String,
        @RequestParam(name = "account_id") accountId: String,
    ): ApiResult<InventoryAgentFavoriteResponse> = success(
        favoriteService.add(helper.requireUserId(), accountId, agentId),
    )

    @Operation(summary = "取消特别关注密探")
    @InventoryWriteResponses
    @RequireJwt
    @DeleteMapping("/agent-favorites/{agentId}")
    fun removeAgentFavorite(
        @PathVariable agentId: String,
        @RequestParam(name = "account_id") accountId: String,
    ): ApiResult<InventoryAgentFavoriteResponse> = success(
        favoriteService.remove(helper.requireUserId(), accountId, agentId),
    )

    /**
     * 导入库存/奖励交换文档(需登录;整份校验,幂等)。
     */
    @Operation(summary = "导入库存/奖励")
    @InventoryWriteResponses
    @RequireJwt
    @PostMapping("/import", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun import(@Valid @RequestBody request: InventoryImportRequest): ApiResult<InventoryImportResult> =
        success(inventoryService.import(helper.requireUserId(), request))

    /**
     * 当前库存查询(需登录;entity_type 可选,缺省返回全部类型)。
     */
    @Operation(summary = "当前库存")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/current")
    fun current(
        @RequestParam(name = "account_id") accountId: String,
        @Parameter(schema = Schema(allowableValues = ["item", "agent"]))
        @RequestParam(name = "entity_type", required = false)
        entityType: String?,
    ): ApiResult<List<InventoryCurrentResponse>> = success(inventoryService.current(helper.requireUserId(), accountId, entityType))

    /**
     * 时段获得量查询(需登录;只聚合 reward_delta,区间 [from, to))。
     */
    @Operation(summary = "时段获得量")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/acquired")
    fun acquired(
        @RequestParam(name = "account_id") accountId: String,
        @Parameter(schema = Schema(allowableValues = ["item", "agent"]))
        @RequestParam(name = "entity_type") entityType: String,
        @RequestParam(name = "from") from: OffsetDateTime,
        @RequestParam(name = "to") to: OffsetDateTime,
    ): ApiResult<InventoryAcquiredResponse> =
        success(inventoryService.acquired(helper.requireUserId(), accountId, entityType, from.toInstant(), to.toInstant()))

    /**
     * 导出(需登录;默认仅当前状态 full 快照,include=rewards 时附带区间奖励流水)。
     */
    @Operation(summary = "导出")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/export")
    fun export(
        @RequestParam(name = "account_id", required = false) accountId: String?,
        @Parameter(schema = Schema(allowableValues = ["all"]))
        @RequestParam(name = "scope", required = false) scope: String?,
        @Parameter(schema = Schema(allowableValues = ["current", "current,rewards"]))
        @RequestParam(name = "include", required = false, defaultValue = "current")
        include: String,
        @RequestParam(name = "from", required = false) from: OffsetDateTime?,
        @RequestParam(name = "to", required = false) to: OffsetDateTime?,
    ): InventoryExportResponse {
        if (include != "current" && include != "current,rewards") {
            throw com.lhs.share.hub.service.inventory.InventoryApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                "include must be current or current,rewards",
            )
        }
        return inventoryService.export(
            helper.requireUserId(),
            accountId,
            scope,
            include == "current,rewards",
            from?.toInstant(),
            to?.toInstant(),
        )
    }

    /**
     * 导入记录列表(需登录;entity_type/from/to 可选过滤,按 effective_at 倒序)。
     */
    @Operation(summary = "导入记录列表")
    @InventoryReadResponses
    @RequireJwt
    @GetMapping("/records")
    fun records(
        @RequestParam(name = "account_id") accountId: String,
        @Parameter(schema = Schema(allowableValues = ["item", "agent"]))
        @RequestParam(name = "entity_type", required = false) entityType: String?,
        @RequestParam(name = "from", required = false) from: OffsetDateTime?,
        @RequestParam(name = "to", required = false) to: OffsetDateTime?,
        @RequestParam(name = "cursor", required = false) cursor: String?,
        @Parameter(
            schema = Schema(
                type = "integer",
                format = "int32",
                minimum = "1",
                maximum = "100",
                defaultValue = "50",
            ),
        )
        @RequestParam(name = "limit", required = false, defaultValue = "50") limit: Int,
    ): ApiResult<InventoryRecordPageResponse> = success(
        inventoryService.listRecords(
            helper.requireUserId(),
            accountId,
            entityType,
            from?.toInstant(),
            to?.toInstant(),
            cursor,
            limit,
        ),
    )

    /**
     * 删除单条记录(需登录,仅本人;不存在/越权统一 404)。
     * 删除后全量重放剩余记录重建当前库存(语义等价于该记录从未导入过)。
     */
    @Operation(summary = "删除单条记录(重放重建库存)")
    @InventoryDeleteResponses
    @RequireJwt
    @AccessLimit(times = 10, second = 60)
    @DeleteMapping("/records/{recordId}")
    fun deleteRecord(
        @PathVariable(name = "recordId") recordId: String,
        @RequestParam(name = "account_id") accountId: String,
    ): ApiResult<Boolean> {
        inventoryService.deleteRecord(helper.requireUserId(), accountId, recordId)
        return success(true)
    }

    /**
     * 对象目录(公开,无需登录;返回目录版本与全部 {entity_type, id, name})。
     */
    @Operation(summary = "对象目录")
    @InventoryPublicResponses
    @GetMapping("/catalog")
    fun catalog(): ApiResult<InventoryCatalogResponse> = success(catalogService.catalog())
}
