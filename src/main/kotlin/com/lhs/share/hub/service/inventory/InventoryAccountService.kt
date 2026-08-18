package com.lhs.share.hub.service.inventory

import com.lhs.share.hub.controller.inventory.response.InventoryAccountResponse
import com.lhs.share.hub.repository.InventoryAccountRepository
import com.lhs.share.hub.repository.InventoryAgentFavoriteRepository
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.entity.InventoryAccount
import com.lhs.share.openapi.OpenApiTokenService
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class InventoryAccountService(
    private val accountRepository: InventoryAccountRepository,
    private val currentRepository: InventoryCurrentRepository,
    private val recordRepository: InventoryRecordRepository,
    private val favoriteRepository: InventoryAgentFavoriteRepository,
    private val tokenService: OpenApiTokenService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun create(userId: String, name: String): InventoryAccountResponse {
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw InventoryApiException(
                HttpStatus.CONFLICT,
                "account_limit_reached",
                "Inventory account limit reached (maximum $MAX_ACCOUNTS_PER_USER)",
            )
        }
        val now = Instant.now()
        return try {
            InventoryAccountResponse.of(
                accountRepository.save(
                    InventoryAccount(
                        userId = userId,
                        accountId = "acc_${UUID.randomUUID().toString().replace("-", "")}",
                        name = name,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
        } catch (e: DuplicateKeyException) {
            throw nameConflict()
        }
    }

    fun list(userId: String): List<InventoryAccountResponse> =
        accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId).map(InventoryAccountResponse::of)

    fun rename(userId: String, accountId: String, name: String): InventoryAccountResponse {
        val account = requireAccount(userId, accountId)
        return try {
            InventoryAccountResponse.of(accountRepository.save(account.copy(name = name, updatedAt = Instant.now())))
        } catch (e: DuplicateKeyException) {
            throw nameConflict()
        }
    }

    fun delete(userId: String, accountId: String) {
        val account = requireAccount(userId, accountId)
        transactionTemplate.executeWithoutResult {
            currentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            recordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            favoriteRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            tokenService.revokeByAccount(userId, accountId)
            accountRepository.deleteById(checkNotNull(account.id))
        }
    }

    fun requireAccount(userId: String, accountId: String): InventoryAccount = accountRepository.findByUserIdAndAccountId(userId, accountId)
        ?: throw InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun nameConflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "account_name_conflict",
        "An inventory account with this name already exists",
    )

    companion object {
        const val MAX_ACCOUNTS_PER_USER = 10
    }
}
