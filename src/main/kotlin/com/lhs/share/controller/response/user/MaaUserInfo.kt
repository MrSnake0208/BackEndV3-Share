package com.lhs.share.controller.response.user

import com.lhs.share.repository.entity.MaaUser

/**
 * 用户可对外公开的信息
 *
 * 注意:activated = (status == 1) 是原项目的判定逻辑,保持口径一致
 */
data class MaaUserInfo(
    val id: String,
    val userName: String,
    val activated: Boolean = false,
    val followingCount: Int = 0,
    val fansCount: Int = 0,
) {
    constructor(user: MaaUser) : this(
        id = user.userId!!,
        userName = user.userName,
        activated = user.status == 1,
        followingCount = user.followingCount,
        fansCount = user.fansCount,
    )
}
