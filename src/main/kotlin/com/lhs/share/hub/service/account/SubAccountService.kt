package com.lhs.share.hub.service.account

import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.repository.InventoryAgentFavoriteRepository
import com.lhs.share.hub.repository.InventoryCurrentRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.openapi.OpenApiTokenService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * 统一子账号服务(库存 × 密探共用)
 *
 * 一个子账号 = 用户的某个游戏登录账号。删除 = 整账号级联:库存 current/records、
 * 密探 current/records、特别关注、全部绑定的 token 一起删除。
 */
@Service
class SubAccountService(
    private val accountRepository: SubAccountRepository,
    private val inventoryCurrentRepository: InventoryCurrentRepository,
    private val inventoryRecordRepository: InventoryRecordRepository,
    private val favoriteRepository: InventoryAgentFavoriteRepository,
    private val operatorCurrentRepository: OperatorCurrentRepository,
    private val operatorRecordRepository: OperatorRecordRepository,
    private val tokenService: OpenApiTokenService,
    @param:Qualifier("hubTransactionTemplate") private val transactionTemplate: TransactionTemplate,
) {
    fun create(userId: String, name: String): SubAccountResponse {
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw InventoryApiException(
                HttpStatus.CONFLICT,
                "account_limit_reached",
                "Account limit reached (maximum $MAX_ACCOUNTS_PER_USER)",
            )
        }
        val now = Instant.now()
        return try {
            SubAccountResponse.of(
                accountRepository.save(
                    SubAccount(
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

    fun list(userId: String): List<SubAccountResponse> =
        accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId).map(SubAccountResponse::of)

    fun rename(userId: String, accountId: String, name: String): SubAccountResponse {
        val account = requireAccount(userId, accountId)
        return try {
            SubAccountResponse.of(accountRepository.save(account.copy(name = name, updatedAt = Instant.now())))
        } catch (e: DuplicateKeyException) {
            throw nameConflict()
        }
    }

    fun delete(userId: String, accountId: String) {
        val account = requireAccount(userId, accountId)
        transactionTemplate.executeWithoutResult {
            inventoryCurrentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            inventoryRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            favoriteRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorCurrentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            operatorRecordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            tokenService.revokeByAccount(userId, accountId)
            accountRepository.deleteById(checkNotNull(account.id))
        }
    }

    fun requireAccount(userId: String, accountId: String): SubAccount = accountRepository.findByUserIdAndAccountId(userId, accountId)
        ?: throw InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun nameConflict() = InventoryApiException(
        HttpStatus.CONFLICT,
        "account_name_conflict",
        "An account with this name already exists",
    )

    companion object {
        const val MAX_ACCOUNTS_PER_USER = 10
    }
}
