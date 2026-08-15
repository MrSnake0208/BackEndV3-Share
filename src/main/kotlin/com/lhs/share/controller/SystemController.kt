package com.lhs.share.controller

import com.lhs.share.config.external.ShareProperties
import com.lhs.share.controller.response.ApiResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.GitProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 系统管理接口
 */
@Tag(name = "System", description = "系统管理接口")
@RequestMapping("")
@RestController
class SystemController(
    private val properties: ShareProperties,
    private val gitPropertiesProvider: ObjectProvider<GitProperties>,
) {
    /**
     * 测试服务是否就绪
     */
    @Operation(summary = "测试服务是否就绪")
    @GetMapping("/")
    fun test(): ApiResult<Nothing> = ApiResult.success("Share Server is Running", null)

    /**
     * 获取当前版本信息
     */
    @Operation(summary = "获取当前版本信息")
    @GetMapping("/version")
    fun getSystemVersion(): ApiResult<SystemInfo> {
        val info = properties.info
        val systemInfo = SystemInfo(info.title, info.description, info.version, gitPropertiesProvider.getIfAvailable())
        return ApiResult.success(systemInfo)
    }

    /**
     * 系统信息
     */
    data class SystemInfo(
        val title: String,
        val description: String,
        val version: String,
        val git: GitProperties?,
    )
}
