package com.lhs.share.hub.service.achievement

import com.lhs.share.hub.repository.entity.CatalogContent
import java.net.URI

object AchievementCatalogRules {
    fun errors(value: CatalogContent, publishing: Boolean): List<String> = buildList {
        fun require(ok: Boolean, message: String) {
            if (!ok) add(message)
        }
        require(value.game in setOf("代号鸢", "如鸢"), "游戏不支持")
        require(value.dataStatus in setOf("empty", "partial", "complete"), "资料范围状态非法")
        require(value.coverage.length <= 2000, "范围说明过长")
        require(value.categories.size <= 100 && value.entries.size <= 1000, "分类最多100个，成就最多1000条")
        require(value.categories.map { it.categoryId }.distinct().size == value.categories.size, "分类 ID 重复")
        require(value.entries.map { it.achievementId }.distinct().size == value.entries.size, "成就 ID 重复")
        require(value.retiredIds.all { id -> value.entries.any { it.achievementId == id } }, "退役 ID 必须存在于草稿")
        value.categories.forEach {
            require(it.categoryId.matches(Regex("[a-zA-Z0-9_-]{1,100}")), "分类 ID 非法")
            require(it.name.isNotBlank() && it.name.length <= 100, "分类名称必填且最多100字")
        }
        val categories = value.categories.associateBy { it.categoryId }
        val active = value.entries.filter { it.achievementId !in value.retiredIds }
        if (publishing) {
            require((value.dataStatus == "empty") == active.isEmpty(), "空目录必须标记 empty，非空目录请选择 partial 或 complete")
        }
        value.entries.forEach { entry ->
            val id = entry.achievementId
            require(id.matches(Regex("yh_[a-z0-9_]{1,96}")), "$id：成就 ID 非法")
            require(entry.game == value.game, "$id：游戏不匹配")
            require(categories.containsKey(entry.categoryId), "$id：分类不存在")
            if (id !in value.retiredIds) require(categories[entry.categoryId]?.enabled == true, "$id：分类已停用，请先迁移或退役该分类下的成就")
            require(entry.name.length <= 200 && entry.condition.length <= 4000, "$id：名称或条件过长")
            require(listOf(entry.method, entry.spoiler, entry.reward, entry.chapter).all { it == null || it.length <= 4000 }, "$id：文本过长")
            require(entry.aliases.size <= 30 && entry.aliases.all { it.length <= 200 }, "$id：别名过多或过长")
            require(entry.operators.size <= 100 && entry.operators.all { it.id.length <= 100 && it.name.length <= 200 }, "$id：关联密探非法")
            require(entry.dataStatus in setOf("draft", "unverified", "verified"), "$id：核验状态非法")
            require(entry.sources.size <= 10, "$id：来源最多10条")
            require(entry.sources.all { it.title.length <= 200 && it.url.length <= 2000 && it.usage.length <= 2000 }, "$id：来源文本过长")
            if (entry.progressKind == null) {
                require(entry.target == null && entry.unit == null, "$id：不支持数值进度时不能设置目标或单位")
            } else {
                require(entry.progressKind in setOf("integer", "number"), "$id：进度类型非法")
                require(entry.target == null || entry.target.isFinite() && entry.target > 0 && entry.target <= 1e12, "$id：目标必须是正数且不超过1e12")
                require(entry.progressKind != "integer" || entry.target == null || entry.target % 1.0 == 0.0, "$id：累计目标必须为整数")
                require(entry.unit == null || entry.unit.length <= 40, "$id：单位过长")
            }
            if (publishing && id !in value.retiredIds) {
                require(entry.name.isNotBlank(), "$id：成就名称必填")
                entry.sources.filter { it.url.isNotBlank() }.forEach { source ->
                    require(
                        runCatching {
                            URI(source.url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
                        }.getOrDefault(false),
                        "$id：来源必须是 HTTPS 原始链接",
                    )
                }
                if (entry.progressKind != null) require(entry.target != null, "$id：启用数值进度时必须填写目标")
            }
        }
    }

    fun compatibility(
        value: CatalogContent,
        identities: Map<String, com.lhs.share.hub.controller.achievement.response.AchievementEntry>,
    ): List<String> = value.entries.filter { it.achievementId !in value.retiredIds }.mapNotNull { entry ->
        val old = identities[entry.achievementId] ?: return@mapNotNull null
        if (old.progressKind != entry.progressKind || old.unit != entry.unit ||
            old.target != null && (entry.target == null || entry.target < old.target)
        ) {
            "${entry.achievementId}：已发布进度类型/单位不可变，目标仅可增加；请保留原 ID 的口径"
        } else {
            null
        }
    }
}
