package com.lhs.share.hub.service.operator

import org.springframework.http.HttpStatus

class OperatorApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val recordId: String? = null,
    val entryId: String? = null,
) : RuntimeException(message)
