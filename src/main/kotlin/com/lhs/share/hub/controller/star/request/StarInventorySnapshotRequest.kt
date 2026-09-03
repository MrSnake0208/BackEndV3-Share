package com.lhs.share.hub.controller.star.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** YuanStar 当前背包快照 PUT 正文。用户身份只从 JWT 获取。 */
data class StarInventorySnapshotRequest(
    @field:NotBlank(message = "effective_at 不能为空")
    @field:Schema(format = "date-time", example = "2026-08-31T10:00:00.000Z")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val effectiveAt: String,
    @field:Valid
    @field:Size(max = 1000, message = "entries 数量不能超过 1000")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val entries: List<StarInventoryEntryRequest>,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star inventory field: $name")
    }
}

data class StarInventoryEntryRequest(
    @field:NotBlank(message = "instance_id 不能为空")
    @field:Size(min = 1, max = 128, message = "instance_id 长度须在 1..128")
    @field:Pattern(
        regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
        message = "instance_id 格式无效",
    )
    @field:JsonSetter(nulls = Nulls.FAIL)
    val instanceId: String,
    @field:NotBlank(message = "kind 不能为空")
    @field:Pattern(regexp = "^(main|support)$", message = "kind 仅支持 main 或 support")
    @field:Schema(allowableValues = ["main", "support"])
    @field:JsonSetter(nulls = Nulls.FAIL)
    val kind: String,
    @field:NotBlank(message = "name 不能为空")
    @field:Size(max = 256, message = "name 长度不能超过 256")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val name: String,
    @field:NotBlank(message = "quality 不能为空")
    @field:Pattern(
        regexp = "^(orange|purple|blue|green|white)$",
        message = "quality 仅支持 orange、purple、blue、green、white",
    )
    @field:Schema(allowableValues = ["orange", "purple", "blue", "green", "white"])
    @field:JsonSetter(nulls = Nulls.FAIL)
    val quality: String,
    @field:NotNull(message = "level 不能为空")
    @field:Min(value = 0, message = "level 不能为负")
    @field:Max(value = 60, message = "level 不能超过 60")
    @field:Schema(minimum = "0", maximum = "60")
    @field:JsonSetter(nulls = Nulls.FAIL)
    val level: Int?,
) {
    @JsonAnySetter
    fun rejectUnknownField(name: String, value: Any?) {
        throw IllegalArgumentException("Unknown star inventory entry field: $name")
    }
}
