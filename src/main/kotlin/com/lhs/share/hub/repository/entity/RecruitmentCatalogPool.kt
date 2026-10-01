package com.lhs.share.hub.repository.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.time.Instant
import java.time.LocalDate

@Document("recruitment_catalog")
data class RecruitmentCatalogPool(
    @Id val poolId: String,
    val game: String,
    val name: String,
    @field:Field(targetType = FieldType.STRING) val startDate: LocalDate? = null,
    @field:Field(targetType = FieldType.STRING) val endDate: LocalDate? = null,
    val poolType: String? = null,
    val enabled: Boolean = true,
    val revision: Long = 0,
    val upAgents: List<RecruitmentUpAgent> = emptyList(),
    val upStatus: String = "unknown",
    @JsonIgnore val updatedBy: String? = null,
    @JsonIgnore val updatedAt: Instant = Instant.EPOCH,
)
