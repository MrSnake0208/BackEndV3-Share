package com.lhs.share.hub.controller.star

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.doc.StarExchangeReplaceResponses
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.star.request.StarExchangeReplaceRequest
import com.lhs.share.hub.controller.star.response.StarExchangeReplaceResponse
import com.lhs.share.hub.service.star.StarExchangeReplaceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "星石数据交换", description = "YuanStar 替换式数据导入")
@RequestMapping("/v1/star-exchange", produces = [MediaType.APPLICATION_JSON_VALUE])
@RestController
class StarExchangeController(
    private val service: StarExchangeReplaceService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "原子替换星石库存和工作区", description = "替换后 account 级 loadout 会被清空；不接收或修改 user-global preset。")
    @StarExchangeReplaceResponses
    @RequireJwt
    @PostMapping("/replace", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun replace(@Valid @RequestBody request: StarExchangeReplaceRequest): ApiResult<StarExchangeReplaceResponse> = success(
        service.replace(helper.requireUserId(), request),
    )
}
