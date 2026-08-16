package com.lhs.share.openapi

/**
 * 第三方 API Token 权限枚举
 *
 * 每个权限有唯一 code(整数)与 key(字符串),scope 中存 code。
 * code 与后端校验、前端展示均以此处为唯一来源。
 */
enum class OpenApiPermission(
    val code: Int,
    val key: String,
    val desc: String,
) {
    /**
     * 库存数据读取
     */
    INVENTORY_READ(code = 10001, key = "inventory:read", desc = "库存数据读取"),

    /**
     * 库存数据写入
     */
    INVENTORY_WRITE(code = 10002, key = "inventory:write", desc = "库存数据写入"),
    ;

    companion object {
        /**
         * 按 key 反向查 code,未知 key 返回 null
         */
        fun codeByKey(key: String): Int? = entries.firstOrNull { it.key == key }?.code

        /**
         * 列出全部权限,每项 {key, code, desc}
         */
        fun listAll(): List<Map<String, Any>> = entries.map {
            mapOf("key" to it.key, "code" to it.code, "desc" to it.desc)
        }
    }
}
