package com.lhs.share.openapi

import com.lhs.share.config.doc.RequireOpenApiToken
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.star.request.StarScanReportRequest
import com.lhs.share.hub.service.star.StarScanService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/open-api/star/scans", produces = [MediaType.APPLICATION_JSON_VALUE])
class OpenApiStarScanController(private val tokens: OpenApiTokenService, private val scans: StarScanService) {
    @RequireOpenApiToken
    @GetMapping("/account")
    fun account(@RequestHeader(value = "Authorization", required = false) authorization: String?) =
        tokens.validateAuthorization(authorization, OpenApiPermission.STAR_SCAN_WRITE).let {
            success(scans.account(it.userId, it.accountId))
        }

    @RequireOpenApiToken
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun upload(
        @RequestHeader(value = "Authorization", required = false) authorization: String?,
        @RequestBody report: StarScanReportRequest,
    ) = tokens.validateAuthorization(authorization, OpenApiPermission.STAR_SCAN_WRITE).let {
        success(scans.upload(it.userId, it.accountId, report))
    }
}
