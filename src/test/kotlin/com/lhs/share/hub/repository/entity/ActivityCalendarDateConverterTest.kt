package com.lhs.share.hub.repository.entity

import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.convert.QueryMapper
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant
import java.time.LocalDate

class ActivityCalendarDateConverterTest {
    private val conversions = MongoCustomConversions(emptyList<Any>())
    private val context = MongoMappingContext().apply {
        setSimpleTypeHolder(conversions.simpleTypeHolder)
        afterPropertiesSet()
    }
    private val converter = MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context).apply {
        setCustomConversions(conversions)
        afterPropertiesSet()
    }

    @Test
    fun `ongoing overlap query maps null and dates without a database`() {
        val from = LocalDate.of(2026, 10, 12)
        val to = from.plusDays(90)
        val query = Query().addCriteria(
            Criteria().orOperator(Criteria.where("endDate").gte(from), Criteria.where("endDate").`is`(null)),
        ).addCriteria(Criteria.where("startDate").lte(to))
        val mapped = QueryMapper(
            converter,
        ).getMappedObject(query.queryObject, context.getPersistentEntity(ActivityCalendarEvent::class.java))
        assertEquals(
            Document("\$or", listOf(Document("endDate", Document("\$gte", "2026-10-12")), Document("endDate", null)))
                .append("startDate", Document("\$lte", "2027-01-10")),
            mapped,
        )
    }

    @Test
    fun `finite null and missing end dates round trip as ISO dates`() {
        val start = LocalDate.of(2026, 10, 12)
        val event = ActivityCalendarEvent(
            id = "date-converter",
            game = "如鸢",
            title = "持续活动",
            category = ActivityCalendarCategory.ACTIVITY,
            startDate = start,
            createdBy = "editor",
            updatedBy = "editor",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        for (end in listOf(null, start.plusDays(1))) {
            val encoded = Document()
            converter.write(event.copy(endDate = end), encoded)
            assertEquals("2026-10-12", encoded["startDate"])
            assertEquals(end?.toString(), encoded["endDate"])
            assertEquals(event.copy(endDate = end), converter.read(ActivityCalendarEvent::class.java, encoded))
        }
        val encoded = Document()
        converter.write(event, encoded)
        encoded["endDate"] = null
        assertNull(converter.read(ActivityCalendarEvent::class.java, encoded).endDate)
        encoded.remove("endDate")
        assertNull(converter.read(ActivityCalendarEvent::class.java, encoded).endDate)
    }
}
