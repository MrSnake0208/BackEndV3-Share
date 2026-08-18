package com.lhs.share.hub.controller.operator

import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.operator.request.OperatorAccountRequest
import com.lhs.share.hub.controller.operator.request.OperatorImportRequest
import com.lhs.share.hub.controller.operator.request.OperatorCatalogWriteRequest
import com.lhs.share.hub.repository.entity.OperatorCatalogEntity
import com.lhs.share.hub.service.operator.OperatorApiException
import com.lhs.share.service.UserService
import com.lhs.share.hub.controller.operator.response.*
import com.lhs.share.hub.service.operator.OperatorAccountService
import com.lhs.share.hub.service.operator.OperatorCatalogService
import com.lhs.share.hub.service.operator.OperatorService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

@RestController
@RequestMapping("/v1/operator", produces = [MediaType.APPLICATION_JSON_VALUE])
class OperatorController(
    private val service: OperatorService,
    private val accountService: OperatorAccountService,
    private val catalogService: OperatorCatalogService,
    private val helper: AuthenticationHelper,
    private val userService: UserService,
) {
    @PostMapping("/accounts") fun create(@Valid @RequestBody request: OperatorAccountRequest): ApiResult<OperatorAccountResponse> = success(accountService.create(helper.requireUserId(), request.name))
    @GetMapping("/accounts") fun accounts(): ApiResult<List<OperatorAccountResponse>> = success(accountService.list(helper.requireUserId()))
    @PatchMapping("/accounts/{accountId}") fun rename(@PathVariable accountId: String, @Valid @RequestBody request: OperatorAccountRequest): ApiResult<OperatorAccountResponse> = success(accountService.rename(helper.requireUserId(), accountId, request.name))
    @DeleteMapping("/accounts/{accountId}") fun deleteAccount(@PathVariable accountId: String): ApiResult<Boolean> { accountService.delete(helper.requireUserId(), accountId); return success(true) }
    @PostMapping("/import") fun import(@Valid @RequestBody request: OperatorImportRequest): ApiResult<OperatorImportResult> = success(service.import(helper.requireUserId(), request))
    @GetMapping("/current") fun current(@RequestParam(name="account_id") accountId: String, @RequestParam(required=false) game: String?): ApiResult<List<OperatorCurrentResponse>> = success(service.current(helper.requireUserId(), accountId, game))
    @GetMapping("/records") fun records(@RequestParam(name="account_id") accountId: String, @RequestParam(required=false) game: String?, @RequestParam(required=false) from: OffsetDateTime?, @RequestParam(required=false) to: OffsetDateTime?, @RequestParam(required=false) cursor: String?, @RequestParam(defaultValue="50") limit: Int): ApiResult<OperatorRecordPageResponse> = success(service.listRecords(helper.requireUserId(), accountId, game, from?.toInstant(), to?.toInstant(), cursor, limit))
    @DeleteMapping("/records/{recordId}") fun deleteRecord(@PathVariable recordId: String, @RequestParam(name="account_id") accountId: String): ApiResult<Boolean> { service.deleteRecord(helper.requireUserId(), accountId, recordId); return success(true) }
    @GetMapping("/export") fun export(@RequestParam(name="account_id",required=false) accountId: String?, @RequestParam(required=false) scope: String?): OperatorExportResponse = service.export(helper.requireUserId(), accountId, scope)
    @GetMapping("/catalog") fun catalog(): ApiResult<OperatorCatalogResponse> = success(catalogService.catalog())

    @PostMapping("/catalog/operators")
    fun createCatalogOperator(
        @Valid @RequestBody request: OperatorCatalogWriteRequest,
    ): ApiResult<OperatorCatalogEntity> {
        requireAdmin()
        return success(catalogService.create(request))
    }

    @PutMapping("/catalog/operators/{operatorId}")
    fun updateCatalogOperator(
        @PathVariable operatorId: String,
        @Valid @RequestBody request: OperatorCatalogWriteRequest,
    ): ApiResult<OperatorCatalogEntity> {
        requireAdmin()
        return success(catalogService.update(operatorId, request))
    }

    @DeleteMapping("/catalog/operators/{operatorId}")
    fun deleteCatalogOperator(@PathVariable operatorId: String): ApiResult<Boolean> {
        requireAdmin()
        catalogService.delete(operatorId)
        return success(true)
    }

    private fun requireAdmin() {
        if (!userService.hasAdminPrivileges(helper.requireUserId())) {
            throw OperatorApiException(HttpStatus.FORBIDDEN, "forbidden", "Administrator privileges are required")
        }
    }
}
