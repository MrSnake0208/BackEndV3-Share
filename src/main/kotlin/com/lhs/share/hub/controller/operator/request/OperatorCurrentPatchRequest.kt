package com.lhs.share.hub.controller.operator.request

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Swagger 用的 PATCH 结构。Controller 使用 JSON tree 保留字段缺失与显式 null 的区别，
 * 但公开契约仍由这些类型准确描述。
 */
@Schema(name = "OperatorCurrentPatchRequest")
data class OperatorCurrentPatchRequest(
    val level: Int? = null,
    val elite: Int? = null,
    @JsonProperty("star_level") val starLevel: Int? = null,
    @JsonProperty("disc_loadouts") val discLoadouts: List<OperatorDiscLoadoutPatchRequest>? = null,
    @field:Schema(description = "六槽当前装备的完整替换；缺失保留，空数组清空；槽位仅允许 main1..main3、assist1..assist3")
    @JsonProperty("star_stones") val starStones: List<OperatorStarStonePatchRequest>? = null,
    @JsonProperty("combat_stats") val combatStats: OperatorCombatStatsPatchRequest? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("expected_revision") val expectedRevision: Long,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        allowableValues = ["manual_correction", "local_migration"],
    )
    val reason: String,
)

data class OperatorDiscLoadoutPatchRequest(
    val id: String,
    val name: String? = null,
    val discs: List<OperatorDiscPatchRequest> = emptyList(),
)

data class OperatorDiscPatchRequest(@JsonProperty("ot_name") val otName: String)

data class OperatorStarStonePatchRequest(
    val name: String,
    @field:Schema(allowableValues = ["main1", "main2", "main3", "assist1", "assist2", "assist3"])
    val type: String,
    @field:Schema(minimum = "0")
    val level: Int,
)

data class OperatorCombatStatsPatchRequest(
    val observedAttack: Long? = null,
    val observedHp: Long? = null,
    val manualAttack: Long? = null,
    val manualHp: Long? = null,
    val source: String? = null,
    val observedAt: Instant? = null,
    val observedStatus: String? = null,
    val combatInputSignature: String? = null,
    val observedInputs: OperatorObservedInputsPatchRequest? = null,
    @field:Schema(description = "攻击力/生命力显示偏好；内部字段按出现性合并，不参与 stale", nullable = true)
    @JsonProperty("display_mode") val displayMode: OperatorCombatDisplayModePatchRequest? = null,
    val oddities: Map<String, OperatorOddityPatchRequest>? = null,
)

data class OperatorCombatDisplayModePatchRequest(
    @field:Schema(description = "auto=公式计算，manual=手动填写或自动采集值", nullable = true, allowableValues = ["auto", "manual"])
    val attack: String? = null,
    @field:Schema(description = "auto=公式计算，manual=手动填写或自动采集值", nullable = true, allowableValues = ["auto", "manual"])
    val hp: String? = null,
)

data class OperatorObservedInputsPatchRequest(
    val level: Int? = null,
    val elite: Int? = null,
    val starLevel: Int? = null,
    val odditiesSignature: String? = null,
    val equippedStarStonesSignature: String? = null,
)

data class OperatorOddityPatchRequest(
    val current: Int,
    @field:Schema(description = "仅作来源诊断；服务端始终使用公共图鉴 rarity 对应的上限")
    val max: Int? = null,
)
