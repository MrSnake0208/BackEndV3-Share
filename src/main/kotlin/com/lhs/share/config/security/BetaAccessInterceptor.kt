package com.lhs.share.config.security

import com.lhs.share.hub.service.beta.BetaApiException
import com.lhs.share.hub.service.beta.BetaService
import com.lhs.share.hub.service.recruitment.RecruitmentAccessService
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Component
class BetaAccessInterceptor(
    private val beta: BetaService,
    private val recruitmentAccess: RecruitmentAccessService,
    private val helper: AuthenticationHelper,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // The initial REQUEST was authenticated. SSE completion/error redispatch must not rewrite a committed response.
        if (request.dispatcherType == DispatcherType.ASYNC || request.dispatcherType == DispatcherType.ERROR) return true
        if (handler !is HandlerMethod) return true
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: request.servletPath
        if (BetaAccessPolicy.requiresBeta(request.method, pattern)) {
            response.setHeader("Cache-Control", "no-store")
            val userId = helper.requireUserId()
            if (pattern == "/v1/accounts" || pattern.startsWith("/v1/accounts/")) {
                try {
                    beta.requireAccess(userId)
                } catch (betaError: BetaApiException) {
                    if (!recruitmentAccess.canAccess(userId)) throw betaError
                }
            } else {
                beta.requireAccess(userId)
            }
        }
        return true
    }
}
