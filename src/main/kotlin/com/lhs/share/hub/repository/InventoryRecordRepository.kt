package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.InventoryRecord
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

/**
 * 库存/奖励流水仓储(HubBackend.inventory_records)
 *
 * 由 [com.lhs.share.config.mongo.HubMongoConfig] 路由到 hubMongoTemplate。
 * 铁律:本接口必须位于 com.lhs.share.hub.repository 顶层包,否则不被
 * HubMongoConfig 扫描而落入主库(MaaBackend)。
 *
 * 幂等键为 (userId, recordId),由唯一复合索引 idx_user_record_unique 保证,
 * 实体中因此不单独 @Indexed recordId。时段获得量由服务层通过聚合
 * record_type = "reward_delta" 完成,这里只提供归属安全的读写原语。
 */
interface InventoryRecordRepository : MongoRepository<InventoryRecord, String> {
    /**
     * 按用户 + 幂等 id 查询;不存在时返回 null(幂等校验用)
     */
    fun findByUserIdAndRecordId(userId: String, recordId: String): InventoryRecord?

    /**
     * 按用户查询流水,按生效时间降序(排查导入结果用)
     */
    fun findByUserIdOrderByEffectiveAtDesc(userId: String): List<InventoryRecord>

    /**
     * 按用户查询流水,按生效时间升序(导出用,保证重导入顺序正确)
     */
    fun findByUserIdOrderByEffectiveAtAsc(userId: String): List<InventoryRecord>

    /**
     * 按用户 + 记录类型 + 生效时间区间分页查询(时段统计用,区间 [from, to))
     */
    fun findByUserIdAndRecordTypeAndEffectiveAtGreaterThanEqualAndEffectiveAtLessThan(
        userId: String,
        recordType: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<InventoryRecord>
}
