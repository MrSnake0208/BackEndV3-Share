package com.lhs.share.openapi

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.config.security.AccessDeniedHandlerImpl
import com.lhs.share.config.security.AuthenticationEntryPointImpl
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.config.security.JwtAuthenticationTokenFilter
import com.lhs.share.config.security.SecurityConfig
import com.lhs.share.hub.controller.calendar.ActivityCalendarController
import com.lhs.share.hub.controller.calendar.AdminActivityCalendarController
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarAdminResponse
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarResponse
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.beta.BetaService
import com.lhs.share.hub.service.calendar.ActivityCalendarService
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

@ActiveProfiles("test")
@WebMvcTest(controllers = [ActivityCalendarController::class, AdminActivityCalendarController::class])
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

    @MockitoBean lateinit var authorization: AdminAuthorizationService

    @MockitoBean lateinit var betaService: BetaService

    @MockitoBean lateinit var recruitmentAccessService: com.lhs.share.hub.service.recruitment.RecruitmentAccessService

    @MockitoBean lateinit var jwtService: JwtService

    @MockitoBean lateinit var stringRedisTemplate: StringRedisTemplate

    @Test
    fun `real security chain allows anonymous public GET and rejects anonymous management or other methods`() {
        `when`(service.publicItems(null, null, null, null)).thenReturn(ActivityCalendarResponse(emptyList()))
        mvc.perform(get("/v1/activity-calendar")).andExpect(status().isOk)
        mvc.perform(get("/v1/admin/activity-calendar")).andExpect(status().isUnauthorized)
        mvc.perform(post("/v1/admin/activity-calendar")).andExpect(status().isUnauthorized)
        mvc.perform(put("/v1/admin/activity-calendar/evt_test")).andExpect(status().isUnauthorized)
        mvc.perform(post("/v1/activity-calendar")).andExpect(status().isUnauthorized)
        verifyNoInteractions(authorization, stringRedisTemplate)
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
}
