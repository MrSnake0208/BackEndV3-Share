package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.CalendarPrivateItemRequest
import com.lhs.share.hub.controller.calendar.request.CalendarPrivateItemRequestDecoder
import com.lhs.share.hub.controller.calendar.response.CalendarPrivateItemResponse
import com.lhs.share.hub.controller.calendar.response.CalendarPrivateItemsResponse
import com.lhs.share.hub.repository.CalendarPrivateItemRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.CalendarPrivateItem
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.recruitment.RecruitmentService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class CalendarPrivateItemService(
    private val repository: CalendarPrivateItemRepository,
    private val accounts: SubAccountRepository,
    @param:Qualifier("hubTransactionTemplate") private val transaction: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(userId: String, accountId: String, from: LocalDate, to: LocalDate): CalendarPrivateItemsResponse {
        val owner = account(userId, accountId)
        if (from.year !in 1..9999 || to.year !in 1..9999 || to < from || ChronoUnit.DAYS.between(from, to) >= 100) {
            throw calendarInvalid("私人事项日期范围须在100天以内")
        }
        val values = repository.list(userId, accountId, owner.game, from, to)
        if (values.size > 1000) throw calendarInvalid("当前范围私人事项超过1000条，请缩小日期范围")
        return CalendarPrivateItemsResponse(values.map(::response))
    }

    fun get(userId: String, accountId: String, id: String): CalendarPrivateItemResponse = response(find(account(userId, accountId), id))

    fun create(userId: String, request: CalendarPrivateItemRequest): CalendarPrivateItemResponse = write {
        val owner = account(userId, request.accountId)
        fence(owner)
        validate(request)
        if (request.expectedVersion != null) throw calendarInvalid("新建不接受expected_version")
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        response(
            repository.save(
                CalendarPrivateItem(
                    "pci_${UUID.randomUUID()}", userId, owner.accountId, owner.game,
                    request.title.trim(), request.date, request.time, request.note, now, now,
                ),
            ),
        )
    }

    fun update(userId: String, id: String, request: CalendarPrivateItemRequest): CalendarPrivateItemResponse = write {
        val owner = account(userId, request.accountId)
        fence(owner)
        val current = find(owner, id)
        val version = request.expectedVersion ?: throw calendarInvalid("请提供expected_version")
        CalendarPrivateItemRequestDecoder.requireVersion(version)
        if (current.version != version) throw conflict()
        validate(request)
        response(
            repository.save(
                current.copy(
                    title = request.title.trim(),
                    date = request.date,
                    time = request.time,
                    note = request.note,
                    updatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS),
                ),
            ),
        )
    }

    fun delete(userId: String, accountId: String, id: String, expectedVersion: Long): Boolean = write {
        val owner = account(userId, accountId)
        fence(owner)
        val current = find(owner, id)
        CalendarPrivateItemRequestDecoder.requireVersion(expectedVersion)
        if (current.version != expectedVersion || !repository.delete(userId, accountId, owner.game, id, expectedVersion)) throw conflict()
        true
    }

    private fun account(userId: String, accountId: String): SubAccount = accounts.findByUserIdAndAccountId(userId, accountId)
        ?: throw ActivityCalendarApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun find(owner: SubAccount, id: String): CalendarPrivateItem {
        if (!id.matches(Regex("pci_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) throw notFound()
        return repository.find(owner.userId, owner.accountId, owner.game, id) ?: throw notFound()
    }

    private fun fence(owner: SubAccount) {
        if (!accounts.fenceRecruitmentWrite(owner.userId, owner.accountId, owner.game)) throw conflict()
    }

    private fun validate(request: CalendarPrivateItemRequest) {
        if (request.title.trim().length !in 1..100 || request.note.length > 1000 || request.date.year !in 1..9999 ||
            request.time != null && !request.time.matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))
        ) {
            throw calendarInvalid("标题须为1–100字，说明最多1000字，日期或时间不正确")
        }
    }

    private fun response(value: CalendarPrivateItem) = CalendarPrivateItemResponse(
        value.id, value.accountId, value.game, value.title, value.date, value.time, value.note,
        checkNotNull(value.version), value.createdAt, value.updatedAt,
    )

    private fun <T : Any> write(action: () -> T): T = try {
        checkNotNull(transaction.execute { action() })
    } catch (_: OptimisticLockingFailureException) {
        throw conflict()
    } catch (_: DuplicateKeyException) {
        throw conflict()
    } catch (error: RuntimeException) {
        if (RecruitmentService.isWriteConflict(error)) throw conflict()
        throw error
    }

    private fun notFound() = ActivityCalendarApiException(HttpStatus.NOT_FOUND, "private_item_not_found", "私人事项不存在")
    private fun conflict() = ActivityCalendarApiException(HttpStatus.CONFLICT, "private_item_conflict", "事项或账号已变化，请读取最新内容后重试")
}
