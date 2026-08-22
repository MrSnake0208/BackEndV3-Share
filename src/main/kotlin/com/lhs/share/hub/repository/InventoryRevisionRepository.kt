package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.InventoryRevision
import org.springframework.data.mongodb.repository.MongoRepository

interface InventoryRevisionRepository : MongoRepository<InventoryRevision, String> {
    fun findByUserIdAndAccountId(userId: String, accountId: String): InventoryRevision?
    fun deleteByUserIdAndAccountId(userId: String, accountId: String): Long
}
