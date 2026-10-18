package com.lhs.share.hub.service.calendar

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lhs.share.hub.repository.ActivityCalendarRepository
import com.lhs.share.hub.repository.RecruitmentCatalogRepository
import com.lhs.share.hub.repository.entity.ActivityCalendarCategory
import com.lhs.share.hub.repository.entity.ActivityCalendarEvent
import com.lhs.share.hub.repository.entity.ActivityCalendarSubtype
import com.lhs.share.hub.repository.entity.ActivityCalendarSubtypeDetail
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate

class ActivityCalendarClassificationExchangeServiceTest {
    private val repository = mockk<ActivityCalendarRepository>()
    private val recruitment = mockk<RecruitmentCatalogRepository>()
    private val mapper = jacksonObjectMapper().registerModule(
        JavaTimeModule(),
    ).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val calendar = ActivityCalendarService(repository, recruitment)
    private val exchange =
        ActivityCalendarClassificationExchangeService(calendar, repository, mapper, TransactionTemplate(mockk(relaxed = true)))
    private val event =
        ActivityCalendarEvent(
            "fixture", "如鸢", "资料", ActivityCalendarCategory.ACTIVITY,
            LocalDate.parse(
                "2026-10-01",
            ),
            createdBy = "creator", createdAt = Instant.EPOCH, updatedBy = "creator", updatedAt = Instant.EPOCH, version = 0,
        )

    init {
        every { repository.list(any(), any(), any(), any(), any()) } returns listOf(event)
        every { repository.findAll(any()) } answers { if (event.id in firstArg<Collection<String>>()) listOf(event) else emptyList() }
        every { recruitment.all() } returns emptyList()
    }

    @Test
    fun `nine manual targets and thirteen displayed choices match picture without guessing legacy`() {
        val exported = exchange.export()
        assertEquals(listOf("活动", "卡池", "家具", "其他"), exported.classificationOptions.keys.toList())
        assertEquals(13, exported.classificationOptions.values.sumOf { it.size })
        val json: ObjectNode = mapper.valueToTree(exported)
        for ((group, types) in exported.classificationOptions.filterKeys { it != "卡池" }) {
            for (type in types) {
                (json["items"][0] as ObjectNode).putObject("classification").put("level1", group).put("level2", type)
                assertEquals(1, exchange.preview(json.toString()).ready, "$group/$type")
            }
        }
        assertNull(exchange.classification(event.copy(subtype = ActivityCalendarSubtype.MEDIUM_EVENT)))
        assertNull(exchange.classification(event.copy(subtype = ActivityCalendarSubtype.TAISHAN_FU)))
        assertNull(exchange.classification(event.copy(subtype = ActivityCalendarSubtype.FURNITURE)))
        assertEquals(
            "神秘洞窟",
            exchange.classification(
                event.copy(subtype = ActivityCalendarSubtype.FURNITURE, subtypeDetail = ActivityCalendarSubtypeDetail.MYSTERIOUS_CAVE),
            )!!.level2,
        )
        verify(exactly = 0) { repository.updateClassification(any(), any(), any(), any()) }
    }

    @Test
    fun `reference fields never define writable values and omitted rows are not deletes`() {
        val json: ObjectNode = mapper.valueToTree(exchange.export())
        (json["items"][0] as ObjectNode).putObject("original").put("title", "不能覆盖标题")
        json.putObject("classification_options").put("伪造", "伪造")
        (json["items"][0] as ObjectNode).putObject("classification").put("level1", "卡池").put("level2", "限定")
        assertEquals(1, exchange.preview(json.toString()).invalid)
        (json["items"][0] as ObjectNode).putObject("classification").put("level1", "其他").put("level2", "商店")
        assertEquals(1, exchange.preview(json.toString()).ready)
        json.putArray("items")
        assertEquals(0, exchange.preview(json.toString()).total)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.updateClassification(any(), any(), any(), any()) }
    }

    @Test
    fun `empty export and row or UTF8 size overflow fail without truncation or writes`() {
        every { repository.list(any(), any(), any(), any(), any()) } returns emptyList()
        assertTrue(exchange.export().items.isEmpty())
        val json: ObjectNode = mapper.valueToTree(exchange.export())
        val rows = json.putArray("reference_items")
        repeat(10_001) { rows.addNull() }
        assertThrows(ActivityCalendarApiException::class.java) { exchange.preview(json.toString()) }
        assertThrows(ActivityCalendarApiException::class.java) {
            exchange.preview(
                "中".repeat(
                    ActivityCalendarClassificationExchangeService.MAX_BYTES / 3 + 1,
                ),
            )
        }
        every { repository.list(any(), any(), any(), any(), any()) } returns List(10_001) { event }
        assertThrows(ActivityCalendarApiException::class.java) { exchange.export() }
        verify(exactly = 0) { repository.save(any()) }
    }
}
