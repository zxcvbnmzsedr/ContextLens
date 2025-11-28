package com.ztianzeng.contextlens.insight.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.ztianzeng.contextlens.insight.events.InsightTopics
import com.ztianzeng.contextlens.insight.model.CodexRequest
import com.ztianzeng.contextlens.insight.model.FileInsightResult
import java.util.UUID

@Service(Service.Level.PROJECT)
class FileInsightManager(
    private val project: Project
) {
    private val contextCollector = project.getService(ContextCollector::class.java)
    private val cliBridge = project.getService(CliInvocationBridge::class.java)
    private val storage = project.getService(InsightStorage::class.java)
    private val log = Logger.getInstance(FileInsightManager::class.java)

    fun requestInsight(psiFile: PsiFile, requestId: String = UUID.randomUUID().toString()) {
        object : Task.Backgroundable(project, "Analyzing ${psiFile.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                val publisher = project.messageBus.syncPublisher(InsightTopics.INSIGHT_EVENTS)
                indicator.text = "Collecting context"
                publisher.onProgress(project, requestId, indicator.text, 10)

                val context = contextCollector.collect(psiFile)
                val request = CodexRequest(
                    filePath = context.filePath,
                    language = context.language,
                    fileContent = context.fileContent,
                    extraContext = mapOf(
                        "module" to context.moduleName,
                        "neighbors" to context.neighbors,
                        "gitRevision" to context.gitRevision,
                        "notes" to context.notes
                    )
                )

                indicator.checkCanceled()
                indicator.text = "Invoking Codex CLI"
                publisher.onProgress(project, requestId, indicator.text, 40)
                val virtualFile = psiFile.virtualFile ?: return
                val analysisTarget = storage.resolveAnalysisTarget(virtualFile)
                var streamingProgress = 40
                val invocation = cliBridge.invoke(request, indicator, analysisTarget) { update ->
                    val clipped = update.take(200)
                    indicator.text = clipped
                    streamingProgress = (streamingProgress + 1).coerceAtMost(75)
                    publisher.onProgress(project, requestId, clipped, streamingProgress)
                }

                indicator.text = "Finalizing"
                publisher.onProgress(project, requestId, indicator.text, 80)

                val result = FileInsightResult(requestId, virtualFile, invocation.response)
                publisher.onResult(project, result)
            }

            override fun onThrowable(error: Throwable) {
                log.warn("Failed to analyze file", error)
                project.messageBus.syncPublisher(InsightTopics.INSIGHT_EVENTS)
                    .onError(project, requestId, error.message ?: "Unknown error")
            }

            override fun onCancel() {
                project.messageBus.syncPublisher(InsightTopics.INSIGHT_EVENTS)
                    .onError(project, requestId, "Analysis cancelled")
            }
        }.queue()
    }
}
