package com.lhs.share.config.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security 配置
 *
 * 认证方式:无状态 JWT,通过 [JwtAuthenticationTokenFilter] 解析请求头中的 token
 *
 * 新增接口时,公开接口加入 [URL_PERMIT_ALL],需要登录的接口保持默认(authenticated)
 * 即可;若需要精细到接口级权限,可参考 URL_AUTHENTICATION_1 的模式按 authority 放行
 */
@Configuration
class SecurityConfig(
    private val authenticationConfiguration: AuthenticationConfiguration,
    private val jwtAuthenticationTokenFilter: JwtAuthenticationTokenFilter,
    private val authenticationEntryPoint: AuthenticationEntryPointImpl,
    private val accessDeniedHandler: AccessDeniedHandlerImpl,
) {
    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(): AuthenticationManager = authenticationConfiguration.authenticationManager

    @Bean
    @Throws(Exception::class)
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // 关闭 CSRF,使用无状态会话
        http
            .csrf { obj: CsrfConfigurer<HttpSecurity> -> obj.disable() }
            .sessionManagement { sessionManagement: SessionManagementConfigurer<HttpSecurity?> ->
                sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }

        http.authorizeHttpRequests { authorize ->
            authorize
                .requestMatchers(*URL_PERMIT_ALL)
                .permitAll()
                .anyRequest()
                .authenticated()
        }
        // 添加 JWT 过滤器
        http.addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter::class.java)

        // 配置认证失败 / 权限不足的 JSON 响应
        http.exceptionHandling { exceptionHandling: ExceptionHandlingConfigurer<HttpSecurity?> ->
            exceptionHandling
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
        }

        // 开启跨域请求
        http.cors(Customizer.withDefaults())
        return http.build()
    }

    companion object {
        /**
         * 放行接口,不需要登录即可访问,在此处添加
         */
        private val URL_PERMIT_ALL =
            arrayOf(
                "/",
                "/error",
                "/version",
                "/demo/**",
                "/auth/demo-token",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/swagger-ui/**",
            )
    }
}
