package com.lhs.share.openapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * OpenApiPermission 枚举单元测试
 *
 * 守护权限 code / key / desc 的稳定契约(前端与第三方接口都依赖这些值)。
 */
class OpenApiPermissionTest {

    @Test
    fun permissions_have_expected_codes() {
        assertEquals(10001, OpenApiPermission.INVENTORY_READ.code)
        assertEquals(10002, OpenApiPermission.INVENTORY_WRITE.code)
        assertEquals("inventory:read", OpenApiPermission.INVENTORY_READ.key)
        assertEquals("inventory:write", OpenApiPermission.INVENTORY_WRITE.key)
    }

    @Test
    fun codeByKey_returns_code_for_known_key() {
        assertEquals(10001, OpenApiPermission.codeByKey("inventory:read"))
        assertEquals(10002, OpenApiPermission.codeByKey("inventory:write"))
    }

    @Test
    fun codeByKey_returns_null_for_unknown_key() {
        assertNull(OpenApiPermission.codeByKey("inventory:admin"))
        assertNull(OpenApiPermission.codeByKey(""))
    }

    @Test
    fun listAll_returns_key_code_desc_entries() {
        val list = OpenApiPermission.listAll()

        assertEquals(2, list.size)
        val read = list.first { it["key"] == "inventory:read" }
        assertEquals(10001, read["code"])
        assertEquals("库存数据读取", read["desc"])

        val write = list.first { it["key"] == "inventory:write" }
        assertEquals(10002, write["code"])
        assertEquals("库存数据写入", write["desc"])
    }
}
