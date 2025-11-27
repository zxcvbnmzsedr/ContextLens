package com.ztianzeng.contextlens.insight.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.JPanel

class InsightWebviewPanel(project: Project) : Disposable {
    private val url = resolveWebviewUrl()
    private val browser = JBCefBrowser(url)
    private val bridge = InsightWebviewBridge(project, browser)

    val component = JPanel(BorderLayout()).apply {
        add(browser.component, BorderLayout.CENTER)
        background = UIUtil.getPanelBackground()
    }

    override fun dispose() {
        bridge.dispose()
        browser.dispose()
    }

    companion object {
        private val log = Logger.getInstance(InsightWebviewPanel::class.java)

        private fun resolveWebviewUrl(): String {
            val devServer = System.getProperty("contextlens.webview.devserver")?.takeIf { it.isNotBlank() }
            return devServer ?: ""
        }
    }
}
