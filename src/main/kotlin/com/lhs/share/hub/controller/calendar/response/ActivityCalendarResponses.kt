package com.lhs.share.hub.controller.calendar.response

import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import java.time.Instant
import java.time.LocalDate

enum class ActivityCalendarSourceType { MANUAL, RECRUITMENT_POOL }

data class ActivityCalendarItem(
    val id: String,
    val sourceType: ActivityCalendarSourceType,
    val sourceRef: String?,
    val game: String,
    val title: String,
    val category: ActivityCalendarCategory,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: String? = null,
    val endTime: String? = null,
    val timeZone: String = "Asia/Shanghai",
    val description: String? = null,
    val sourceUrl: String? = null,
)

data class ActivityCalendarResponse(val items: List<ActivityCalendarItem>)

data class ActivityCalendarAdminItem(
    val item: ActivityCalendarItem,
    val readOnly: Boolean,
    val enabled: Boolean,
    val version: Long?,
    val sourceNote: String?,
    val createdBy: String?,
    val createdAt: Instant?,
    val updatedBy: String?,
    val updatedAt: Instant?,
)

data class ActivityCalendarAdminResponse(val items: List<ActivityCalendarAdminItem>)

data class ActivityCalendarError(val code: String, val message: String)

data class ActivityCalendarErrorResponse(val error: ActivityCalendarError)
