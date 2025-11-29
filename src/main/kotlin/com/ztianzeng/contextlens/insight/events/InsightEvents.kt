package com.ztianzeng.contextlens.insight.events

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.ztianzeng.contextlens.insight.model.FileInsightResult

interface InsightEventListener {
    fun onProgress(project: Project, requestId: String, message: String, percentage: Int) {}
    fun onResult(project: Project, result: FileInsightResult) {}
    fun onError(
        project: Project,
        requestId: String,
        error: String,
        code: String? = null,
        context: Map<String, Any?> = emptyMap()
    ) {
    }
}

object InsightTopics {
    val INSIGHT_EVENTS: Topic<InsightEventListener> =
        Topic.create("ContextLens Events", InsightEventListener::class.java)
}

/**
 * Error codes for insight events, shared between backend and frontend.
 */
object InsightErrorCodes {
    const val HTML_CACHE_MISSING = "HTML_CACHE_MISSING"
    const val HTML_CACHE_READ_FAILED = "HTML_CACHE_READ_FAILED"
}
