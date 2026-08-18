package com.lhs.share.hub.repository

import com.lhs.share.hub.repository.entity.OperatorCurrent
import org.springframework.data.mongodb.repository.MongoRepository

interface OperatorCurrentRepository : MongoRepository<OperatorCurrent, String> {
    fun findByUserIdAndAccountIdAndGame(userId: String, accountId: String, game: String): OperatorCurrent?
    fun findByUserIdAndAccountIdOrderByUpdatedAtDesc(userId: String, accountId: String): List<OperatorCurrent>
    fun deleteAllByUserIdAndAccountId(userId: String, accountId: String)
    fun deleteByUserIdAndAccountIdAndGame(userId: String, accountId: String, game: String)
}
