package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("operator_accounts")
@CompoundIndexes(
    CompoundIndex(name = "idx_op_user_account_unique", def = "{'userId': 1, 'accountId': 1}", unique = true),
    CompoundIndex(name = "idx_op_user_account_name_unique", def = "{'userId': 1, 'name': 1}", unique = true),
)
data class OperatorAccount(
    @Id val id: String? = null,
    val userId: String,
    val accountId: String,
    val name: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
