package com.lhs.share.hub.controller.calendar.request

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.hub.service.calendar.calendarInvalid
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class CalendarPrivateItemRequest(
    val accountId: String,
    val title: String,
    val date: LocalDate,
    val time: String?,
    val note: String,
    val expectedVersion: Long?,
)

object CalendarPrivateItemRequestDecoder {
    fun decode(node: JsonNode, update: Boolean): CalendarPrivateItemRequest {
        val required = setOf("account_id", "title", "date") + if (update) setOf("expected_version") else emptySet()
        val allowed = required + setOf("time", "note")
        if (!node.isObject || required.any { !node.has(it) } || node.fieldNames().asSequence().any { it !in allowed }) {
            throw calendarInvalid("私人事项请求字段不正确")
        }
        val date = text(node, "date", 10)
        if (!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || date.startsWith("0000")) throw calendarInvalid("日期不正确", "date")
        val parsedDate = try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            throw calendarInvalid("日期不正确", "date")
        }
        val time = node.get("time")?.takeUnless { it.isNull }?.let {
            if (!it.isTextual || !it.asText().matches(Regex("([01][0-9]|2[0-3]):[0-5][0-9]"))) throw calendarInvalid("时间须为HH:mm", "time")
            it.asText()
        }
        val version = if (update) {
            val value = node.get("expected_version")
            if (!value.isIntegralNumber || !value.canConvertToLong()) throw calendarInvalid("expected_version不正确")
            value.longValue().also(::requireVersion)
        } else {
            null
        }
        return CalendarPrivateItemRequest(
            text(node, "account_id", 128),
            text(node, "title", 100, trim = true),
            parsedDate,
            time,
            if (node.has("note")) text(node, "note", 1000, allowBlank = true) else "",
            version,
        )
    }

    fun requireVersion(value: Long) {
        if (value < 0 || value == Long.MAX_VALUE) throw calendarInvalid("expected_version不正确")
    }

    private fun text(node: JsonNode, key: String, limit: Int, allowBlank: Boolean = false, trim: Boolean = false): String {
        val raw = node.get(key)
        if (raw == null || !raw.isTextual) throw calendarInvalid("$key 必须为纯文本", key)
        val value = if (trim) raw.asText().trim() else raw.asText()
        if (value.length > limit || !allowBlank && value.isBlank()) throw calendarInvalid("$key 长度不正确", key)
        return value
    }
}
