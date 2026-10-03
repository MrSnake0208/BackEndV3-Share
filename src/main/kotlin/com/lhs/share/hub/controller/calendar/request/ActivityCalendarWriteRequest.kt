package com.lhs.share.hub.controller.calendar.request

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.service.calendar.calendarInvalid
import java.time.LocalDate

data class ActivityCalendarWriteRequest(
    val game: String,
    val title: String,
    val category: ActivityCalendarCategory,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: String? = null,
    val endTime: String? = null,
    val timeZone: String = "Asia/Shanghai",
    val description: String? = null,
    val sourceUrl: String? = null,
    val sourceNote: String? = null,
    val enabled: Boolean = true,
    val expectedVersion: Long? = null,
)

/** Use the same strict boundary as recruitment, without changing the shared mapper. */
class ActivityCalendarRequestDecoder(mapper: ObjectMapper) {
    private val reader = mapper.copy().enable(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
    ).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)

    fun read(node: JsonNode): ActivityCalendarWriteRequest {
        if (!node.isObject) throw calendarInvalid("请求必须是JSON对象")
        // Jackson still coerces numbers to strings and accepts ordinal enums despite ALLOW_COERCION_OF_SCALARS.
        for (field in listOf(
            "game", "title", "category", "start_time", "end_time", "time_zone", "description", "source_url", "source_note",
        )) {
            val value = node.get(field)
            if (value != null && !value.isNull && !value.isTextual) throw calendarInvalid("文本字段必须是字符串")
        }
        for (field in listOf("start_date", "end_date")) {
            val value = node.get(field)
            if (value == null || !value.isTextual || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value.asText())) {
                throw calendarInvalid("日期必须为YYYY-MM-DD字符串")
            }
        }
        return try {
            reader.treeToValue(node, ActivityCalendarWriteRequest::class.java)
        } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
            throw calendarInvalid("请求字段或类型不符合活动日历要求")
        }
    }
}
