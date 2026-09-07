package com.lhs.share.hub.controller.operator.response

import java.time.Instant

data class OperatorShareResponse(
    val accountId: String,
    val active: Boolean,
    val shareCode: String?,
)

/** Public projection for a share link; identity and internal current fields are deliberately absent. */
data class OperatorShareViewResponse(
    val game: String,
    val catalogVersion: String,
    val updatedAt: Instant?,
    val entries: Map<String, OperatorShareEntryDto>,
)

data class OperatorShareEntryDto(
    val level: Int,
    val elite: Int,
    val starLevel: Int,
    val growthState: String,
    val discLoadouts: List<OperatorShareDiscLoadout>,
    val starStones: List<OperatorShareStarStone>,
    val combatStats: OperatorShareCombatStats?,
)

/** Public combat values; observation provenance, signatures and display preferences stay private. */
data class OperatorShareCombatStats(
    val observedAttack: Long?,
    val observedHp: Long?,
    val manualAttack: Long?,
    val manualHp: Long?,
    val oddities: Map<String, OperatorShareOddityValue>,
)

data class OperatorShareDiscLoadout(
    val id: String,
    val name: String,
    val discs: List<OperatorShareDisc>,
)

data class OperatorShareDisc(
    val otName: String,
    val abbreviation: String?,
    val color: String?,
    val desp: String?,
)

data class OperatorShareStarStone(
    val name: String?,
    val type: String,
    val level: Int,
)

data class OperatorShareOddityValue(val current: Int)
