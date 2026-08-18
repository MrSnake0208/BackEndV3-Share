package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.OperatorAccount
import org.springframework.data.mongodb.repository.MongoRepository

interface OperatorAccountRepository : MongoRepository<OperatorAccount, String> {
    fun countByUserId(userId: String): Long
    fun findByUserIdAndAccountId(userId: String, accountId: String): OperatorAccount?
    fun findAllByUserIdOrderByCreatedAtAsc(userId: String): List<OperatorAccount>
    fun findAllByUserIdAndAccountIdIn(userId: String, accountIds: Collection<String>): List<OperatorAccount>
}
