package com.lhs.share.handler

import com.lhs.share.hub.controller.star.StarCaptureController
import com.lhs.share.hub.service.inventory.InventoryApiException
import com.lhs.share.openapi.OpenApiStarCaptureController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

class InventoryExceptionHandlerTest {
    @Test
    fun `star capture controllers use the inventory error advice`() {
        val advice = InventoryExceptionHandler::class.java.getAnnotation(RestControllerAdvice::class.java)

        assertTrue(StarCaptureController::class in advice.assignableTypes)
        assertTrue(OpenApiStarCaptureController::class in advice.assignableTypes)

        val response = InventoryExceptionHandler().inventoryException(
            InventoryApiException(HttpStatus.NOT_FOUND, "star_capture_not_found", "星石采集不存在"),
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("star_capture_not_found", response.body?.error?.code)
    }
}
