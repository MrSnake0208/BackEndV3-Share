package com.lhs.share.hub.service.calendar

import com.lhs.share.hub.controller.calendar.request.ActivityCalendarWriteRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminItem
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarItem
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarSourceType
import com.lhs.share.hub.repository.ActivityCalendarRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarEvent
import com.lhs.share.hub.service.account.SubAccountService
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class ActivityCalendarService(
    private val repository: ActivityCalendarRepository,
    private val recruitment: RecruitmentCatalogRepository,
) {
    fun publicItems(game: String?, from: LocalDate?, to: LocalDate?, category: List<String>?): ActivityCalendarResponse =
        ActivityCalendarResponse(entries(game, from, to, category, true).map { it.item })

    fun adminItems(
        game: String?,
        from: LocalDate?,
        to: LocalDate?,
        category: List<String>?,
        enabled: Boolean?,
        search: String?,
    ): ActivityCalendarAdminResponse = ActivityCalendarAdminResponse(
        entries(game, from, to, category, enabled).filter {
            search.isNullOrBlank() ||
                it.item.title.contains(search.trim(), ignoreCase = true)
        },
    )

    private fun entries(
        game: String?,
        from: LocalDate?,
        to: LocalDate?,
        category: List<String>?,
        enabled: Boolean?,
    ): List<ActivityCalendarAdminItem> {
        game?.let(::requireGame)
        if (from != null && to != null && from > to) throw calendarInvalid("from不能晚于to")
        val categories = category.orEmpty().flatMap { it.split(',') }.map { value ->
            ActivityCalendarCategory.entries.find { it.name == value.trim() } ?: throw calendarInvalid("不支持的活动类别")
        }.toSet()
        val manual = repository.list(game, from, to, categories, enabled).map(::adminItem)
        val pools = if (categories.isEmpty() || ActivityCalendarCategory.RECRUITMENT in categories) {
            recruitment.all().mapNotNull { pool ->
                val start = pool.startDate ?: return@mapNotNull null
                val end = pool.endDate ?: return@mapNotNull null
                if (!pool.enabled || start > end || enabled == false || (game != null && pool.game != game) ||
                    (from != null && end < from) || (to != null && start > to)
                ) {
                    return@mapNotNull null
                }
                ActivityCalendarAdminItem(
                    item = ActivityCalendarItem(
                        "recruitment:${pool.poolId}", ActivityCalendarSourceType.RECRUITMENT_POOL, pool.poolId,
                        pool.game, pool.name, ActivityCalendarCategory.RECRUITMENT, start, end, sourceUrl = pool.sourceUrl,
                    ),
                    readOnly = true, enabled = true, version = null, sourceNote = pool.sourceNote,
                    createdBy = null, createdAt = null, updatedBy = pool.updatedBy, updatedAt = pool.updatedAt,
                )
            }
        } else {
            emptyList()
        }
        return (manual + pools).sortedWith(
            compareBy<ActivityCalendarAdminItem> { it.item.startDate }.thenBy { it.item.startTime }.thenBy { it.item.id },
        )
    }

    fun create(actor: String, request: ActivityCalendarWriteRequest): ActivityCalendarAdminItem {
        if (request.expectedVersion != null) throw calendarInvalid("新建活动不接受expected_version")
        return adminItem(save(event(actor, "evt_${UUID.randomUUID()}", request, null)))
    }

    fun update(actor: String, id: String, request: ActivityCalendarWriteRequest): ActivityCalendarAdminItem {
        if (id.startsWith("recruitment:")) throw calendarInvalid("招募派生项只读，请在招募卡池管理修改")
        val expected = request.expectedVersion ?: throw calendarInvalid("更新必须提供expected_version")
        if (expected < 0 || expected == Long.MAX_VALUE) throw calendarInvalid("expected_version不正确")
        val current =
            repository.find(id) ?: throw ActivityCalendarApiException(HttpStatus.NOT_FOUND, "activity_calendar_not_found", "活动不存在")
        if (current.version != expected) throw calendarConflict()
        return adminItem(save(event(actor, id, request, current)))
    }

    /** Shares normalization and validation with suggestions without writing a formal event. */
    fun validate(request: ActivityCalendarWriteRequest): ActivityCalendarWriteRequest {
        val validated = event("", "", request, null)
        return request.copy(title = validated.title, sourceUrl = validated.sourceUrl)
    }

    fun findAdminItem(id: String): ActivityCalendarAdminItem? = repository.find(id)?.let(::adminItem)

    private fun event(
        actor: String,
        id: String,
        request: ActivityCalendarWriteRequest,
        current: ActivityCalendarEvent?,
    ): ActivityCalendarEvent {
        requireGame(request.game)
        val title = request.title.trim().takeIf { it.length in 1..120 } ?: throw calendarInvalid("标题须为1至120字")
        if (request.category == ActivityCalendarCategory.RECRUITMENT) throw calendarInvalid("招募活动由卡池目录派生")
        if (request.endDate < request.startDate) throw calendarInvalid("结束日期不能早于开始日期")
        if ((request.startTime == null) != (request.endTime == null)) throw calendarInvalid("开始和结束时间须成对填写")
        for (time in listOfNotNull(request.startTime, request.endTime)) {
            if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)) throw calendarInvalid("时间须为HH:mm")
        }
        if (request.startDate.isEqual(request.endDate) && request.startTime != null && request.endTime!! < request.startTime) {
            throw calendarInvalid("结束时间不能早于开始时间")
        }
        try {
            ZoneId.of(request.timeZone)
        } catch (_: DateTimeException) {
            throw calendarInvalid("时区不正确")
        }
        if ((request.description?.length ?: 0) > 1000 || (request.sourceNote?.length ?: 0) > 1000) throw calendarInvalid("说明或来源备注不能超过1000字")
        val sourceUrl = request.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (sourceUrl != null) {
            val uri = try {
                URI(sourceUrl)
            } catch (_: java.net.URISyntaxException) {
                throw calendarInvalid("来源链接不正确")
            }
            if (sourceUrl.length > 2048 || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw calendarInvalid("来源链接须为http/https地址")
            }
        }
        val now = Instant.now()
        return ActivityCalendarEvent(
            id, request.game, title, request.category, request.startDate, request.endDate,
            request.startTime, request.endTime, request.timeZone, request.description, sourceUrl, request.sourceNote, request.enabled,
            current?.createdBy ?: actor, current?.createdAt ?: now, actor, now, current?.version,
        )
    }

    private fun save(event: ActivityCalendarEvent): ActivityCalendarEvent = try {
        repository.save(event)
    } catch (_: OptimisticLockingFailureException) {
        throw calendarConflict()
    }

    private fun requireGame(game: String) {
        if (game !in SubAccountService.SUPPORTED_GAMES) throw calendarInvalid("游戏只支持代号鸢或如鸢")
    }

    fun adminItem(event: ActivityCalendarEvent) = ActivityCalendarAdminItem(
        ActivityCalendarItem(
            event.id, ActivityCalendarSourceType.MANUAL, null, event.game, event.title, event.category,
            event.startDate, event.endDate, event.startTime, event.endTime, event.timeZone, event.description, event.sourceUrl,
        ),
        false, event.enabled, event.version, event.sourceNote, event.createdBy, event.createdAt, event.updatedBy, event.updatedAt,
    )
}
