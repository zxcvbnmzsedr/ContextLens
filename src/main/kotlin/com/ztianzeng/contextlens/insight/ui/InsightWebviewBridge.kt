package com.ztianzeng.contextlens.insight.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.handler.CefLoadHandlerAdapter
import com.ztianzeng.contextlens.insight.core.FileInsightManager
import com.ztianzeng.contextlens.insight.events.InsightEventListener
import com.ztianzeng.contextlens.insight.events.InsightTopics
import com.ztianzeng.contextlens.insight.model.FileInsightResult
import com.ztianzeng.contextlens.insight.settings.FileInsightSettings

class InsightWebviewBridge(
    private val project: Project,
    private val browser: JBCefBrowserBase
) : Disposable, InsightEventListener {
    private val gson = Gson()
    private val jsQuery = JBCefJSQuery.create(browser)
    private val connection = project.messageBus.connect()

    init {
        connection.subscribe(InsightTopics.INSIGHT_EVENTS, this)
        jsQuery.addHandler { payload ->
            handleIncoming(payload)
            null
        }
        injectBridge()
    }

    private fun injectBridge() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                val script = """
                window.__CONTEXTLENS__ = window.__CONTEXTLENS__ || {};
                window.__CONTEXTLENS__.receive = function(payload) {
                    window.dispatchEvent(new CustomEvent('contextlens-insight', { detail: payload }));
                };
                window.intellijApi = {
                    postMessage: function(payload) {
                        ${jsQuery.inject("payload")}
                    }
                };
            """.trimIndent()
                browser?.executeJavaScript(script, browser.url, 0)
                publishConfig()
            }
        }, browser.cefBrowser)
    }

    private fun publishConfig() {
        val config = mapOf(
            "settings" to FileInsightSettings.getInstance().current,
            "version" to "0.1.0",
            "projectRoot" to (project.basePath ?: "")
        )
        send("insight:init", "init", config)
    }

    private fun handleIncoming(payload: String) {
        val json = JsonParser.parseString(payload).asJsonObject
        val event = json.get("event")?.asString ?: return
        val requestId = json.get("requestId")?.asString ?: "ui"
        val body = json.getAsJsonObject("body") ?: JsonObject()
        when (event) {
            "insight:navigate" -> navigate(body)
            "insight:refresh" -> refresh(body)
            else -> send("insight:error", requestId, mapOf("message" to "Unsupported event $event"))
        }
    }

    private fun navigate(body: JsonObject) {
        val path = body.get("file")?.asString ?: return
        val line = body.get("line")?.asInt ?: 0
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vf, line.coerceAtLeast(0), 0).navigate(true)
        }
    }

    private fun refresh(body: JsonObject) {
        val path = body.get("filePath")?.asString ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) ?: return
        project.getService(FileInsightManager::class.java).requestInsight(psiFile)
    }

    override fun onProgress(project: Project, requestId: String, message: String, percentage: Int) {
        send("insight:progress", requestId, mapOf("message" to message, "percentage" to percentage))
    }

    override fun onResult(project: Project, result: FileInsightResult) {
        send("insight:update", result.requestId, result.response)
    }

    override fun onError(project: Project, requestId: String, error: String) {
        send("insight:error", requestId, mapOf("message" to error))
    }

    private fun send(event: String, requestId: String, body: Any) {
        val payload = gson.toJson(mapOf("event" to event, "requestId" to requestId, "body" to body))
        ApplicationManager.getApplication().invokeLater {
            browser.cefBrowser.executeJavaScript(
                "window.__CONTEXTLENS__ && window.__CONTEXTLENS__.receive($payload);",
                browser.cefBrowser.url,
                0
            )
        }
    }

    override fun dispose() {
        connection.disconnect()
        jsQuery.dispose()
    }
}
