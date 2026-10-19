package com.lhs.share.hub.service.achievement

import com.lhs.share.hub.controller.achievement.response.AchievementEntry
import com.lhs.share.hub.repository.entity.CatalogContent

object AchievementCatalogRules {
    const val MAX_ENTRIES = 5000
    const val MAX_TARGET = 1000000000000L
    const val MAX_DOCUMENT_BYTES = 12 * 1024 * 1024
    val BOND_TARGETS = setOf(1L, 3L, 10L, 20L, 50L, 100L, 200L)

    fun errors(value: CatalogContent, publishing: Boolean): List<String> = buildList {
        fun require(ok: Boolean, message: String) {
            if (!ok) add(message)
        }
        require(value.game in setOf("代号鸢", "如鸢"), "游戏不支持")
        require(value.dataStatus in setOf("empty", "partial", "complete"), "资料范围状态非法")
        require(value.coverage.length <= 2000, "范围说明过长")
        require(value.categories.size <= 100 && value.entries.size <= MAX_ENTRIES, "分类最多100个，成就最多5000条（含退役）")
        require(value.categories.map { it.categoryId }.distinct().size == value.categories.size, "分类 ID 重复")
        require(value.entries.map { it.achievementId }.distinct().size == value.entries.size, "成就 ID 重复")
        require(value.retiredIds.all { id -> value.entries.any { it.achievementId == id } }, "退役 ID 必须存在于草稿")
        value.categories.forEach {
            require(it.categoryId.matches(Regex("[a-zA-Z0-9_-]{1,100}")), "分类 ID 非法")
            require(it.name.isNotBlank() && it.name.length <= 100, "分类名称必填且最多100字")
        }
        val categories = value.categories.associateBy { it.categoryId }
        val active = value.entries.filter { it.achievementId !in value.retiredIds }
        if (publishing) require((value.dataStatus == "empty") == active.isEmpty(), "空目录必须标记 empty，非空目录请选择 partial 或 complete")
        value.entries.forEach { entry ->
            val id = entry.achievementId
            require(id.matches(Regex("yh_[a-z0-9_]{1,96}")), "$id：成就 ID 非法")
            require(entry.game == value.game, "$id：游戏不匹配")
            require(categories.containsKey(entry.categoryId), "$id：分类不存在")
            if (id !in value.retiredIds) require(categories[entry.categoryId]?.enabled == true, "$id：分类已停用，请先迁移或退役该分类下的成就")
            require(entry.name.length <= 200 && entry.condition.length <= 4000, "$id：名称或条件过长")
            require(entry.target == null || entry.target in 1..MAX_TARGET, "$id：目标必须是1到1e12的正整数")
            require(
                entry.linkedMetric == null || entry.linkedMetric == "yuan_bond_variants" && entry.target in BOND_TARGETS,
                "$id：联动指标仅支持 yuan_bond_variants，目标必须为1/3/10/20/50/100/200",
            )
            entry.rewards?.let { rewards ->
                require(rewards.size <= 52, "$id：奖励物品最多52种")
                require(rewards.map { it.itemKey }.distinct().size == rewards.size, "$id：奖励物品键重复")
                var total = 0L
                rewards.forEach { reward ->
                    val item = AchievementRewards.byKey[reward.itemKey]
                    require(item != null && item.name == reward.name && entry.game in item.games, "$id：奖励物品键、名称或适用游戏不匹配")
                    val valid = reward.quantity in 1..AchievementRewards.MAX_SAFE_INTEGER
                    require(valid, "$id：奖励数量必须是正安全整数")
                    if (valid) {
                        require(total <= AchievementRewards.MAX_SAFE_INTEGER - reward.quantity, "$id：奖励数量合计超过安全整数")
                        total = (total + reward.quantity).coerceAtMost(AchievementRewards.MAX_SAFE_INTEGER)
                    }
                }
            }
            if (publishing && id !in value.retiredIds) require(entry.name.isNotBlank(), "$id：成就名称必填")
        }
    }

    fun compatibility(value: CatalogContent, identities: Map<String, AchievementEntry>): List<String> = value.entries.mapNotNull { entry ->
        val old = identities[entry.achievementId] ?: return@mapNotNull null
        if (old.target != null && (entry.target == null || entry.target < old.target)) {
            "${entry.achievementId}：已发布目标仅可增加，不可降低或清空；请保留原 ID 的口径"
        } else {
            null
        }
    }
}
