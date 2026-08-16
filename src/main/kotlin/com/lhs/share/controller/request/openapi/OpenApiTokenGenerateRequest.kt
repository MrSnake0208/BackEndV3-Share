package com.lhs.share.controller.request.openapi

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

/**
 * 生成第三方 API Token 请求
 */
data class OpenApiTokenGenerateRequest(
    /**
     * Public stable scope keys. Integer permission codes remain an internal detail.
     */
    @field:NotEmpty(message = "scopes 不能为空")
    @field:ArraySchema(
        minItems = 1,
        uniqueItems = true,
        schema = Schema(allowableValues = ["inventory:read", "inventory:write", "inventory:export"]),
    )
    val scopes: List<String>,

    /**
     * 备注,可空
     */
    val remark: String?,
)
