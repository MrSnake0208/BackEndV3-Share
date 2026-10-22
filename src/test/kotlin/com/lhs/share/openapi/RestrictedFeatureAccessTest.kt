package com.lhs.share.openapi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.config.security.RestrictedFeatureInterceptor
import com.lhs.share.handler.InventoryExceptionHandler
import com.lhs.share.handler.OperatorExceptionHandler
import com.lhs.share.hub.controller.achievement.AchievementCatalogAdminController
import com.lhs.share.hub.controller.achievement.AchievementController
import com.lhs.share.hub.controller.achievement.response.AchievementCatalogResponse
import com.lhs.share.hub.controller.bond.BondCatalogAdminController
import com.lhs.share.hub.controller.bond.BondController
import com.lhs.share.hub.controller.operator.OperatorPanelController
import com.lhs.share.hub.controller.operator.response.OperatorPanelResponse
import com.lhs.share.hub.repository.entity.BondPublication
import com.lhs.share.hub.service.achievement.AchievementCatalogService
import com.lhs.share.hub.service.achievement.AchievementService
import com.lhs.share.hub.service.admin.AdminAuthorizationService
import com.lhs.share.hub.service.admin.AdminPermission
import com.lhs.share.hub.service.bond.BondCatalogService
import com.lhs.share.hub.service.bond.BondService
import com.lhs.share.hub.service.operator.OperatorPanelService
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RestrictedFeatureAccessTest {
    private val helper = mockk<AuthenticationHelper>()
    private val authorization = mockk<AdminAuthorizationService>()
    private val achievements = mockk<AchievementService>()
    private val achievementCatalog = mockk<AchievementCatalogService>()
    private val bonds = mockk<BondService>()
    private val bondCatalog = mockk<BondCatalogService>()
    private val panels = mockk<OperatorPanelService>()
    private val mapper = jacksonObjectMapper()
    private val mvc = MockMvcBuilders.standaloneSetup(
        AchievementController(achievements, achievementCatalog, helper, mapper),
        BondController(bonds, bondCatalog, helper, mapper),
        AchievementCatalogAdminController(mockk(), authorization, helper, mapper),
        BondCatalogAdminController(bondCatalog, authorization, helper, mapper),
        OperatorPanelController(panels, helper),
    ).addInterceptors(RestrictedFeatureInterceptor(authorization, helper))
        .setControllerAdvice(InventoryExceptionHandler(), OperatorExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()

    @Test
    fun `anonymous and ungranted users cannot read write import or calculate`() {
        for (user in listOf(null, "ordinary")) {
            every { helper.obtainUserId() } returns user
            every { authorization.hasPermission(any(), any()) } returns false
            val expected = if (user == null) 401 else 403
            for (root in listOf("/v1/achievements", "/v1/bonds")) {
                for (suffix in listOf("/catalog?game=如鸢", "/archive?account_id=a", "/export?account_id=a")) {
                    mvc.perform(get(root + suffix)).andExpect(status().`is`(expected))
                }
                for (suffix in listOf("/batch", "/import/preview", "/import/confirm")) {
                    mvc.perform(post(root + suffix).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().`is`(expected))
                }
                mvc.perform(patch("$root/records/id").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().`is`(expected))
            }
            for (path in listOf("/v1/admin/achievement-catalogs", "/v1/admin/bonds/catalog")) {
                mvc.perform(get(path).param("game", "如鸢")).andExpect(status().`is`(expected))
            }
            for (suffix in listOf("preview", "current")) {
                mvc.perform(post("/v1/operator/panel/$suffix").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().`is`(expected))
            }
        }
        verify { listOf(achievements, achievementCatalog, bonds, bondCatalog, panels) wasNot Called }
    }

    @Test
    fun `grants are independent and revocation rejects the next request`() {
        every { helper.obtainUserId() } returns "member"
        every { helper.requireUserId() } returns "member"
        every { authorization.hasPermission("member", any()) } returns false
        every { authorization.hasPermission("member", AdminPermission.ARCHIVE_ACCESS) } returns true
        every { achievementCatalog.catalog("如鸢") } returns
            AchievementCatalogResponse("v", "如鸢", "ready", "fixture", emptyList(), emptyList())
        every { bondCatalog.catalog("如鸢") } returns BondPublication("v", "如鸢")
        mvc.perform(get("/v1/achievements/catalog").param("game", "如鸢")).andExpect(status().isOk)
        mvc.perform(get("/v1/bonds/catalog").param("game", "如鸢")).andExpect(status().isOk)
        mvc.perform(get("/v1/admin/bonds/catalog").param("game", "如鸢")).andExpect(status().isForbidden)
        mvc.perform(post("/v1/operator/panel/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
        every { authorization.hasPermission("member", AdminPermission.ARCHIVE_ACCESS) } returns false
        every { authorization.hasPermission("member", AdminPermission.OPERATOR_PANEL_USE) } returns true
        every { panels.preview("member", any()) } returns OperatorPanelResponse("calculated", 10, 20)
        mvc.perform(post("/v1/operator/panel/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk)
        mvc.perform(get("/v1/achievements/catalog").param("game", "如鸢")).andExpect(status().isForbidden)
    }
}
