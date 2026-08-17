package com.lhs.share.hub.controller.inventory.response

import com.lhs.share.hub.repository.entity.InventoryAccount
import java.time.Instant

data class InventoryAccountResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(account: InventoryAccount) = InventoryAccountResponse(
            id = account.accountId,
            name = account.name,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }
}
