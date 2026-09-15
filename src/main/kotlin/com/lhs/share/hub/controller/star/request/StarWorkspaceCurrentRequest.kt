package com.lhs.share.hub.controller.star.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Full replacement request for the small, cloud-synced part of the star workspace. */
data class StarWorkspaceCurrentRequest(
    @field:NotNull(message = "expected_revision 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long?,
    @field:NotNull(message = "plan_targets 不能为空")
    @field:Size(max = 1000, message = "plan_targets 数量不能超过 1000")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val planTargets: Map<String, Int>?,
    @field:NotNull(message = "bag 不能为空")
    @field:Valid
    @field:JsonSetter(nulls = Nulls.FAIL)
    val bag: StarWorkspaceBagRequest?,
    @field:NotNull(message = "experience 不能为空")
    @field:Valid
    @field:JsonSetter(nulls = Nulls.FAIL)
    val experience: StarWorkspaceExperienceRequest?,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star workspace field: $name")
    }
}

data class StarWorkspaceBagRequest(
    val currentCount: Int? = null,
    val capacity: Int? = null,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star workspace bag field: $name")
    }
}

data class StarWorkspaceExperienceRequest(
    val orange: Int? = null,
    val purple: Int? = null,
    val white: Int? = null,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star workspace experience field: $name")
    }
}
