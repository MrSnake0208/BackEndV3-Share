package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.convert.ValueConverter
import org.springframework.data.mongodb.core.convert.MongoConversionContext
import org.springframework.data.mongodb.core.convert.MongoValueConverter
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

enum class ActivityCalendarCategory {
    ACTIVITY,
    RECRUITMENT,
    LOGIN,
    SHOP,
    MAINTENANCE,
    OTHER,
}

@Document("activity_calendar_events")
data class ActivityCalendarEvent(
    @Id val id: String,
    val game: String,
    val title: String,
    val category: ActivityCalendarCategory,
    @field:ValueConverter(ActivityCalendarDateConverter::class) val startDate: LocalDate,
    @field:ValueConverter(ActivityCalendarDateConverter::class) val endDate: LocalDate,
    val startTime: String? = null,
    val endTime: String? = null,
    val timeZone: String = "Asia/Shanghai",
    val description: String? = null,
    val sourceUrl: String? = null,
    val sourceNote: String? = null,
    val enabled: Boolean = true,
    val createdBy: String,
    val createdAt: Instant,
    val updatedBy: String,
    val updatedAt: Instant,
    @Version val version: Long? = null,
)

/** Property-scoped conversion avoids the default LocalDate -> BSON Date/time-zone conversion. */
class ActivityCalendarDateConverter : MongoValueConverter<LocalDate, String> {
    override fun read(value: String, context: MongoConversionContext): LocalDate = LocalDate.parse(value)
    override fun write(value: LocalDate, context: MongoConversionContext): String = value.toString()
}
