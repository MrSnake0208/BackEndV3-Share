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
    val discLoadouts: List<OperatorDiscLoadout> = emptyList(),
    val combatStats: OperatorCombatStats? = null,
    val revision: Long = 0,
    val listedBaselineAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class OperatorDisc(val otName: String, val abbreviation: String? = null, val color: String? = null, val desp: String? = null)
data class OperatorStarStone(val name: String? = null, val type: String, val level: Int)
data class OperatorDiscLoadout(val id: String, val name: String, val discs: List<OperatorDisc> = emptyList())

data class OperatorCombatStats(
    val observedAttack: Long? = null,
    val observedHp: Long? = null,
    val manualAttack: Long? = null,
    val manualHp: Long? = null,
    val source: String? = null,
    val observedAt: Instant? = null,
    val observedStatus: String? = null,
    val combatInputSignature: String? = null,
    val observedInputs: OperatorObservedInputs? = null,
    val oddities: Map<String, OperatorOddityValue> = emptyMap(),
)

data class OperatorObservedInputs(
    val level: Int? = null,
    val elite: Int? = null,
    val starLevel: Int? = null,
    val odditiesSignature: String? = null,
    val equippedStarStonesSignature: String? = null,
)

data class OperatorOddityValue(val current: Int)

fun OperatorEntry.normalized(): OperatorEntry {
    val loadouts = if (discLoadouts.isNotEmpty()) {
        discLoadouts
    } else if (discs.isNotEmpty()) {
        listOf(OperatorDiscLoadout("disc_1", "命盘一", discs))
    } else {
        emptyList()
    }
    val normalizedStones = starStones.map { stone ->
        stone.copy(
            type = when (stone.type) {
                "main" -> "main1"
                "assist" -> "assist1"
                else -> stone.type
            },
        )
    }
    return copy(
        discs = loadouts.firstOrNull()?.discs.orEmpty(),
        starStones = normalizedStones,
        discLoadouts = loadouts,
    )
}
