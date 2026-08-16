package com.lhs.share.controller.request.openapi

import jakarta.validation.constraints.NotEmpty

/**
 * 生成第三方 API Token 请求
 */
data class OpenApiTokenGenerateRequest(
    /**
     * 授权范围,存 OpenApiPermission.code
     */
    @field:NotEmpty(message = "scope 不能为空")
    val scope: List<Int>,

    /**
     * 备注,可空
     */
    val remark: String?,
)
