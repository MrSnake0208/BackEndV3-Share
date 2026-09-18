package com.lhs.share.hub.work

import com.lhs.share.hub.work.adapter.MaaYuanWorkAdapter
import com.lhs.share.hub.work.adapter.YuanAssistWorkAdapter
import com.lhs.share.hub.work.model.AutoBattleAction
import com.lhs.share.hub.work.model.CheckAction
import com.lhs.share.hub.work.model.CompatibilityStatus
import com.lhs.share.hub.work.model.CritCondition
import com.lhs.share.hub.work.model.DragonQiCondition
import com.lhs.share.hub.work.model.InteractionAction
import com.lhs.share.hub.work.model.MaaYuanTargetDocument
import com.lhs.share.hub.work.model.OperatorAction
import com.lhs.share.hub.work.model.OperatorPresentCondition
import com.lhs.share.hub.work.model.PauseAction
import com.lhs.share.hub.work.model.RestartAction
import com.lhs.share.hub.work.model.SlotAction
import com.lhs.share.hub.work.model.StarCountCondition
import com.lhs.share.hub.work.model.WorkDoc
import com.lhs.share.hub.work.model.WorkDocument
import com.lhs.share.hub.work.model.WorkRound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkAdaptersTest {
    @Test
    fun `MAAYUAN compiles only exact work to standard round actions`() {
        val adapter = MaaYuanWorkAdapter()
        val result = adapter.check(work(), emptyList())

        assertEquals(CompatibilityStatus.EXACT, result.status)
        val target = result.targetDocument as MaaYuanTargetDocument
        assertEquals(listOf(listOf("2普"), listOf("重开:无橙星")), target.roundActions.getValue("1"))

        val unsupported = adapter.check(
            work(CheckAction(condition = DragonQiCondition(slot = 2, operator = ">", value = 1))),
            emptyList(),
        )
        assertEquals(CompatibilityStatus.UNSUPPORTED, unsupported.status)
        assertNull(unsupported.targetDocument)

        val unsupportedActions = adapter.check(
            work(PauseAction(), CheckAction(condition = CritCondition())),
            emptyList(),
        )
        assertTrue(unsupportedActions.issues.any { it.feature == "pause" })
        assertTrue(unsupportedActions.issues.any { it.feature == "check:crit" })
    }

    @Test
    fun `YUANASSIST reports phase-one gaps without target document`() {
        val result = YuanAssistWorkAdapter().check(
            work(
                CheckAction(condition = OperatorPresentCondition(slot = 2)),
                CheckAction(condition = DragonQiCondition(slot = 2, operator = ">=", value = 2)),
                CheckAction(condition = StarCountCondition(color = "blue", operator = ">=", value = 1)),
                AutoBattleAction(enabled = true),
                InteractionAction(),
                OperatorAction(slot = 2),
                RestartAction(),
            ),
            null,
            emptyList(),
        )

        assertEquals(CompatibilityStatus.UNSUPPORTED, result.status)
        assertTrue(result.issues.any { it.feature == "check:operator_present" })
        assertTrue(result.issues.any { it.feature == "check:dragon_qi:slot" })
        assertTrue(result.issues.any { it.feature == "check:blue_star" })
        assertTrue(result.issues.any { it.feature == "auto_battle" })
        assertTrue(result.issues.any { it.feature == "interaction" })
        assertTrue(result.issues.any { it.feature == "operator_action:switch_form" })
        assertTrue(result.issues.any { it.feature == "restart" })
        assertNull(result.targetDocument)
    }

    private fun work(vararg extra: com.lhs.share.hub.work.model.WorkAction) = WorkDocument(
        game = "如鸢",
        stageName = "测试关卡",
        doc = WorkDoc("测试", ""),
        operators = listOf("一", "二", "三", "四", "五"),
        rounds = listOf(
            WorkRound(
                1,
                actions = listOf(SlotAction(2, "attack")) +
                    (
                        extra.toList().ifEmpty {
                            listOf(CheckAction(condition = StarCountCondition(color = "orange", operator = ">=", value = 1)))
                        }
                        ),
            ),
        ),
    )
}
