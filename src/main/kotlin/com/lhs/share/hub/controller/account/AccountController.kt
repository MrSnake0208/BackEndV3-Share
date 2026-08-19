package com.lhs.share.hub.controller.account

import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.account.request.AccountRequest
import com.lhs.share.hub.controller.account.response.SubAccountResponse
import com.lhs.share.hub.service.account.SubAccountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 统一子账号 CRUD(库存 × 密探共用)
 *
 * 替代原 /v1/inventory/accounts 与 /v1/operator/accounts:一个子账号全局可用。
 * 删除 = 整账号级联(库存、密探、特别关注数据 + 全部绑定的 token)。
 */
@Tag(name = "子账号", description = "库存 × 密探共用的统一子账号(HubBackend)")
@RequestMapping("/v1/accounts", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class AccountController(
    private val accountService: SubAccountService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "创建子账号")
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@Valid @RequestBody request: AccountRequest): ApiResult<SubAccountResponse> =
        success(accountService.create(helper.requireUserId(), request.name))

    @Operation(summary = "子账号列表")
    @GetMapping
    fun list(): ApiResult<List<SubAccountResponse>> = success(accountService.list(helper.requireUserId()))

    @Operation(summary = "修改子账号名称")
    @PatchMapping("/{accountId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun rename(@PathVariable accountId: String, @Valid @RequestBody request: AccountRequest): ApiResult<SubAccountResponse> =
        success(accountService.rename(helper.requireUserId(), accountId, request.name))

    @Operation(summary = "删除子账号及其全部数据")
    @DeleteMapping("/{accountId}")
    fun delete(@PathVariable accountId: String): ApiResult<Boolean> {
        accountService.delete(helper.requireUserId(), accountId)
        return success(true)
    }
}
