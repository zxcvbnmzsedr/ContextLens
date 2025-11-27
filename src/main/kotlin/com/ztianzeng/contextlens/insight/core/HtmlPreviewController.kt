package com.ztianzeng.contextlens.insight.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.ztianzeng.contextlens.insight.events.InsightTopics
import com.ztianzeng.contextlens.insight.model.CodexResponse
import com.ztianzeng.contextlens.insight.model.FileInsightResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Service(Service.Level.PROJECT)
class HtmlPreviewController(private val project: Project) : FileEditorManagerListener, Disposable {
    private val log = Logger.getInstance(HtmlPreviewController::class.java)
    private val storage = project.getService(InsightStorage::class.java)
    private val connection = project.messageBus.connect(this)

    init {
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val file = event.newFile ?: return
        if (file.isDirectory || !file.isValid || storage.isAnalysisOutput(file)) {
            return
        }
        val htmlPath = storage.findExistingAnalysisFile(file)
        val requestId = "preview:${file.path}"
        val publisher = project.messageBus.syncPublisher(InsightTopics.INSIGHT_EVENTS)
        if (htmlPath == null) {
            publisher.onError(project, requestId, "未找到该文件的 HTML 分析缓存")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = try {
                Files.readString(htmlPath, StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                log.warn("Failed to read html file ${'$'}htmlPath", ex)
                publisher.onError(project, requestId, "读取 HTML 失败：${'$'}{ex.message}")
                return@executeOnPooledThread
            }
            val response = CodexResponse(status = "cached", raw = html)
            val result = FileInsightResult(requestId, file, response)
            publisher.onResult(project, result)
        }
    }

    override fun dispose() {
        connection.dispose()
    }
}
