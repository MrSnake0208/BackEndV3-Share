package com.lhs.share.hub.work.controller

import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.work.model.WorkCompatibilityResponse
import com.lhs.share.hub.work.model.WorkDetailResponse
import com.lhs.share.hub.work.model.WorkPageResponse
import com.lhs.share.hub.work.service.WorkApiException
import com.lhs.share.hub.work.service.WorkService
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestController
@RequestMapping("/v1/works", produces = [MediaType.APPLICATION_JSON_VALUE])
class WorkController(private val service: WorkService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "20") limit: Int): ApiResult<WorkPageResponse> =
        success(service.list(page, limit))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResult<WorkDetailResponse> = success(service.get(id))

    @GetMapping("/{id}/compatibility")
    fun compatibility(@PathVariable id: Long, @RequestParam("to") target: String): ApiResult<WorkCompatibilityResponse> =
        success(service.compatibility(id, target))
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [WorkController::class])
class WorkExceptionHandler {
    @ExceptionHandler(WorkApiException::class)
    fun api(e: WorkApiException): ResponseEntity<ApiResult<Nothing>> =
        ResponseEntity.status(e.status).body(ApiResult.fail(e.status.value(), e.message))

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun badRequest(e: Exception): ResponseEntity<ApiResult<Nothing>> = ResponseEntity.badRequest().body(ApiResult.fail(400, e.message))
}
