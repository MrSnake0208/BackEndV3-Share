package com.lhs.share.hub.repository.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class AdminAuditAction {
    ROLE_GRANTED,
    ROLE_REVOKED,
    ROLE_REPLACED,
    FEEDBACK_ACCESS_UPDATED,
    FEEDBACK_ACCESS_DELETED,
    CHANGELOG_PUBLISHED,
    CHANGELOG_REJECTED,
    CHANGELOG_WITHDRAWN,
}

data class AdminAuditSnapshot(
    val roles: Set<String>? = null,
    val receiveAreas: Set<String>? = null,
    val manageAreas: Set<String>? = null,
)

@Document("admin_audit_logs")
data class AdminAuditLog(
    @Id
    val id: String? = null,
    val actorUserId: String,
    val action: AdminAuditAction,
    val targetUserId: String? = null,
    val targetResource: String? = null,
    val before: AdminAuditSnapshot? = null,
    val after: AdminAuditSnapshot? = null,
    val occurredAt: Instant = Instant.now(),
    val requestId: String? = null,
)
