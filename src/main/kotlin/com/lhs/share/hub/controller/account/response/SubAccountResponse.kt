package com.lhs.share.hub.controller.account.response

import com.lhs.share.hub.repository.entity.SubAccount
import java.time.Instant

/**
 * 统一子账号响应(与旧 Inventory/Operator 账号响应同构,id = accountId)
 */
data class SubAccountResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(account: SubAccount) = SubAccountResponse(
            id = account.accountId,
            name = account.name,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }
}
