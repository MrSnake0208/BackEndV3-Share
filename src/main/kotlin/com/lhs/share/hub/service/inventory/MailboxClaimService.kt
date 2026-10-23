package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.hub.controller.inventory.request.InventoryEntryRequest
import com.lhs.share.hub.controller.inventory.request.InventoryImportRequest
import com.lhs.share.hub.controller.inventory.request.InventoryRecordRequest
import com.lhs.share.hub.controller.inventory.request.ProducerDto
import com.lhs.share.hub.controller.inventory.response.MailboxReserveResponse
import com.lhs.share.hub.repository.InventoryDeletedRecordRepository
import com.lhs.share.hub.repository.InventoryRecordRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.MailboxReserve
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class MailboxClaimService(
    private val accounts: SubAccountRepository,
    private val records: InventoryRecordRepository,
    private val deleted: InventoryDeletedRecordRepository,
    private val inventory: InventoryService,
    @param:Qualifier("hubTransactionTemplate") private val transactions: TransactionTemplate,
) {
    fun claim(userId: String, accountId: String, body: JsonNode): MailboxReserveResponse {
        val version = body.path("expected_version")
        val operation = body.path("operation_id")
        if (!body.isObject || body.fieldNames().asSequence().toSet() != setOf("expected_version", "operation_id") ||
            !version.isIntegralNumber || !version.canConvertToLong() || version.longValue() !in 0L until 9007199254740991L ||
            !operation.isTextual || !Regex("[A-Za-z0-9._-]{1,64}").matches(operation.textValue())
        ) {
            invalid("邮件领取参数无效，请重新读取储备")
        }
        val recordId = "yuanhub:mail:${version.longValue()}:${operation.textValue()}"
        return checkNotNull(
            transactions.execute {
                val account = accounts.findByUserIdAndAccountId(userId, accountId)
                    ?: throw InventoryApiException(HttpStatus.NOT_FOUND, "account_not_found", "游戏账号不存在")
                val reserve = account.mailboxReserve ?: MailboxReserve()
                if (records.findByUserIdAndAccountIdAndRecordId(userId, accountId, recordId) != null) {
                    return@execute MailboxReserveResponse.of(accountId, reserve)
                }
                if (deleted.findByUserIdAndAccountIdAndRecordId(userId, accountId, recordId) != null) {
                    conflict("领取记录已删除，请到最近记录恢复，不要重新领取")
                }
                if (reserve.version != version.longValue()) conflict("邮件储备已变更，请重新读取后核对")
                val coins = reserve.whiteCoinMails.sumOf { it.count.toLong() * it.coinsPerMail }
                val tickets = reserve.fuChuanMailCount.toLong() * 3
                if (coins > Int.MAX_VALUE || tickets > Int.MAX_VALUE) invalid("邮件奖励超过支持上限，请分批维护储备")
                val entries = buildList {
                    if (coins > 0) add(InventoryEntryRequest("baijinbi", "白金币", coins))
                    if (tickets > 0) add(InventoryEntryRequest("fuchuan", "符传", tickets))
                }
                if (entries.isEmpty()) invalid("没有可记录领取的邮件奖励")
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val cleared = reserve.copy(
                    whiteCoinMails = reserve.whiteCoinMails.map { it.copy(count = 0) },
                    fuChuanMailCount = 0,
                    version = reserve.version + 1,
                    updatedAt = now,
                )
                val saved = accounts.replaceMailboxReserve(userId, accountId, reserve.version, cleared)
                    ?: conflict("邮件储备已变更或账号已删除，请重新读取")
                inventory.import(
                    userId,
                    accountId,
                    InventoryImportRequest(
                        format = "myshare-inventory-exchange",
                        version = 2,
                        exportedAt = now.toString(),
                        producer = ProducerDto("yuanhub"),
                        records = listOf(
                            InventoryRecordRequest(
                                accountId = accountId,
                                recordId = recordId,
                                recordType = "reward_delta",
                                entityType = "item",
                                acquisitionChannel = "邮件领取",
                                effectiveAt = now.toString(),
                                entries = entries,
                            ),
                        ),
                    ),
                )
                MailboxReserveResponse.of(accountId, checkNotNull(saved.mailboxReserve))
            },
        )
    }

    private fun invalid(message: String): Nothing =
        throw InventoryApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", message)

    private fun conflict(message: String): Nothing = throw InventoryApiException(HttpStatus.CONFLICT, "mailbox_claim_conflict", message)
}
