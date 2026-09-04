package com.lhs.share.openapi

import com.lhs.share.hub.controller.operator.response.OperatorShareViewResponse
import com.lhs.share.hub.service.operator.OperatorShareService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import org.mockito.Mockito.`when`

@SpringBootTest(
    properties = [
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/MaaBackend?serverSelectionTimeoutMS=50&connectTimeoutMS=50",
        "spring.data.mongodb.auto-index-creation=false",
    ],
)
@AutoConfigureMockMvc
class OperatorShareSecurityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var shareService: OperatorShareService

    @Test
    fun `only the view path is public`() {
        `when`(shareService.view("code")).thenReturn(
            OperatorShareViewResponse("代号鸢", "2026-09-03", Instant.EPOCH, emptyMap()),
        )

        mockMvc.perform(get("/v1/operator/share/view/code"))
            .andExpect(status().isOk)

        mockMvc.perform(get("/v1/operator/share").param("account_id", "acc_a"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(put("/v1/operator/share").param("account_id", "acc_a"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/v1/operator/share/regenerate").param("account_id", "acc_a"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/v1/operator/share").param("account_id", "acc_a"))
            .andExpect(status().isUnauthorized)
    }
}
