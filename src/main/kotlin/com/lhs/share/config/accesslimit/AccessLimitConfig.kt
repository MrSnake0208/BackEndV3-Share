package com.lhs.share.config.accesslimit

import com.lhs.share.service.DataTransferService
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 注册 [AccessLimit] 拦截器
 */
@Configuration
class AccessLimitConfig(
    private val stringRedisTemplate: StringRedisTemplate,
    private val dataTransferService: DataTransferService,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AccessLimitInterceptor(stringRedisTemplate, dataTransferService))
    }
}
