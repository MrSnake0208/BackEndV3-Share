package com.lhs.share.hub.work.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDateTime

enum class CompatibilityStatus(@get:JsonValue val value: String) {
    EXACT("exact"),
    PARTIAL("partial"),
    UNSUPPORTED("unsupported"),
}

data class CompatibilityIssue(
    val code: String,
    val path: String,
    val feature: String,
    val message: String,
    val severity: CompatibilityStatus,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkDocument(
    val format: String = "yuanhub-work",
    val version: Int = 1,
    val game: String,
    val levelId: String? = null,
    val stageName: String,
    val doc: WorkDoc,
    val operators: List<String?>,
    val exec: WorkExec? = null,
    val rounds: List<WorkRound>,
)

data class WorkDoc(val title: String, val details: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkExec(
    val delaysMs: WorkDelays? = null,
    val extensions: WorkExtensions? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkDelays(
    val attack: Int? = null,
    val ultimate: Int? = null,
    val defense: Int? = null,
    val sp: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkExtensions(
    val maayuan: MaaYuanExtension? = null,
    val yuanassist: YuanAssistExtension? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaaYuanExtension(
    val levelType: String? = null,
    val recognitionName: String? = null,
    val recTargetOffset: List<Int>? = null,
    val difficulty: String? = null,
    val caveType: String? = null,
    val lantaiNav: Boolean? = null,
)

data class YuanAssistExtension(val enemyTurnWaitMs: Int? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkRound(val round: Int, val remark: String? = null, val actions: List<WorkAction>)

sealed interface WorkAction

data class SlotAction(val slot: Int, val type: String) : WorkAction
data class WaitAction(val type: String = "wait", val durationMs: Int) : WorkAction
data class PauseAction(val type: String = "pause") : WorkAction

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SwitchTargetAction(val type: String = "switch_target", val direction: String, val count: Int? = null) : WorkAction
data class AutoBattleAction(val type: String = "auto_battle", val enabled: Boolean) : WorkAction
data class InteractionAction(val type: String = "interaction") : WorkAction
data class OperatorAction(val type: String = "operator_action", val slot: Int, val action: String = "switch_form") : WorkAction
data class CheckAction(val type: String = "check", val condition: WorkCondition, val onFail: String = "restart") : WorkAction
data class RestartAction(val type: String = "restart") : WorkAction

sealed interface WorkCondition

data class PartySurvivesCondition(val type: String = "party_survives") : WorkCondition
data class OperatorAliveCondition(val type: String = "operator_alive", val slot: Int) : WorkCondition
data class OperatorPresentCondition(val type: String = "operator_present", val slot: Int) : WorkCondition
data class OperatorCopiedCondition(val type: String = "operator_copied", val slot: Int) : WorkCondition

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DragonQiCondition(
    val type: String = "dragon_qi",
    val slot: Int? = null,
    val operator: String,
    val value: Int,
) : WorkCondition

data class StarCountCondition(
    val type: String = "star_count",
    val color: String,
    val operator: String,
    val value: Int,
) : WorkCondition

data class CritCondition(val type: String = "crit") : WorkCondition

data class WorkLevelSummary(
    val id: String,
    val game: String,
    val name: String,
    val levelId: String,
    val stageId: String,
)

data class WorkMetadata(
    val id: Long,
    val title: String,
    val uploaderId: String?,
    val uploadTime: LocalDateTime?,
    val views: Long,
    val hotScore: Double,
    val likeCount: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkListItem(
    val id: Long,
    val title: String,
    val stageName: String?,
    val game: String?,
    val level: WorkLevelSummary?,
    val uploaderId: String?,
    val uploadTime: LocalDateTime?,
    val views: Long,
    val hotScore: Double,
    val conversionStatus: CompatibilityStatus,
    val issueCount: Int,
)

data class WorkPageResponse(
    val page: Int,
    val limit: Int,
    val total: Long,
    val hasNext: Boolean,
    val items: List<WorkListItem>,
)

data class WorkConversion(val status: CompatibilityStatus, val issues: List<CompatibilityIssue>)
data class WorkSource(val type: String = "maayuan_legacy", val id: Long, val rawContent: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkDetailResponse(
    val metadata: WorkMetadata,
    val level: WorkLevelSummary?,
    val conversion: WorkConversion,
    val work: WorkDocument?,
    val source: WorkSource,
)

enum class WorkTarget { MAAYUAN, YUANASSIST }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkCompatibilityResponse(
    val target: WorkTarget,
    val status: CompatibilityStatus,
    val issues: List<CompatibilityIssue>,
    val targetDocument: Any? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaaYuanTargetDocument(
    val roundActions: Map<String, List<List<String>>>,
    val delaysMs: WorkDelays? = null,
    val extensions: MaaYuanExtension? = null,
)

fun statusOf(issues: List<CompatibilityIssue>): CompatibilityStatus = when {
    issues.any { it.severity == CompatibilityStatus.UNSUPPORTED } -> CompatibilityStatus.UNSUPPORTED
    issues.isNotEmpty() -> CompatibilityStatus.PARTIAL
    else -> CompatibilityStatus.EXACT
}
