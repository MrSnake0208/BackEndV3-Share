package com.lhs.share.hub.service.operator

import com.lhs.share.hub.controller.operator.response.OperatorAccountResponse
import com.lhs.share.hub.repository.OperatorAccountRepository
import com.lhs.share.hub.repository.OperatorCurrentRepository
import com.lhs.share.hub.repository.OperatorRecordRepository
import com.lhs.share.hub.repository.entity.OperatorAccount
import com.lhs.share.openapi.OpenApiTokenService
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class OperatorAccountService(
    private val accountRepository: OperatorAccountRepository,
    private val currentRepository: OperatorCurrentRepository,
    private val recordRepository: OperatorRecordRepository,
    private val tokenService: OpenApiTokenService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun create(userId: String, name: String): OperatorAccountResponse {
        if (accountRepository.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) throw OperatorApiException(HttpStatus.TOO_MANY_REQUESTS, "account_limit_reached", "Operator account limit reached")
        val now = Instant.now()
        return try { OperatorAccountResponse.of(accountRepository.save(OperatorAccount(userId = userId, accountId = "acc_" + UUID.randomUUID().toString().replace("-", ""), name = name, createdAt = now, updatedAt = now))) } catch (e: DuplicateKeyException) { throw conflict() }
    }
    fun list(userId: String) = accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId).map(OperatorAccountResponse::of)
    fun rename(userId: String, accountId: String, name: String): OperatorAccountResponse = try { OperatorAccountResponse.of(accountRepository.save(requireAccount(userId, accountId).copy(name = name, updatedAt = Instant.now()))) } catch (e: DuplicateKeyException) { throw conflict() }
    fun requireAccount(userId: String, accountId: String): OperatorAccount = accountRepository.findByUserIdAndAccountId(userId, accountId) ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")
    fun delete(userId: String, accountId: String) {
        val account = requireAccount(userId, accountId)
        transactionTemplate.executeWithoutResult {
            currentRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            recordRepository.deleteAllByUserIdAndAccountId(userId, accountId)
            tokenService.revokeByAccount(userId, accountId, "OPERATOR")
            accountRepository.deleteById(checkNotNull(account.id))
        }
    }
    private fun conflict() = OperatorApiException(HttpStatus.CONFLICT, "account_name_conflict", "An operator account with this name already exists")
    companion object { const val MAX_ACCOUNTS_PER_USER = 10 }
}
