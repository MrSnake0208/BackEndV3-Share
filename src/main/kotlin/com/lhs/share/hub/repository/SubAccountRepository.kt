package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.SubAccount
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * 统一子账号仓储(HubBackend.sub_accounts)
 *
 * 由 HubMongoConfig 路由到 hubMongoTemplate。铁律:本接口必须位于
 * com.lhs.share.hub.repository 顶层包,否则不被扫描而落入主库(MaaBackend)。
 */
interface SubAccountRepository : MongoRepository<SubAccount, String> {
    fun countByUserId(userId: String): Long

    fun findByUserIdAndAccountId(userId: String, accountId: String): SubAccount?

    fun findAllByUserIdOrderByCreatedAtAsc(userId: String): List<SubAccount>

    fun findAllByUserIdAndAccountIdIn(userId: String, accountIds: Collection<String>): List<SubAccount>
}
