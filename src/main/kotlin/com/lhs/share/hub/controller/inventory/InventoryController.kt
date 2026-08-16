package com.lhs.share.hub.controller.inventory

import com.lhs.share.config.accesslimit.AccessLimit
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.response.InventoryAcquiredResponse
import com.lhs.share.hub.controller.inventory.response.InventoryCatalogResponse
import com.lhs.share.hub.controller.inventory.response.InventoryCurrentResponse
import com.lhs.share.hub.controller.inventory.response.InventoryExportResponse
import com.lhs.share.hub.controller.inventory.response.InventoryImportResult
import com.lhs.share.hub.controller.inventory.response.InventoryRecordListItemDto
import com.lhs.share.hub.service.inventory.EntityCatalogService
import com.lhs.share.hub.service.inventory.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 库存与奖励接口(数据存 HubBackend.inventory_current / inventory_records)
 *
 * 安全:/v1/inventory/catalog 公开(见 SecurityConfig.URL_PERMIT_ALL);
 * 其余接口需登录,userId 一律取自当前 JWT(authenticateHelper.requireUserId()),
 * 库存为私有数据,绝不由前端传入用户身份。
 *
 * 协议:规范/5.0 后端设计、规范/5.1 交换协议 v1。
 */
@Tag(name = "库存", description = "库存与奖励数据(HubBackend)")
@RequestMapping("/v1/inventory")
@RestController
class InventoryController(
    private val inventoryService: InventoryService,
    private val catalogService: EntityCatalogService,
    private val helper: AuthenticationHelper,
) {
    /**
     * 导入库存/奖励交换文档(需登录;整份校验,幂等)。
     */
    @Operation(summary = "导入库存/奖励")
    @RequireJwt
    @PostMapping("/import")
    fun import(@Valid @RequestBody request: InventoryImportRequest): ApiResult<InventoryImportResult> =
        success(inventoryService.import(helper.requireUserId(), request))

    /**
     * 当前库存查询(需登录;entity_type 可选,缺省返回全部类型)。
     */
    @Operation(summary = "当前库存")
    @RequireJwt
    @GetMapping("/current")
    fun current(@RequestParam(name = "entity_type", required = false) entityType: String?): ApiResult<List<InventoryCurrentResponse>> =
        success(inventoryService.current(helper.requireUserId(), entityType))

    /**
     * 时段获得量查询(需登录;只聚合 reward_delta,区间 [from, to))。
     */
    @Operation(summary = "时段获得量")
    @RequireJwt
    @GetMapping("/acquired")
    fun acquired(
        @RequestParam(name = "entity_type") entityType: String,
        @RequestParam(name = "from") from: String,
        @RequestParam(name = "to") to: String,
    ): ApiResult<InventoryAcquiredResponse> =
        success(inventoryService.acquired(helper.requireUserId(), entityType, parseQueryTime(from), parseQueryTime(to)))

    /**
     * 导出(需登录;默认仅当前状态 full 快照,include=rewards 时附带区间奖励流水)。
     */
    @Operation(summary = "导出")
    @RequireJwt
    @GetMapping("/export")
    fun export(
        @RequestParam(name = "include", required = false, defaultValue = "current") include: String,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
    ): ApiResult<InventoryExportResponse> {
        val includeRewards = include.split(",").any { it.trim() == "rewards" }
        val fromInstant = from?.let { parseQueryTime(it) }
        val toInstant = to?.let { parseQueryTime(it) }
        return success(inventoryService.export(helper.requireUserId(), includeRewards, fromInstant, toInstant))
    }

    /**
     * 导入记录列表(需登录;entity_type/from/to 可选过滤,按 effective_at 倒序)。
     */
    @Operation(summary = "导入记录列表")
    @RequireJwt
    @GetMapping("/records")
    fun records(
        @RequestParam(name = "entity_type", required = false) entityType: String?,
        @RequestParam(name = "from", required = false) from: String?,
        @RequestParam(name = "to", required = false) to: String?,
    ): ApiResult<List<InventoryRecordListItemDto>> = success(
        inventoryService.listRecords(
            helper.requireUserId(),
            entityType,
            from?.let { parseQueryTime(it) },
            to?.let { parseQueryTime(it) },
        ),
    )

    /**
     * 删除单条记录(需登录,仅本人;不存在/越权统一 404)。
     * 删除后全量重放剩余记录重建当前库存(语义等价于该记录从未导入过)。
     */
    @Operation(summary = "删除单条记录(重放重建库存)")
    @RequireJwt
    @AccessLimit(times = 10, second = 60)
    @DeleteMapping("/records/{recordId}")
    fun deleteRecord(@PathVariable(name = "recordId") recordId: String): ApiResult<Boolean> {
        inventoryService.deleteRecord(helper.requireUserId(), recordId)
        return success(true)
    }

    /**
     * 对象目录(公开,无需登录;返回目录版本与全部 {entity_type, id, name})。
     */
    @Operation(summary = "对象目录")
    @GetMapping("/catalog")
    fun catalog(): ApiResult<InventoryCatalogResponse> = success(catalogService.catalog())

    /**
     * 解析查询参数中的时间:支持 RFC 3339(带 offset 或 Z)与纯日期 YYYY-MM-DD。
     * 纯日期按 UTC 当日起点解析(前端 <input type=date> 传的是本地日期字符串,
     * 由前端决定时区语义,这里约定按 UTC 处理以保证 [from,to) 区间稳定)。
     */
    private fun parseQueryTime(value: String): Instant {
        val trimmed = value.trim()
        return try {
            if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC)
            } else {
                OffsetDateTime.parse(trimmed).toInstant()
            }
        } catch (e: Exception) {
            throw com.lhs.share.controller.response.ApiResultException(
                422,
                "schema_validation_failed: 非法时间 $value",
            )
        }
    }
}
