package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.CalendarProgressRequest
import com.lhs.share.hub.controller.calendar.request.CalendarSubscribeRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarItem
import com.lhs.share.hub.controller.calendar.response.CalendarSubscriptionResponse
import com.lhs.share.hub.controller.calendar.response.CalendarSubscriptionSummaryResponse
import com.lhs.share.hub.controller.calendar.response.CalendarSubscriptionsResponse
import com.lhs.share.hub.repository.ActivityCalendarSubscriptionRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarSubscription
import com.lhs.share.hub.repository.entity.SubAccount
import com.lhs.share.hub.service.recruitment.RecruitmentService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ActivityCalendarSubscriptionService(
    private val repository: ActivityCalendarSubscriptionRepository,
    private val accounts: SubAccountRepository,
    private val calendar: ActivityCalendarService,
    @param:Qualifier("hubTransactionTemplate") private val transaction: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), zone)

    fun list(userId: String, accountId: String, from: LocalDate?, to: LocalDate?, category: String?): CalendarSubscriptionsResponse {
        val account = account(userId, accountId)
        val start = from ?: today()
        val end = to ?: start.plusDays(90)
        if (end < start || ChronoUnit.DAYS.between(start, end) > 100) throw calendarInvalid("日期范围须在100天以内")
        val selected = category?.let { value ->
            ActivityCalendarCategory.entries.find { it.name == value } ?: throw calendarInvalid("不支持的活动类别")
        }
        val records = resolved(userId, account)
        return CalendarSubscriptionsResponse(
            records.filter {
                it.item != null && it.item.endDate >= start && it.item.startDate <= end &&
                    (selected == null || it.item.category == selected)
            },
            records.filter { it.item == null },
            records.size,
        )
    }

    fun summary(userId: String, accountId: String): CalendarSubscriptionSummaryResponse {
        val records = resolved(userId, account(userId, accountId))
        val now = clock.instant()
        val day = today()
        val pending = records.filter { record ->
            val item = record.item
            item != null && !record.completed && started(item, now, day) && !ended(item, now, day) &&
                (item.endAt?.let { it <= now.plus(7, ChronoUnit.DAYS) } ?: (item.endDate <= day.plusDays(7)))
        }.sortedWith(
            compareBy<CalendarSubscriptionResponse> {
                it.item!!.endAt ?: it.item.endDate.plusDays(1).atStartOfDay(zone).toInstant()
            }.thenBy { it.eventId },
        )
        return CalendarSubscriptionSummaryResponse(pending.take(3), pending.size, records.size)
    }

    fun get(userId: String, accountId: String, eventId: String): CalendarSubscriptionResponse {
        val owner = account(userId, accountId)
        return response(find(userId, accountId, eventId), sources(owner)[eventId])
    }

    fun subscribe(userId: String, eventId: String, request: CalendarSubscribeRequest): CalendarSubscriptionResponse = write {
        val owner = account(userId, request.accountId)
        fence(owner)
        val current = repository.find(userId, owner.accountId, eventId)
        if (current?.version != request.expectedVersion || (current == null && !request.subscribed)) throw calendarConflict()
        val item = sources(owner)[eventId]
        if (request.subscribed &&
            (item == null || ended(item, clock.instant(), today()) || (current != null && current.game != item.game))
        ) {
            throw calendarInvalid("该活动已截止、停用或与当前账号游戏不符，无法订阅")
        }
        val now = clock.instant()
        val next = current?.copy(subscribed = request.subscribed, updatedAt = now) ?: ActivityCalendarSubscription(
            id = "acs_${UUID.randomUUID()}",
            userId = userId,
            accountId = owner.accountId,
            eventId = eventId,
            game = owner.game,
            createdAt = now,
            updatedAt = now,
        )
        response(repository.save(next), item)
    }

    fun progress(userId: String, eventId: String, request: CalendarProgressRequest): CalendarSubscriptionResponse = write {
        val owner = account(userId, request.accountId)
        fence(owner)
        val current = find(userId, owner.accountId, eventId)
        if (current.version != request.expectedVersion) throw calendarConflict()
        val item = sources(owner)[eventId]
        if (!current.subscribed || item == null || current.game != item.game) throw calendarInvalid("该订阅当前不可编辑")
        val entries = request.checklist.map { it.copy(title = it.title.trim()) }
        if (entries.size > 50 || entries.any { it.title.length !in 1..80 || !it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ||
            entries.map { it.id }.distinct().size != entries.size || (entries.isNotEmpty() && request.completed)
        ) {
            throw calendarInvalid("关卡名称、标识或完成状态不正确")
        }
        val completed = entries.isEmpty() && request.completed
        response(repository.save(current.copy(checklist = entries, completed = completed, updatedAt = clock.instant())), item)
    }

    private fun sources(account: SubAccount): Map<String, ActivityCalendarItem> =
        calendar.publicItems(account.game, null, null, null).items.associateBy { it.id }

    private fun resolved(userId: String, account: SubAccount): List<CalendarSubscriptionResponse> {
        val records = repository.list(userId, account.accountId)
        if (records.isEmpty()) return emptyList()
        val items = sources(account)
        return records.map { response(it, items[it.eventId]) }.sortedBy { it.eventId }
    }

    private fun response(record: ActivityCalendarSubscription, item: ActivityCalendarItem?) = CalendarSubscriptionResponse(
        record.eventId,
        checkNotNull(record.version),
        record.subscribed,
        record.isCompleted(),
        record.checklist,
        item?.takeIf { it.game == record.game },
    )

    private fun account(userId: String, accountId: String): SubAccount = accounts.findByUserIdAndAccountId(userId, accountId)
        ?: throw ActivityCalendarApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")

    private fun find(userId: String, accountId: String, eventId: String): ActivityCalendarSubscription =
        repository.find(userId, accountId, eventId)
            ?: throw ActivityCalendarApiException(HttpStatus.NOT_FOUND, "subscription_not_found", "订阅不存在")

    private fun fence(owner: SubAccount) {
        if (!accounts.fenceRecruitmentWrite(owner.userId, owner.accountId, owner.game)) throw calendarConflict()
    }

    private fun <T : Any> write(action: () -> T): T = try {
        checkNotNull(transaction.execute { action() })
    } catch (_: DuplicateKeyException) {
        throw calendarConflict()
    } catch (_: OptimisticLockingFailureException) {
        throw calendarConflict()
    } catch (e: RuntimeException) {
        if (RecruitmentService.isWriteConflict(e)) throw calendarConflict()
        throw e
    }

    private fun started(item: ActivityCalendarItem, now: Instant, day: LocalDate): Boolean =
        item.startAt?.let { it <= now } ?: (item.startDate <= day)

    private fun ended(item: ActivityCalendarItem, now: Instant, day: LocalDate): Boolean =
        item.endAt?.let { it <= now } ?: (item.endDate < day)
}
