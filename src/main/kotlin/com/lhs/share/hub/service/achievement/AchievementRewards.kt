package com.lhs.share.hub.service.achievement

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lhs.share.hub.controller.achievement.response.AchievementRewardItem
import org.springframework.core.io.ClassPathResource

object AchievementRewards {
    const val MAX_SAFE_INTEGER = 9007199254740991L
    val items: List<AchievementRewardItem> = ClassPathResource("achievement/rewards.json").inputStream.use {
        jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).readValue<List<AchievementRewardItem>>(it)
    }.also { values ->
        check(values.map { it.itemKey }.distinct().size == values.size)
        check(values.all { it.itemKey.matches(Regex("[a-z][a-z0-9_]{0,99}")) && it.name.isNotBlank() })
    }
    val byKey = items.associateBy { it.itemKey }
}
