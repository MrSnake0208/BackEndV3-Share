package com.lhs.share.hub.controller.account.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import jakarta.validation.constraints.NotNull

/** A one-scope command; never replaces another scope from a stale client snapshot. */
data class AccountShareScopeRequest(@field:NotNull val scope: String?, @field:NotNull val enabled: Boolean?) {
    @JsonAnySetter fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown share field: $name")
    }
}
