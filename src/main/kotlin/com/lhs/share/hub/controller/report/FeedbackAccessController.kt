package com.lhs.share.hub.controller.report

import com.lhs.share.config.doc.RequireJwt
import com.lhs.share.config.security.AuthenticationHelper
import com.lhs.share.controller.response.ApiResult
import com.lhs.share.controller.response.ApiResult.Companion.success
import com.lhs.share.hub.controller.report.request.FeedbackAccessUpdateRequest
import com.lhs.share.hub.controller.report.response.CurrentFeedbackAccessResponse
import com.lhs.share.hub.controller.report.response.FeedbackAccessGrantResponse
import com.lhs.share.hub.service.report.FeedbackAccessService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@RequireJwt
class FeedbackAccessController(
    private val service: FeedbackAccessService,
    private val helper: AuthenticationHelper,
) {
    @GetMapping("/v1/reports/access")
    fun current(): ApiResult<CurrentFeedbackAccessResponse> = success(service.current(helper.requireUserId()))

    @GetMapping("/v1/admin/feedback-access")
    fun list(): ApiResult<List<FeedbackAccessGrantResponse>> = success(service.listGrants(helper.requireUserId()))

    @PutMapping("/v1/admin/feedback-access/{userId}")
    fun update(@PathVariable userId: String, @RequestBody request: FeedbackAccessUpdateRequest): ApiResult<FeedbackAccessGrantResponse> {
        return success(service.updateGrant(helper.requireUserId(), userId, request))
    }

    @DeleteMapping("/v1/admin/feedback-access/{userId}")
    fun delete(@PathVariable userId: String): ApiResult<Unit> {
        service.deleteGrant(helper.requireUserId(), userId)
        return success()
    }
}
