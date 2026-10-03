package com.lhs.share.openapi

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.config.security.AccessDeniedHandlerImpl
import com.lhs.share.config.security.AuthenticationEntryPointImpl
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.config.security.JwtAuthenticationTokenFilter
import com.lhs.share.config.security.SecurityConfig
import com.lhs.share.hub.controller.calendar.ActivityCalendarController
import com.lhs.share.hub.controller.calendar.ActivityCalendarSuggestionController
import com.lhs.share.hub.controller.calendar.AdminActivityCalendarController
import com.lhs.share.hub.controller.calendar.AdminActivityCalendarSuggestionController
import com.lhs.share.hub.controller.calendar.request.ActivityCalendarSuggestionSubmitRequest
import com.lhs.share.hub.controller.calendar.request.ActivityCalendarWriteRequest
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarSuggestionPage
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarSuggestionResponse
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarSuggestionOriginal
import com.lhs.share.hub.repository.entity.ActivityCalendarSuggestionStatus
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.beta.BetaService
import com.lhs.share.hub.service.calendar.ActivityCalendarService
import com.lhs.share.hub.service.calendar.ActivityCalendarSuggestionService
import com.lhs.share.service.DataTransferService
import com.lhs.share.service.jwt.JwtAuthToken
import com.lhs.share.service.jwt.JwtService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

@ActiveProfiles("test")
@WebMvcTest(
    controllers = [
        ActivityCalendarController::class, AdminActivityCalendarController::class,
        ActivityCalendarSuggestionController::class, AdminActivityCalendarSuggestionController::class,
    ],
)
@EnableConfigurationProperties(ShareProperties::class)
@Import(
    SecurityConfig::class,
    JwtAuthenticationTokenFilter::class,
    AuthenticationHelper::class,
    AuthenticationEntryPointImpl::class,
    AccessDeniedHandlerImpl::class,
    DataTransferService::class,
)
class ActivityCalendarSecurityTest {
    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var service: ActivityCalendarService

    @MockitoBean lateinit var suggestions: ActivityCalendarSuggestionService

    @MockitoBean lateinit var authorization: AdminAuthorizationService

    @MockitoBean lateinit var betaService: BetaService

    @MockitoBean lateinit var recruitmentAccessService: com.lhs.share.hub.service.recruitment.RecruitmentAccessService

    @MockitoBean lateinit var jwtService: JwtService

    @MockitoBean lateinit var stringRedisTemplate: StringRedisTemplate

    @Test
    fun `real security chain rejects anonymous calendar reads management and suggestions`() {
        mvc.perform(get("/v1/activity-calendar")).andExpect(status().isUnauthorized)
        mvc.perform(get("/v1/admin/activity-calendar")).andExpect(status().isUnauthorized)
        mvc.perform(post("/v1/admin/activity-calendar")).andExpect(status().isUnauthorized)
        mvc.perform(put("/v1/admin/activity-calendar/evt_test")).andExpect(status().isUnauthorized)
        mvc.perform(post("/v1/activity-calendar")).andExpect(status().isUnauthorized)
        verifyNoInteractions(service, authorization, stringRedisTemplate)
    }

    @Test
    fun `ordinary users cannot read calendar and administrators can read without calendar write permission`() {
        val now = Instant.now()
        val token = JwtAuthToken("tester", "testing-token", now, now.plusSeconds(3600), now, emptyList(), ByteArray(32) { 1 })
        token.isAuthenticated = true
        `when`(jwtService.verifyAndParseAuthToken("testing-token")).thenReturn(token)
        `when`(authorization.hasAnyAdminCapability("tester")).thenReturn(false)
        mvc.perform(get("/v1/activity-calendar").header("Authorization", "Bearer testing-token"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(service)
        `when`(authorization.hasAnyAdminCapability("tester")).thenReturn(true)
        `when`(service.publicItems(null, null, null, null)).thenReturn(ActivityCalendarResponse(emptyList()))
        mvc.perform(get("/v1/activity-calendar").header("Authorization", "Bearer testing-token"))
            .andExpect(status().isOk)
        `when`(authorization.hasPermission("tester", AdminPermission.ACTIVITY_CALENDAR_WRITE)).thenReturn(false)
        mvc.perform(get("/v1/admin/activity-calendar").header("Authorization", "Bearer testing-token"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(stringRedisTemplate)
    }

    @Test
    fun `authenticated caller still needs calendar permission and editor can read management`() {
        val now = Instant.now()
        val token = JwtAuthToken("editor", "synthetic", now, now.plusSeconds(3600), now, emptyList(), ByteArray(32) { 1 })
        token.isAuthenticated = true
        `when`(jwtService.verifyAndParseAuthToken("synthetic")).thenReturn(token)
        `when`(authorization.hasPermission("editor", AdminPermission.ACTIVITY_CALENDAR_WRITE)).thenReturn(false)
        val body = """{"game":"如鸢","title":"活动","category":"ACTIVITY","start_date":"2026-10-03","end_date":"2026-10-10"}"""
        listOf(
            get("/v1/admin/activity-calendar"),
            post("/v1/admin/activity-calendar"),
            put("/v1/admin/activity-calendar/evt_test"),
        ).forEach {
            mvc.perform(it.header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(service)
        `when`(authorization.hasPermission("editor", AdminPermission.ACTIVITY_CALENDAR_WRITE)).thenReturn(true)
        `when`(service.adminItems(null, null, null, null, null, null)).thenReturn(ActivityCalendarAdminResponse(emptyList()))
        mvc.perform(get("/v1/admin/activity-calendar").header("Authorization", "Bearer synthetic")).andExpect(status().isOk)
        verifyNoInteractions(stringRedisTemplate)
    }

    @Test
    fun `suggestion methods require JWT and admin capability while calendar write permission remains independent`() {
        val path = "/v1/activity-calendar/suggestions"
        val admin = "/v1/admin/activity-calendar/suggestions"
        listOf(
            post(path),
            get("$path/mine"),
            get("$path/sug_one"),
            get(admin),
            post("$admin/sug_one/accept"),
            post("$admin/sug_one/reject"),
        ).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(suggestions, authorization)
        val now = Instant.now()
        val token = JwtAuthToken("ordinary", "suggestion-token", now, now.plusSeconds(3600), now, emptyList(), ByteArray(32) { 1 })
        token.isAuthenticated = true
        `when`(jwtService.verifyAndParseAuthToken("suggestion-token")).thenReturn(token)
        `when`(suggestions.mine("ordinary", null, 1, 20)).thenReturn(ActivityCalendarSuggestionPage(emptyList(), 0, 1, 20))
        `when`(authorization.hasAnyAdminCapability("ordinary")).thenReturn(false)
        listOf(get("$path/mine"), get("$path/sug_one")).forEach {
            mvc.perform(it.header("Authorization", "Bearer suggestion-token")).andExpect(status().isForbidden)
        }
        verifyNoInteractions(suggestions)
        `when`(authorization.hasAnyAdminCapability("ordinary")).thenReturn(true)
        mvc.perform(get("$path/mine").header("Authorization", "Bearer suggestion-token")).andExpect(status().isOk)
        val day = LocalDate.parse("2026-10-03")
        val event =
            ActivityCalendarWriteRequest("如鸢", "活动", ActivityCalendarCategory.ACTIVITY, day, day, sourceUrl = "https://example.com/source")
        val request = ActivityCalendarSuggestionSubmitRequest(event, null, "request-one")
        val response = ActivityCalendarSuggestionResponse(
            "sug_one", "ordinary", now,
            ActivityCalendarSuggestionOriginal("如鸢", "活动", ActivityCalendarCategory.ACTIVITY, day, day, sourceUrl = event.sourceUrl!!),
            null, ActivityCalendarSuggestionStatus.PENDING, 0, null, null, null, null, null, null,
        )
        `when`(suggestions.submit("ordinary", request)).thenReturn(response)
        val body = """
            {"game":"如鸢","title":"活动","category":"ACTIVITY",
            "start_date":"2026-10-03","end_date":"2026-10-03","source_url":"https://example.com/source",
            "client_request_id":"request-one"}
        """.trimIndent()
        `when`(authorization.hasAnyAdminCapability("ordinary")).thenReturn(false)
        mvc.perform(post(path).header("Authorization", "Bearer suggestion-token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        `when`(authorization.hasAnyAdminCapability("ordinary")).thenReturn(true)
        mvc.perform(post(path).header("Authorization", "Bearer suggestion-token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
        verifyNoInteractions(betaService, stringRedisTemplate)
        `when`(authorization.hasPermission("ordinary", AdminPermission.ACTIVITY_CALENDAR_WRITE)).thenReturn(false)
        listOf(get(admin), post("$admin/sug_one/accept"), post("$admin/sug_one/reject")).forEach {
            mvc.perform(it.header("Authorization", "Bearer suggestion-token").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden)
        }
    }
}
