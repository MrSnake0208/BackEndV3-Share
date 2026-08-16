package com.lhs.share.hub.controller.inventory.response

import java.time.Instant

/**
 * 导出响应(协议 5.1 交换文档的读侧视图,可直接被其它兼容平台导入)
 *
 * 每条 entity_type 生成一条 full stock_snapshot(当前状态导出);
 * 若 include=rewards,再附带区间内的 reward_delta 流水。
 */
data class InventoryExportResponse(
    val format: String = "myshare-inventory-exchange",
    val version: Int = 1,
    val exportedAt: Instant,
    val catalogVersion: String?,
    val records: List<InventoryExportRecordDto>,
)

/**
 * 导出记录:与导入记录结构一致,方便往返。
 */
data class InventoryExportRecordDto(
    val recordId: String,
    val recordType: String,
    val entityType: String,
    val effectiveAt: Instant,
    val snapshotScope: String?,
    val entries: List<InventoryExportEntryDto>,
)

/**
 * 导出条目
 */
data class InventoryExportEntryDto(
    val id: String,
    val name: String?,
    val count: Long,
)
