package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("operator_catalog")
@CompoundIndex(name = "idx_op_catalog_id_unique", def = "{'operatorId': 1}", unique = true)
data class OperatorCatalogEntity(
    @Id val id: String? = null,
    val operatorId: String,
    val name: String,
    val alias: String? = null,
    val rarity: Int,
    val prof: List<String>,
    val subProf: List<String>,
    val games: List<String>,
    val discs: List<OperatorDiscCatalog>,
    val starStones: List<OperatorStarStoneCatalog>,
    val catalogVersion: String,
    val createdAt: Instant = Instant.now(),
)

data class OperatorDiscCatalog(val otName: String, val abbreviation: String? = null, val color: String? = null, val desp: String? = null)
data class OperatorStarStoneCatalog(val name: String, val type: String)
