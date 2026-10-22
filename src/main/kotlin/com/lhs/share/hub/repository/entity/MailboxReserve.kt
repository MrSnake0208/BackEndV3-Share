package com.lhs.share.hub.repository.entity

import java.time.Instant

data class WhiteCoinMail(val count: Int, val coinsPerMail: Int)

data class MailboxReserve(
    val whiteCoinMails: List<WhiteCoinMail> = emptyList(),
    val fuChuanMailCount: Int = 0,
    val version: Long = 0,
    val updatedAt: Instant? = null,
)
