package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.lhs.share.hub.controller.operator.response.OperatorDailyTaskSource
import com.lhs.share.hub.controller.operator.response.OperatorDailyTasksResponse
import com.lhs.share.hub.repository.OperatorDailyTasksRepository
import com.lhs.share.hub.repository.SubAccountRepository
import com.lhs.share.hub.repository.entity.OperatorDailyTasks
import com.lhs.share.hub.repository.entity.OperatorTrainingTask
import com.lhs.share.hub.service.account.AccountEventService
import com.lhs.share.hub.service.recruitment.RecruitmentService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Service
class OperatorDailyTasksService(
    private val planner: OperatorPlannerService,
    private val repository: OperatorDailyTasksRepository,
    private val accounts: SubAccountRepository,
    private val events: AccountEventService,
    @param:Qualifier("hubTransactionTemplate") private val transaction: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun get(user: String, account: String, plan: String, date: String): OperatorDailyTasksResponse {
        validateDate(date)
        val source = source(planner.schedule(user, account, plan), date)
        return response(account, plan, date, source, repository.find(user, account, plan, date))
    }

    fun adopt(user: String, account: String, plan: String, date: String, body: JsonNode): OperatorDailyTasksResponse {
        fields(body, setOf("expected_version", "expected_schedule_revision"))
        val expected = version(body, "expected_version")
        val scheduleRevision = version(body, "expected_schedule_revision")
        return write(user, account, plan, date) {
            val source = source(planner.schedule(user, account, plan), date)
            if (source.revision != scheduleRevision) conflict("日程已变化，请读取最新方案后重试")
            if (source.status != "ready") invalid("当前日程不可加入待办，请先在养成页更新日程")
            val current = repository.find(user, account, plan, date)
            val tasks = merge(source.items, current?.tasks.orEmpty())
            if (exceedsDailyLimit(tasks)) invalid("新方案加上已完成次数超过同类历练每日 6 次，请先调整规划")
            // Retrying an identical import never resets or duplicates task history.
            if (current != null && current.tasks == tasks) return@write response(account, plan, date, source, current)
            if ((current?.version ?: 0) != expected) conflict()
            if (current == null && tasks.isEmpty()) invalid("今日没有可加入的历练")
            val saved = repository.replace(user, account, plan, date, expected, tasks, clock.instant()) ?: conflict()
            publish(user, account, plan, date)
            response(account, plan, date, source, saved)
        }
    }

    fun complete(user: String, account: String, plan: String, date: String, entry: String, body: JsonNode): OperatorDailyTasksResponse {
        fields(body, setOf("expected_version", "completed"))
        val expected = version(body, "expected_version")
        if (!body.path("completed").isBoolean) invalid("completed 必须为布尔值")
        return write(user, account, plan, date) {
            val source = source(planner.schedule(user, account, plan), date)
            val current = repository.find(user, account, plan, date) ?: conflict("请先加入今日待办")
            if (current.version != expected) conflict()
            val task = current.tasks.find { it.id == entry } ?: invalid("历练待办不存在")
            val completed = if (body.path("completed").booleanValue()) maxOf(task.count, task.completedCount) else 0
            val tasks = current.tasks.map { if (it.id == entry) it.copy(completedCount = completed) else it }
                .filter { it.count > 0 || it.completedCount > 0 }
            if (tasks == current.tasks) return@write response(account, plan, date, source, current)
            val saved = repository.replace(user, account, plan, date, expected, tasks, clock.instant()) ?: conflict()
            publish(user, account, plan, date)
            response(account, plan, date, source, saved)
        }
    }

    private fun write(
        user: String,
        account: String,
        plan: String,
        date: String,
        action: () -> OperatorDailyTasksResponse,
    ): OperatorDailyTasksResponse {
        validateDate(date)
        if (date != clock.instant().atZone(ZoneId.of("Asia/Shanghai")).minusHours(5).toLocalDate().toString()) {
            conflict("已跨过北京时间 05:00，请刷新今日历练")
        }
        try {
            return transaction.execute {
                val owner = accounts.findByUserIdAndAccountId(user, account)
                    ?: throw OperatorApiException(HttpStatus.NOT_FOUND, "account_not_found", "Account not found")
                if (!accounts.fenceRecruitmentWrite(user, account, owner.game)) conflict()
                if (planner.workspace(user, account).path("active_plan_id").asText() != plan) conflict("当前清单已切换，请刷新后重试")
                action()
            }!!
        } catch (error: RuntimeException) {
            if (error is DuplicateKeyException || RecruitmentService.isWriteConflict(error)) conflict()
            throw error
        }
    }

    private fun publish(user: String, account: String, plan: String, date: String) =
        events.publishChange(user, account, "operator_training_tasks", mapOf("plan_id" to plan, "date" to date))

    private fun response(
        account: String,
        plan: String,
        date: String,
        source: OperatorDailyTaskSource,
        record: OperatorDailyTasks?,
    ): OperatorDailyTasksResponse {
        val displayedSource = if (source.status == "ready" && exceedsDailyLimit(merge(source.items, record?.tasks.orEmpty()))) {
            source.copy(status = "daily_limit")
        } else {
            source
        }
        return OperatorDailyTasksResponse(
            account,
            plan,
            date,
            record?.version ?: 0,
            displayedSource,
            record?.tasks.orEmpty(),
            record != null && source.status == "ready" &&
                record.tasks.filter { it.count > 0 }.map { it.copy(completedCount = 0) }.sortedBy { it.id } != source.items,
        )
    }

    private fun validateDate(date: String) {
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || runCatching { LocalDate.parse(date) }.isFailure) invalid("日期不正确")
    }

    private fun fields(body: JsonNode, names: Set<String>) {
        if (!body.isObject || body.fieldNames().asSequence().toSet() != names) invalid("请求字段不正确")
    }

    private fun version(body: JsonNode, field: String): Long {
        val value = body.path(field)
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() !in 0L until 9007199254740991L) invalid("$field 不正确")
        return value.longValue()
    }

    private fun conflict(message: String = "待办已在其他设备更新，请读取最新状态后重试"): Nothing =
        throw OperatorApiException(HttpStatus.CONFLICT, "training_tasks_conflict", message)

    private fun invalid(message: String): Nothing =
        throw OperatorApiException(HttpStatus.UNPROCESSABLE_ENTITY, "schema_validation_failed", message)

    companion object {
        private fun exceedsDailyLimit(tasks: List<OperatorTrainingTask>) =
            tasks.groupBy { it.groupId }.values.any { group -> group.sumOf { maxOf(it.count, it.completedCount) } > 6 }

        internal fun merge(planned: List<OperatorTrainingTask>, old: List<OperatorTrainingTask>): List<OperatorTrainingTask> {
            val byId = old.associateBy { it.id }
            val ids = planned.map { it.id }.toSet()
            return (
                planned.map { it.copy(completedCount = byId[it.id]?.completedCount ?: 0) } +
                    old.filter { it.id !in ids && it.completedCount > 0 }.map { it.copy(count = 0) }
                ).sortedBy { it.id }
        }

        internal fun source(snapshot: JsonNode, date: String): OperatorDailyTaskSource {
            val revision = snapshot.path("revision").asLong()
            fun empty(status: String) = OperatorDailyTaskSource(status, revision)
            if (snapshot.path("schema_version").asInt() != 1 || snapshot.path("rules_version").asInt() != 13) return empty("unsupported")
            val schedule = snapshot.path("schedule")
            if (!schedule.isObject) return empty("no_schedule")
            val context = schedule.path("context")
            if (context.path("display_start_date").asText("") > date) return empty("no_day")
            val day = (schedule.path("history").toList() + schedule.path("result").path("timeline").toList())
                .find { it.path("date").asText() == date } ?: return empty("no_day")
            if (day.path("paused").asBoolean() || day.path("errors").size() > 0) return empty("invalid")
            val baseline = context.path("plan_baselines").path(date)
            if ((!baseline.isMissingNode && baseline != day.path("planned")) ||
                schedule.path("result").path("awaiting_recalculation").asBoolean()
            ) {
                return empty("needs_recalculation")
            }
            val spends = day.path("planned").path("spends")
            if (!spends.isArray) return empty("invalid")
            val items = mutableMapOf<String, OperatorTrainingTask>()
            for (spend in spends.filter { it.path("kind").asText() == "training" }) {
                val count = spend.path("value").asDouble(-1.0)
                if (count == 0.0) continue
                val group = spend.path("group_id").asText()
                val level = spend.path("stage_level").asDouble(-1.0)
                val maxLevel = when (group) {
                    "fh", "ds", "yy" -> 12
                    "experience" -> 8
                    else -> return empty("invalid")
                }
                if (count % 1 != 0.0 || count !in 1.0..6.0 || level % 1 != 0.0 || level !in 1.0..maxLevel.toDouble() ||
                    spend.path("cost_per").asDouble() != 20.0
                ) {
                    return empty("invalid")
                }
                val id = "$group-${level.toInt()}"
                items[id] = OperatorTrainingTask(id, group, level.toInt(), (items[id]?.count ?: 0) + count.toInt())
            }
            if (items.values.groupBy { it.groupId }.values.any { group -> group.sumOf { it.count } > 6 }) return empty("invalid")
            return OperatorDailyTaskSource("ready", revision, items.values.sortedBy { it.id })
        }
    }
}
