package com.lhs.share.config.security

/** Match MVC's decoded route pattern, not a client supplied source, Origin or Referer. */
object BetaAccessPolicy {
    private val privateRoots = listOf(
        "/v1/accounts",
        "/v1/inventory",
        "/v1/operator",
        "/v1/star-state",
        "/v1/star-loadout",
        "/v1/star-loadout-presets",
        "/v1/star/captures",
        "/hub/ledger/plan",
    )

    fun requiresBeta(method: String, path: String): Boolean {
        if (method == "OPTIONS") return false
        if (method == "GET" && path in setOf("/v1/inventory/catalog", "/v1/operator/catalog")) return false
        if (method == "GET" && path.startsWith("/v1/operator/share/view/")) return false
        if (method == "POST" && path == "/user/open-api/token") return true
        if (method == "PATCH" && path.startsWith("/user/open-api/tokens/") && path.endsWith("/scopes")) return true
        return privateRoots.any { path == it || path.startsWith("$it/") }
    }
}
