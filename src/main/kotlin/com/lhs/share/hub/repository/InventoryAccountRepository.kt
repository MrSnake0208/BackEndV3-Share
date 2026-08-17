package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.InventoryAccount
import org.springframework.data.mongodb.repository.MongoRepository

interface InventoryAccountRepository : MongoRepository<InventoryAccount, String> {
    fun countByUserId(userId: String): Long

    fun findByUserIdAndAccountId(userId: String, accountId: String): InventoryAccount?

    fun findAllByUserIdOrderByCreatedAtAsc(userId: String): List<InventoryAccount>

    fun findAllByUserIdAndAccountIdIn(userId: String, accountIds: Collection<String>): List<InventoryAccount>
}
