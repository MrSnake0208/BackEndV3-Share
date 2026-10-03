package com.lhs.share.hub.controller.calendar

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarResponse
import com.lhs.share.hub.service.calendar.ActivityCalendarService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Activity Calendar", description = "公开活动与招募日历")
@RestController
@RequestMapping("/v1/activity-calendar", produces = [MediaType.APPLICATION_JSON_VALUE])
class ActivityCalendarController(private val service: ActivityCalendarService) {
    @Operation(summary = "按闭区间重叠筛选公开活动，招募卡池为只读派生项")
    @GetMapping
    fun list(
        @RequestParam(required = false) game: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) category: List<String>?,
    ): ApiResult<ActivityCalendarResponse> = success(service.publicItems(game, from, to, category))
}
