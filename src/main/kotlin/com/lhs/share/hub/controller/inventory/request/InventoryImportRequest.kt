package com.lhs.share.hub.controller.inventory.request

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 库存/奖励导入请求(协议 5.1,Jackson SNAKE_CASE 反序列化)
 *
 * 对应交换文档完整 JSON 顶层结构:format / version / exported_at / producer /
 * catalog_version / records。正文不携带 user_id,用户归属由登录态确定。
 */
data class InventoryImportRequest(
    @field:NotBlank(message = "format 不能为空")
    @field:Pattern(regexp = "myshare-inventory-exchange", message = "format 必须为 myshare-inventory-exchange")
    val format: String,
    @field:NotNull(message = "version 不能为空")
    @field:Min(value = 1, message = "version 必须为正整数")
    val version: Int,
    @field:NotBlank(message = "exported_at 不能为空")
    val exportedAt: String,
    @field:Valid
    @field:NotNull(message = "producer 不能为空")
    val producer: ProducerDto,
    val catalogVersion: String? = null,
    @field:Valid
    @field:NotEmpty(message = "records 不能为空")
    @field:Size(max = 1000, message = "records 最多 1000 条")
    val records: List<InventoryRecordRequest>,
)

/**
 * 单条记录(协议 4 Record 字段);field 级约束无法表达「快照必填 snapshot_scope、
 * 奖励不得携带 snapshot_scope、reward_delta count 必须 > 0」这类跨字段规则,
 * 由服务层补充校验。
 */
data class InventoryRecordRequest(
    @field:NotBlank(message = "record_id 不能为空")
    @field:Size(min = 1, max = 128, message = "record_id 长度须在 1..128")
    val recordId: String,
    @field:NotBlank(message = "record_type 不能为空")
    @field:Pattern(regexp = "reward_delta|stock_snapshot", message = "record_type 仅支持 reward_delta 或 stock_snapshot")
    val recordType: String,
    @field:NotBlank(message = "entity_type 不能为空")
    @field:Pattern(regexp = "item|agent", message = "entity_type 仅支持 item 或 agent")
    val entityType: String,
    @field:NotNull(message = "effective_at 不能为空")
    val effectiveAt: String,
    @field:Pattern(regexp = "full|listed", message = "snapshot_scope 仅支持 full 或 listed")
    val snapshotScope: String? = null,
    @field:Valid
    @field:NotEmpty(message = "entries 不能为空")
    val entries: List<InventoryEntryRequest>,
)

/**
 * 记录条目(协议 5 Entry 字段);id 唯一性由服务层校验,count 取值范围由记录类型决定。
 */
data class InventoryEntryRequest(
    @field:NotBlank(message = "id 不能为空")
    @field:Size(max = 128, message = "id 最长 128 个字符")
    val id: String,
    @field:Size(max = 128, message = "name 最长 128 个字符")
    val name: String? = null,
    @field:NotNull(message = "count 不能为空")
    @field:Min(value = 0, message = "count 不能为负")
    @field:Max(value = 2147483647, message = "count 超出协议上限")
    val count: Long,
)
