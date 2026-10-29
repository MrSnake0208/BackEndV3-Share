package com.lhs.share.hub.service.inventory

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.hub.controller.inventory.response.MailboxReserveResponse
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.MailboxReserve
import com.lhs.share.hub.repository.entity.WhiteCoinMail
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class MailboxReserveService(
    private val accounts: SubAccountService,
    private val repository: SubAccountRepository,
    private val history: MailboxHistoryService,
    @param:Qualifier("hubTransactionTemplate") private val transactions: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun get(userId: String, accountId: String): MailboxReserveResponse = history.current(userId, accountId)

    fun save(userId: String, accountId: String, body: JsonNode): MailboxReserveResponse {
        accounts.requireAccount(userId, accountId)
        fields(body, setOf("expected_version", "white_coin_mails", "fu_chuan_mail_count"))
        val expected = body.path("expected_version")
        if (!expected.isIntegralNumber || !expected.canConvertToLong() || expected.longValue() !in 0L until 9007199254740991L) {
            invalid("储备版本不正确，请重新读取")
        }
        val rows = body.path("white_coin_mails")
        if (!rows.isArray || rows.size() > 20) invalid("白金币邮件最多填写20行")
        val mails = rows.map {
            fields(it, setOf("count", "coins_per_mail"))
            WhiteCoinMail(amount(it.path("count")), amount(it.path("coins_per_mail")))
        }
        val tickets = amount(body.path("fu_chuan_mail_count"))
        return checkNotNull(
            transactions.execute {
                val previous = accounts.requireAccount(userId, accountId).mailboxReserve ?: MailboxReserve()
                if (previous.version != expected.longValue()) conflict()
                if (previous.updatedAt != null && previous.whiteCoinMails == mails && previous.fuChuanMailCount == tickets) {
                    return@execute MailboxReserveResponse.of(accountId, previous)
                }
                val now = maxOf(clock.instant().truncatedTo(ChronoUnit.MILLIS), previous.updatedAt ?: java.time.Instant.MIN)
                val reserve = MailboxReserve(mails, tickets, previous.version + 1, now)
                val account = repository.replaceMailboxReserve(userId, accountId, previous.version, reserve) ?: conflict()
                history.record(userId, accountId, previous, reserve, if (previous.updatedAt == null) "baseline" else "adjustment")
                MailboxReserveResponse.of(accountId, checkNotNull(account.mailboxReserve))
            },
        )
    }

    private fun conflict(): Nothing = throw InventoryApiException(HttpStatus.CONFLICT, "mailbox_reserve_conflict", "邮箱储备已变更或账号已删除，请重新读取后核对")

    private fun amount(value: JsonNode): Int {
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.intValue() !in 0..1000000) {
            invalid("封数及每封奖励须为0至1000000的整数")
        }
        return value.intValue()
    }

    private fun fields(value: JsonNode, names: Set<String>) {
        if (!value.isObject || value.fieldNames().asSequence().toSet() != names) invalid("邮箱储备字段不正确")
    }

    private fun invalid(message: String): Nothing =
        throw InventoryApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", message)
}
