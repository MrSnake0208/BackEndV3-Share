package com.lhs.share.hub.service.operator

import com.lhs.share.hub.repository.entity.OperatorCatalogEntity

object OperatorResetRules {
    data class Refund(val suggested: Map<String, Long>, val money: Long, val experience: Long?)

    fun refund(level: Int, elite: Int, stage: Int?, catalog: OperatorCatalogEntity): Refund {
        // shortcut: the shared source lacks level-100 XP; require game-preview book counts until verified.
        val growth = OperatorRequirementRules.level(1, minOf(level, 99), catalog)
        val boundary = if (level in 10..90 && level % 10 == 0 && stage == level / 10) {
            OperatorRequirementRules.level(level, level + 1, catalog, levelBreakthroughStage = level / 10 - 1)
        } else {
            OperatorRequirementRules.Cost(emptyMap())
        }
        val cultivation = OperatorRequirementRules.elite(1, elite, catalog)
        val items = linkedMapOf<String, Long>()
        listOf(growth, boundary, cultivation).forEach { cost ->
            cost.items.forEach { (id, count) -> items[id] = (items[id] ?: 0) + count }
        }
        val xp = growth.experience.takeIf { level < 100 }
        // These are one mixed refund, not the three alternative ceil-based upgrade estimates.
        items["bingshuquanjuan"] = (xp ?: 0) / 1000
        items["bingshucanjuan"] = ((xp ?: 0) % 1000) / 100
        items["liutaobingshu"] = 0
        return Refund(items, growth.money + boundary.money + cultivation.money, xp)
    }
}
