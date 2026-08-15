package com.lhs.share.config.accesslimit

import com.lhs.share.common.utils.IpUtil
import com.lhs.share.controller.response.ApiResult.Companion.fail
import com.lhs.share.service.DataTransferService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.util.concurrent.TimeUnit

/**
 * [AccessLimit] 注解的拦截器实现,基于 Redis 做 IP + 接口维度计数
 */
class AccessLimitInterceptor(
    private val stringRedisTemplate: StringRedisTemplate,
    private val dataTransferService: DataTransferService,
) : HandlerInterceptor {
    private val log = KotlinLogging.logger { }

    @Throws(Exception::class)
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val ann = (handler as? HandlerMethod)?.method?.getAnnotation(AccessLimit::class.java) ?: return true
        // 拼接 redis key = IP + 接口路径
        val key = IpUtil.getIpAddr(request) + request.requestURI

        val count = stringRedisTemplate.opsForValue()[key]?.toInt() ?: 0
        if (count < ann.times) {
            stringRedisTemplate.opsForValue().set(
                key,
                (count + 1).toString(),
                ann.second.toLong(),
                TimeUnit.SECONDS,
            )
        } else {
            log.info { "$key 请求过于频繁" }
            val result = fail(HttpStatus.TOO_MANY_REQUESTS.value(), "请求过于频繁")
            dataTransferService.writeJson(response, result, HttpStatus.TOO_MANY_REQUESTS.value())
            return false
        }

        return true
    }
}
