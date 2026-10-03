package com.lhs.share.hub.controller.calendar

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.calendar.request.ActivityCalendarRequestDecoder
import com.lhs.share.hub.controller.calendar.request.ActivityCalendarWriteRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminItem
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminResponse
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.calendar.ActivityCalendarApiException
import com.lhs.share.hub.service.calendar.ActivityCalendarService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Activity Calendar Admin", description = "手工活动维护；招募派生项只读")
@RestController
@RequireJwt
@RequestMapping("/v1/admin/activity-calendar", produces = [MediaType.APPLICATION_JSON_VALUE])
class AdminActivityCalendarController(
    private val service: ActivityCalendarService,
    private val helper: AuthenticationHelper,
    private val authorization: AdminAuthorizationService,
    mapper: ObjectMapper,
) {
    private val decoder = ActivityCalendarRequestDecoder(mapper)

    @Operation(summary = "查询活动目录，含停用手工活动及只读招募来源")
    @GetMapping
    fun list(
        @RequestParam(required = false) game: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) category: List<String>?,
        @RequestParam(required = false) enabled: Boolean?,
        @RequestParam(required = false) search: String?,
    ): ApiResult<ActivityCalendarAdminResponse> {
        requireEditor()
        return success(service.adminItems(game, from, to, category, enabled, search))
    }

    @Operation(
        summary = "创建手工活动",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = ActivityCalendarWriteRequest::class))],
        ),
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@RequestBody body: JsonNode): ApiResult<ActivityCalendarAdminItem> =
        success(service.create(requireEditor(), decoder.read(body)))

    @Operation(
        summary = "按expected_version更新或停用手工活动",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(schema = Schema(implementation = ActivityCalendarWriteRequest::class))],
        ),
    )
    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(@PathVariable id: String, @RequestBody body: JsonNode): ApiResult<ActivityCalendarAdminItem> =
        success(service.update(requireEditor(), id, decoder.read(body)))

    private fun requireEditor(): String {
        val actor = helper.requireUserId()
        if (!authorization.hasPermission(actor, AdminPermission.ACTIVITY_CALENDAR_WRITE)) {
            throw ActivityCalendarApiException(HttpStatus.FORBIDDEN, "forbidden", "需要活动日历维护权限")
        }
        return actor
    }
}
