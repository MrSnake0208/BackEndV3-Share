package com.lhs.share.hub.controller.calendar.request

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.hub.repository.entity.CalendarChecklistEntry
import com.lhs.share.hub.service.calendar.calendarInvalid

data class CalendarSubscribeRequest(val accountId: String, val expectedVersion: Long?, val subscribed: Boolean)
data class CalendarProgressRequest(
    val accountId: String,
    val expectedVersion: Long,
    val completed: Boolean,
    val checklist: List<CalendarChecklistEntry>,
)

object CalendarSubscriptionRequestDecoder {
    fun subscribe(node: JsonNode): CalendarSubscribeRequest {
        fields(node, setOf("account_id", "expected_version", "subscribed"))
        return CalendarSubscribeRequest(string(node, "account_id"), version(node, true), boolean(node, "subscribed"))
    }

    fun progress(node: JsonNode): CalendarProgressRequest {
        fields(node, setOf("account_id", "expected_version", "completed", "checklist"))
        val list = node.get("checklist") ?: throw calendarInvalid("请提供关卡清单")
        if (!list.isArray || list.size() > 50) throw calendarInvalid("关卡清单最多50项")
        val entries = list.map {
            fields(it, setOf("id", "title", "completed"))
            CalendarChecklistEntry(string(it, "id"), string(it, "title"), boolean(it, "completed"))
        }
        return CalendarProgressRequest(string(node, "account_id"), checkNotNull(version(node, false)), boolean(node, "completed"), entries)
    }

    private fun fields(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject || node.fieldNames().asSequence().any { it !in allowed } || allowed.any { !node.has(it) }) {
            throw calendarInvalid("请求字段不正确")
        }
    }

    private fun string(node: JsonNode, key: String): String {
        val value = node.get(key)
        if (value == null || !value.isTextual || value.asText().isBlank() || value.asText().length > 128) {
            throw calendarInvalid("$key 不正确")
        }
        return value.asText()
    }

    private fun boolean(node: JsonNode, key: String): Boolean {
        val value = node.get(key)
        if (value == null || !value.isBoolean) throw calendarInvalid("$key 必须为布尔值")
        return value.booleanValue()
    }

    private fun version(node: JsonNode, nullable: Boolean): Long? {
        val value = node.get("expected_version") ?: throw calendarInvalid("请提供expected_version")
        if (nullable && value.isNull) return null
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() == Long.MAX_VALUE) {
            throw calendarInvalid("expected_version不正确")
        }
        return value.longValue()
    }
}
