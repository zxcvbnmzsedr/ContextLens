package com.ztianzeng.contextlens.insight.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.ztianzeng.contextlens.insight.core.HtmlPreviewController

class InsightToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow is ToolWindowEx) {
            toolWindow.setSplitMode(false, null)
        }
        // Touch project service so the file-selection listener starts pushing cached HTML.
        project.getService(HtmlPreviewController::class.java)
        val view = InsightWebviewPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(view.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
