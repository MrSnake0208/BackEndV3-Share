package com.lhs.share.hub.work.adapter

import com.lhs.share.hub.work.model.AutoBattleAction
import com.lhs.share.hub.work.model.CheckAction
import com.lhs.share.hub.work.model.CompatibilityIssue
import com.lhs.share.hub.work.model.CompatibilityStatus
import com.lhs.share.hub.work.model.CritCondition
import com.lhs.share.hub.work.model.DragonQiCondition
import com.lhs.share.hub.work.model.InteractionAction
import com.lhs.share.hub.work.model.MaaYuanTargetDocument
import com.lhs.share.hub.work.model.OperatorAction
import com.lhs.share.hub.work.model.OperatorAliveCondition
import com.lhs.share.hub.work.model.OperatorCopiedCondition
import com.lhs.share.hub.work.model.OperatorPresentCondition
import com.lhs.share.hub.work.model.PartySurvivesCondition
import com.lhs.share.hub.work.model.PauseAction
import com.lhs.share.hub.work.model.RestartAction
import com.lhs.share.hub.work.model.SlotAction
import com.lhs.share.hub.work.model.StarCountCondition
import com.lhs.share.hub.work.model.SwitchTargetAction
import com.lhs.share.hub.work.model.WaitAction
import com.lhs.share.hub.work.model.WorkAction
import com.lhs.share.hub.work.model.WorkCompatibilityResponse
import com.lhs.share.hub.work.model.WorkDocument
import com.lhs.share.hub.work.model.WorkLevelSummary
import com.lhs.share.hub.work.model.WorkTarget
import com.lhs.share.hub.work.model.statusOf
import org.springframework.stereotype.Component

@Component
class MaaYuanWorkAdapter {
    fun check(work: WorkDocument?, sourceIssues: List<CompatibilityIssue>): WorkCompatibilityResponse {
        val issues = sourceIssues.toMutableList()
        if (work != null) {
            work.exec?.delaysMs?.let { delays ->
                if (delays.sp != null && delays.ultimate != null && delays.sp != delays.ultimate) {
                    issues += partial("delay_mismatch", "$.exec.delays_ms.sp", "delay:sp", "当前 SiMing 与大招共用 SP 延迟")
                }
            }
            work.rounds.forEachIndexed { roundIndex, round ->
                round.actions.forEachIndexed { actionIndex, action ->
                    checkAction(work, action, "$.rounds[$roundIndex].actions[$actionIndex]")?.let(issues::add)
                }
            }
        }
        val status = statusOf(issues)
        return WorkCompatibilityResponse(
            target = WorkTarget.MAAYUAN,
            status = status,
            issues = issues,
            targetDocument = work?.takeIf { status == CompatibilityStatus.EXACT }?.let(::compile),
        )
    }

    private fun checkAction(work: WorkDocument, action: WorkAction, path: String): CompatibilityIssue? = when (action) {
        is SlotAction, is WaitAction, is SwitchTargetAction, is InteractionAction, is RestartAction -> null
        is PauseAction -> unsupported("unsupported_action", path, "pause", "MaaYuan 没有稳定的暂停动作")
        is AutoBattleAction -> action.takeUnless { it.enabled }?.let {
            unsupported("unsupported_action", path, "auto_battle:false", "MaaYuan 没有稳定的关闭自动战斗动作")
        }
        is OperatorAction -> action.takeUnless { work.operators.getOrNull(it.slot - 1) == "吕布" }?.let {
            unsupported("unsupported_action", path, "operator_action:switch_form", "仅能为吕布无损编译切形态")
        }
        is CheckAction -> checkCondition(action, path)
    }

    private fun checkCondition(action: CheckAction, path: String): CompatibilityIssue? {
        if (action.onFail != "restart") {
            return unsupported("unsupported_failure_action", "$path.on_fail", "on_fail:${action.onFail}", "MaaYuan 检测仅支持失败后重开")
        }
        return when (val condition = action.condition) {
            is PartySurvivesCondition, is OperatorAliveCondition, is OperatorPresentCondition, is OperatorCopiedCondition -> null
            is DragonQiCondition -> condition.takeUnless { it.slot != null && it.operator == ">=" && it.value == 2 }?.let {
                unsupported("unsupported_condition", "$path.condition", "check:dragon_qi", "MaaYuan 仅支持指定槽位龙气 >= 2")
            }
            is StarCountCondition -> condition.takeUnless { it.operator == ">=" && it.value == 1 }?.let {
                unsupported("unsupported_condition", "$path.condition", "check:star_count", "MaaYuan 仅支持指定颜色星数 >= 1")
            }
            is CritCondition -> unsupported("unsupported_condition", "$path.condition", "check:crit", "MaaYuan 当前没有稳定的暴击检测动作")
        }
    }

    private fun compile(work: WorkDocument): MaaYuanTargetDocument = MaaYuanTargetDocument(
        roundActions = work.rounds.associate { round ->
            round.round.toString() to round.actions.flatMap { action -> compileAction(action) }.map(::listOf)
        },
        delaysMs = work.exec?.delaysMs,
        extensions = work.exec?.extensions?.maayuan,
    )

    private fun compileAction(action: WorkAction): List<String> = when (action) {
        is SlotAction -> listOf(
            "${action.slot}${mapOf("attack" to "普", "ultimate" to "大", "defense" to "下", "sp" to "sp").getValue(action.type)}",
        )
        is WaitAction -> listOf("额外:等待:${action.durationMs}")
        is SwitchTargetAction -> List(action.count ?: 1) { "额外:${if (action.direction == "left") "左" else "右"}侧目标" }
        is AutoBattleAction -> listOf("额外:开自动")
        is InteractionAction -> listOf("额外:关卡内互动")
        is OperatorAction -> listOf("额外:吕布")
        is RestartAction -> listOf("重开:左上角")
        is CheckAction -> listOf(compileCheck(action))
        is PauseAction -> error("Compatibility check must reject pause")
    }

    private fun compileCheck(action: CheckAction): String = when (val condition = action.condition) {
        is PartySurvivesCondition -> "重开:全灭"
        is OperatorAliveCondition -> "重开:检测${condition.slot}号位阵亡"
        is OperatorPresentCondition -> "重开:检测${condition.slot}号位退场"
        is OperatorCopiedCondition -> "重开:检测${condition.slot}号位鹦鹉"
        is DragonQiCondition -> "重开:检测${condition.slot}号位龙气"
        is StarCountCondition -> "重开:无${mapOf("orange" to "橙", "purple" to "紫", "blue" to "蓝").getValue(condition.color)}星"
        is CritCondition -> error("Compatibility check must reject crit")
    }
}

@Component
class YuanAssistWorkAdapter {
    fun check(work: WorkDocument?, level: WorkLevelSummary?, sourceIssues: List<CompatibilityIssue>): WorkCompatibilityResponse {
        val issues = sourceIssues.toMutableList()
        work?.rounds?.forEachIndexed { roundIndex, round ->
            round.actions.forEachIndexed { actionIndex, action ->
                checkAction(action, level, "$.rounds[$roundIndex].actions[$actionIndex]")?.let(issues::add)
            }
        }
        return WorkCompatibilityResponse(WorkTarget.YUANASSIST, statusOf(issues), issues)
    }

    private fun checkAction(action: WorkAction, level: WorkLevelSummary?, path: String): CompatibilityIssue? = when (action) {
        is SlotAction, is WaitAction, is PauseAction, is SwitchTargetAction -> null
        is AutoBattleAction -> unsupported("unsupported_action", path, "auto_battle", "YuanAssist 当前没有稳定的自动战斗开关指令")
        is InteractionAction -> unsupported("unsupported_action", path, "interaction", "YuanAssist 当前没有等价互动指令")
        is OperatorAction -> unsupported("unsupported_action", path, "operator_action:switch_form", "YuanAssist 当前没有等价切形态指令")
        is RestartAction -> unsupported("unsupported_action", path, "restart", "YuanAssist 当前没有等价立即重开指令")
        is CheckAction -> checkCondition(action, level, path)
    }

    private fun checkCondition(action: CheckAction, level: WorkLevelSummary?, path: String): CompatibilityIssue? {
        if (action.onFail != "restart") {
            return unsupported("unsupported_failure_action", "$path.on_fail", "on_fail:${action.onFail}", "YuanAssist 尚未实现该检测失败行为")
        }
        return when (val condition = action.condition) {
            is PartySurvivesCondition -> null
            is OperatorPresentCondition -> unsupported(
                "unsupported_condition",
                "$path.condition",
                "check:operator_present",
                "YuanAssist 当前没有退场检测",
            )
            is DragonQiCondition -> if (condition.slot != null) {
                unsupported("unsupported_condition", "$path.condition.slot", "check:dragon_qi:slot", "YuanAssist 龙气检测不携带槽位")
            } else {
                restartNavigationIssue(level, path)
            }
            is StarCountCondition -> if (condition.color == "blue") {
                unsupported("unsupported_condition", "$path.condition.color", "check:blue_star", "YuanAssist 当前没有蓝星检测")
            } else if (condition.operator != ">=" || condition.value != 1) {
                unsupported("unsupported_condition", "$path.condition", "check:star_count", "YuanAssist 星检测仅支持存在橙星或紫星")
            } else {
                restartNavigationIssue(level, path)
            }
            is OperatorAliveCondition, is OperatorCopiedCondition, is CritCondition -> restartNavigationIssue(level, path)
        }
    }

    private fun restartNavigationIssue(level: WorkLevelSummary?, path: String): CompatibilityIssue = partial(
        "restart_navigation_unavailable",
        "$path.on_fail",
        "restart_navigation",
        if (level == null) "缺少可靠 Level Catalog 关联，无法生成失败后的自动导航" else "YUANASSIST Adapter 第一阶段仅分析，尚未生成失败后的自动导航",
    )
}

private fun unsupported(code: String, path: String, feature: String, message: String) =
    CompatibilityIssue(code, path, feature, message, CompatibilityStatus.UNSUPPORTED)

private fun partial(code: String, path: String, feature: String, message: String) =
    CompatibilityIssue(code, path, feature, message, CompatibilityStatus.PARTIAL)
