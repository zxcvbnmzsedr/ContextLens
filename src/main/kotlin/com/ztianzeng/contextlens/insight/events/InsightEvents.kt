package com.ztianzeng.contextlens.insight.events

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.ztianzeng.contextlens.insight.model.FileInsightResult

interface InsightEventListener {
    fun onProgress(project: Project, requestId: String, message: String, percentage: Int) {}
    fun onResult(project: Project, result: FileInsightResult) {}
    fun onError(project: Project, requestId: String, error: String) {}
}

object InsightTopics {
    val INSIGHT_EVENTS: Topic<InsightEventListener> =
        Topic.create("ContextLens Events", InsightEventListener::class.java)
}
