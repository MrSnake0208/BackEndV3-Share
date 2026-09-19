package com.lhs.share.hub.controller.star.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/** Dedicated replacement-import body. Loadouts and user-global presets are intentionally absent. */
data class StarExchangeReplaceRequest(
    @field:NotBlank(message = "account_id 不能为空")
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$", message = "account_id 格式无效")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val accountId: String,
    @field:Valid
    @field:NotNull(message = "inventory 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val inventory: StarExchangeReplaceInventoryRequest?,
    @field:Valid
    @field:NotNull(message = "workspace 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val workspace: StarWorkspaceCurrentRequest?,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star exchange replacement field: $name")
    }
}

data class StarExchangeReplaceInventoryRequest(
    @field:NotNull(message = "expected_revision 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long?,
    @field:NotBlank(message = "effective_at 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val effectiveAt: String,
    @field:Valid
    @field:NotNull(message = "entries 不能为空")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val entries: List<StarInventoryEntryRequest>?,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star exchange inventory field: $name")
    }
}
