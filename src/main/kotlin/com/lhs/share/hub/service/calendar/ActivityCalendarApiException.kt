package com.lhs.share.hub.service.calendar

import org.springframework.http.HttpStatus

class ActivityCalendarApiException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)

fun calendarInvalid(message: String) = ActivityCalendarApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", message)

fun calendarConflict() = ActivityCalendarApiException(HttpStatus.CONFLICT, "activity_calendar_version_conflict", "活动已被修改，请刷新后重试")
