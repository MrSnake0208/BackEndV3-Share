package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lhs.share.hub.service.inventory.EntityCatalogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Validates persistence, not a second simulator. Infeasible user drafts remain saveable. */
@Component
class OperatorPlannerValidator(
    private val catalog: OperatorCatalogService,
    private val items: EntityCatalogService,
    private val mapper: ObjectMapper,
) {
    fun emptyWorkspace(): ObjectNode = mapper.readTree(
        """{"schema_version":1,"active_plan_id":"favorites","training_levels":{"fh":12,"ds":12,"yy":12,"experience":8},
            "plans":[{"id":"favorites","name":"特别关注","source":"favorites","operator_ids":[],
            "excluded_operator_ids":[],"targets":{}}]}""",
    ) as ObjectNode

    fun emptySchedule(): ObjectNode = mapper.readTree(
        """{"schema_version":1,"rules_version":13,"timezone":"Asia/Shanghai","strategy":"overall","agent_order":[],
            "preferences":{"luoyang":0,"shouchun":0,"purchase_count":0},"manual_plans":{},"schedule":null}""",
    ) as ObjectNode

    fun workspace(request: ObjectNode): ObjectNode {
        fields(request, setOf("schema_version", "active_plan_id", "training_levels", "plans", "expected_revision"))
        version(request)
        revision(request)
        levels(request.path("training_levels"))
        val plans = array(request.path("plans"), "plans")
        val ids = mutableSetOf<String>()
        plans.forEach { plan ->
            fields(plan, setOf("id", "name", "source", "operator_ids", "excluded_operator_ids", "targets"))
            val id = planId(plan.path("id").asText())
            if (!ids.add(id)) invalid("Duplicate plan id", "plans")
            val name = text(plan.path("name"), "plans.name").trim()
            if (name.length !in 1..40) invalid("Plan name must contain 1..40 characters", "plans.name")
            (plan as ObjectNode).put("name", name)
            val source = if (id == "favorites") "favorites" else "custom"
            if (plan.path("source").asText() != source) invalid("Invalid plan source", "plans.source")
            stringIds(plan.path("operator_ids"), "plans.operator_ids").forEach(::operator)
            stringIds(plan.path("excluded_operator_ids"), "plans.excluded_operator_ids").forEach(::operator)
            val targets = obj(plan.path("targets"), "plans.targets")
            if (source == "favorites" && !targets.isEmpty) invalid("Favorites targets use growth-targets", "plans.targets")
            targets.fields().forEach { (operatorId, target) ->
                operator(operatorId)
                fields(target, setOf("level", "elite", "star_level"))
                integer(target.path("level"), "level", 0, 100)
                integer(target.path("elite"), "elite", 0, 17)
                integer(target.path("star_level"), "star_level", 0, 31)
                // Existing current entries can exceed the displayed level/elite relation.
                // Keep their saved targets intact; the client derives effective display limits.
            }
        }
        if ("favorites" !in ids ||
            request.path("active_plan_id").asText() !in ids
        ) {
            invalid("Missing default or active plan", "active_plan_id")
        }
        return request.deepCopy().also { it.remove("expected_revision") }
    }

    fun schedule(request: ObjectNode): ObjectNode {
        fields(
            request,
            setOf(
                "schema_version", "rules_version", "timezone", "strategy", "agent_order", "preferences",
                "manual_plans", "schedule", "expected_revision",
            ),
        )
        version(request)
        revision(request)
        integer(request.path("rules_version"), "rules_version", 13, 13)
        zone(request.path("timezone"))
        strategy(request.path("strategy"))
        stringIds(request.path("agent_order"), "agent_order").forEach(::operator)
        preferences(request.path("preferences"))
        obj(request.path("manual_plans"), "manual_plans").fields().forEach { (day, plan) ->
            date(day)
            dailyPlan(plan)
        }
        if (!request.has("schedule")) invalid("schedule is required (null for an unfixed plan)", "schedule")
        if (!request.path("schedule").isNull) fixedSchedule(request.path("schedule"))
        return request.deepCopy().also { it.remove("expected_revision") }
    }

    fun preserveHistory(previous: ObjectNode?, next: ObjectNode) {
        if (previous == null || previous.path("schedule").isNull) return
        if (previous.path("timezone") != next.path("timezone")) invalid("A saved schedule keeps its timezone", "timezone")
        if (next.path("schedule").isNull) invalid("A fixed schedule cannot be silently cleared", "schedule")
        val today = LocalDate.now(zone(previous.path("timezone")))
        val before = days(previous.path("schedule"))
        val after = days(next.path("schedule")).associateBy { it.path("date").asText() }
        before.filter { date(it.path("date").asText()) < today }.forEach { day ->
            if (after[day.path("date").asText()] != day) invalid("Saved past days must remain unchanged", "schedule.history")
        }
    }

    fun revision(request: JsonNode, field: String = "expected_revision"): Long = integer(request.path(field), field, 0, Long.MAX_VALUE - 1)

    fun planId(value: String): String {
        if (value != "favorites" && runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false).not()) {
            invalid("Plan id must be favorites or UUID", "plan_id")
        }
        return value
    }

    fun operator(id: String) {
        if (catalog.getOperator(id) == null) invalid("Unknown operator: $id", "operator_id")
    }

    fun fields(node: JsonNode, allowed: Set<String>) {
        obj(node, "request")
        node.fieldNames().forEach { if (it !in allowed) invalid("Unknown field: $it", it) }
    }

    private fun version(request: JsonNode) {
        if (!request.path("schema_version").isIntegralNumber || request.path("schema_version").intValue() != 1) {
            throw OperatorApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unsupported_training_workspace_version",
                "Only schema_version 1 is supported",
            )
        }
    }

    private fun fixedSchedule(node: JsonNode) {
        fields(node, setOf("version", "start_date", "baseline_date", "context", "history", "result"))
        integer(node.path("version"), "schedule.version", 1, 1)
        val start = date(text(node.path("start_date"), "start_date"))
        val baseline = date(text(node.path("baseline_date"), "baseline_date"))
        if (start > baseline) invalid("start_date is after baseline_date", "start_date")
        val context = obj(node.path("context"), "context")
        if (date(text(context.path("date"), "context.date")) != baseline) invalid("Context date must match baseline", "context.date")
        state(context.path("initial_state"))
        state(context.path("required_state"))
        quantities(context.path("stock"))
        obj(context.path("goals"), "goals").forEach { goal ->
            if (!goal.isArray || goal.size() != 3) invalid("Goal must contain level, elite and star level", "goals")
            integer(goal[0], "goals.level", 0, 100)
            integer(goal[1], "goals.elite", 0, 17)
            integer(goal[2], "goals.star_level", 0, 31)
        }
        levels(context.path("levels"))
        strategy(context.path("strategy"))
        stringIds(context.path("agent_order"), "context.agent_order")
        preferences(context.path("preferences"))
        val history = array(node.path("history"), "history")
        val result = obj(node.path("result"), "result")
        val timeline = array(result.path("timeline"), "timeline")
        if (result.path("status").asText() !in
            setOf("complete", "invalid", "blocked", "horizon")
        ) {
            invalid("Invalid simulation status", "result.status")
        }
        if (!result.path("eta_days").isNull) integer(result.path("eta_days"), "eta_days", 0, 90)
        state(result.path("remaining"))
        array(result.path("blocked"), "blocked")
        val all = history.toList() + timeline.toList()
        val dates = all.map { date(text(it.path("date"), "day.date")) }
        if (dates != dates.distinct().sorted() ||
            dates.firstOrNull()?.let { it < start } == true
        ) {
            invalid("Day dates must be unique and ordered", "timeline")
        }
        if (history.any { date(it.path("date").asText()) >= baseline } || timeline.any { date(it.path("date").asText()) < baseline }) {
            invalid("History and future dates must match the baseline", "baseline_date")
        }
        all.forEach { day ->
            state(day.path("start"))
            state(day.path("end"))
            dailyPlan(day.path("planned"))
            dailyPlan(day.path("recommended"))
            quantities(day.path("yield"))
            quantities(day.path("used"))
            quantities(day.path("surplus"))
            array(day.path("errors"), "errors")
            array(day.path("warnings"), "warnings")
            array(day.path("progress_rows"), "progress_rows")
            if (!day.path("errors").isEmpty || day.path("paused").asBoolean()) {
                if (day.path("end") != day.path("start") || day.path("yield").any { it.asDouble() != 0.0 } ||
                    day.path("used").any { it.asDouble() != 0.0 } || day.path("surplus").any { it.asDouble() != 0.0 }
                ) {
                    invalid("Paused or invalid days cannot produce progress", "timeline")
                }
            }
        }
        finiteTree(node)
    }

    private fun dailyPlan(node: JsonNode) {
        fields(node, setOf("gains", "spends"))
        array(node.path("gains"), "gains").forEach { gain ->
            text(gain.path("id"), "gain.id")
            text(gain.path("label"), "gain.label")
            number(gain.path("value"), "gain.value")
            if (gain.path("kind").asText() !in setOf("count", "energy")) invalid("Invalid gain kind", "gain.kind")
        }
        array(node.path("spends"), "spends").forEach { spend ->
            text(spend.path("id"), "spend.id")
            text(spend.path("label"), "spend.label")
            number(spend.path("value"), "spend.value")
            number(spend.path("cost_per"), "spend.cost_per")
            if (spend.path("kind").asText() !in
                setOf("count", "training", "stage624", "custom")
            ) {
                invalid("Invalid spend kind", "spend.kind")
            }
            quantities(spend.path("yield"))
            if (spend.path("custom").asBoolean()) {
                spend.path("yield").fieldNames().forEach { id ->
                    if (id != "__xp__" && !items.exists("item", id)) invalid("Unknown custom output: $id", "spend.yield")
                }
            }
        }
        finiteTree(node)
    }

    private fun levels(node: JsonNode) {
        fields(node, setOf("fh", "ds", "yy", "experience"))
        listOf("fh", "ds", "yy").forEach { integer(node.path(it), it, 1, 12) }
        integer(node.path("experience"), "experience", 1, 8)
    }

    private fun preferences(node: JsonNode) {
        fields(node, setOf("luoyang", "shouchun", "purchase_count"))
        integer(node.path("luoyang"), "luoyang", 0, 4)
        integer(node.path("shouchun"), "shouchun", 0, 4)
        integer(node.path("purchase_count"), "purchase_count", 0, 8)
    }

    private fun strategy(node: JsonNode) {
        if (node.asText() !in setOf("overall", "priority")) invalid("Invalid strategy", "strategy")
    }

    private fun state(node: JsonNode) = obj(node, "state").forEach { quantities(it) }

    private fun quantities(node: JsonNode) = obj(node, "quantities").fields().forEach { (key, value) -> number(value, key) }

    private fun number(node: JsonNode, field: String) {
        if (!node.isNumber || !node.asDouble().isFinite() || node.asDouble() < 0) invalid("Expected a finite non-negative number", field)
    }

    private fun finiteTree(node: JsonNode) {
        if (node.isNumber && !node.asDouble().isFinite()) invalid("Non-finite number", "snapshot")
        if (node.isContainerNode) node.forEach(::finiteTree)
    }

    private fun integer(node: JsonNode, field: String, min: Long, max: Long): Long {
        if (!node.isIntegralNumber || !node.canConvertToLong() ||
            node.longValue() !in min..max
        ) {
            invalid("Expected integer $min..$max", field)
        }
        return node.longValue()
    }

    private fun stringIds(node: JsonNode, field: String): List<String> {
        val ids = array(node, field).map { text(it, field) }
        if (ids.distinct().size != ids.size) invalid("Duplicate IDs", field)
        return ids
    }

    private fun text(node: JsonNode, field: String): String {
        if (!node.isTextual || node.asText().isBlank()) invalid("Expected nonempty text", field)
        return node.asText()
    }

    private fun obj(node: JsonNode, field: String): ObjectNode = node as? ObjectNode ?: invalid("Expected object", field)
    private fun array(node: JsonNode, field: String): JsonNode = node.takeIf { it.isArray } ?: invalid("Expected array", field)
    private fun date(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse { invalid("Invalid date", "date") }
    private fun zone(node: JsonNode): ZoneId =
        runCatching { ZoneId.of(text(node, "timezone")) }.getOrElse { invalid("Invalid timezone", "timezone") }
    private fun days(node: JsonNode): List<JsonNode> = node.path("history").toList() + node.path("result").path("timeline").toList()

    fun invalid(message: String, field: String): Nothing = throw OperatorApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "invalid_training_workspace",
        message,
        fieldPath = field,
    )
}
