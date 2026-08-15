package com.lhs.share.config.external

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.stereotype.Component

/**
 * 应用自定义配置,前缀 share
 *
 * 对应 application.yml 中的 share.* 配置段
 */
@Component
@ConfigurationProperties("share")
data class ShareProperties(
    @NestedConfigurationProperty
    var jwt: Jwt = Jwt(),
    @NestedConfigurationProperty
    var info: Info = Info(),
    @NestedConfigurationProperty
    var cache: Cache = Cache(),
) {
    /**
     * JWT 配置
     */
    data class Jwt(
        /**
         * 携带 token 的请求头名称
         */
        var header: String = "Authorization",
        /**
         * JWT 签名密钥,生产环境务必更换
         */
        var secret: String = "please-change-me-to-a-long-random-secret",
        /**
         * AccessToken 过期时间,单位秒
         */
        var expire: Long = 21600,
        /**
         * RefreshToken 过期时间,单位秒
         */
        var refreshExpire: Long = 604800,
    )

    /**
     * 系统信息配置(用于 /version 等接口)
     */
    data class Info(
        var title: String = "Share Backend API",
        var description: String = "Share Backend API",
        var version: String = "v0.1.0",
    )

    /**
     * 缓存配置
     */
    data class Cache(
        /**
         * 缓存默认过期时间,单位秒
         */
        var defaultExpire: Long = 60,
    )
}
