package com.lhs.share.handler

import com.lhs.share.hub.controller.calendar.ActivityCalendarController
import com.lhs.share.hub.controller.calendar.AdminActivityCalendarController
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarError
import com.lhs.share.hub.controller.calendar.response.ActivityCalendarErrorResponse
import com.lhs.share.hub.service.calendar.ActivityCalendarApiException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [ActivityCalendarController::class, AdminActivityCalendarController::class])
class ActivityCalendarExceptionHandler {
    @ExceptionHandler(ActivityCalendarApiException::class)
    fun api(error: ActivityCalendarApiException) = response(error.status, error.code, error.message)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(error: Exception) = response(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", "请求字段或类型不符合活动日历要求")

    @ExceptionHandler(ResponseStatusException::class)
    fun authentication(error: ResponseStatusException) = response(HttpStatus.valueOf(error.statusCode.value()), "unauthorized", "请先登录")

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<ActivityCalendarErrorResponse> =
        ResponseEntity.status(status).body(ActivityCalendarErrorResponse(ActivityCalendarError(code, message)))
}
