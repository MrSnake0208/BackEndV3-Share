package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.io.Serializable
import java.time.Instant

@Document("operator_current")
@CompoundIndex(name = "idx_op_user_account_game", def = "{'userId': 1, 'accountId': 1, 'game': 1}", unique = true)
data class OperatorCurrent(
    @Id val id: String? = null,
    @Indexed val userId: String,
    val accountId: String,
    val game: String,
    val fullBaselineAt: Instant? = null,
    val entries: Map<String, OperatorEntry> = emptyMap(),
    val updatedAt: Instant = Instant.now(),
) : Serializable

data class OperatorEntry(
    val elite: Int,
    val starLevel: Int,
    val level: Int,
    val discs: List<OperatorDisc> = emptyList(),
    val starStones: List<OperatorStarStone> = emptyList(),
    val listedBaselineAt: Instant? = null,
)

data class OperatorDisc(val otName: String, val abbreviation: String? = null, val color: String? = null, val desp: String? = null)
data class OperatorStarStone(val name: String? = null, val type: String, val level: Int)
