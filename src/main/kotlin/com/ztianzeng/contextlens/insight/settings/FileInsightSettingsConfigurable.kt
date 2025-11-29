package com.ztianzeng.contextlens.insight.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.ztianzeng.contextlens.insight.core.AgentPromptProvider
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class FileInsightSettingsConfigurable : Configurable {

    private val settings get() = FileInsightSettings.getInstance().current

    private val codexPathField = JBTextField()
    private val codexModelField = JBTextField()
    private val apiKeyField = JBTextField()
    private val apiUrlField = JBTextField()
    private val concurrencySpinner = JSpinner(SpinnerNumberModel(2, 1, 8, 1))
    private val outputRootField = JBTextField()
    private val overwriteCheck = JBCheckBox("Overwrite existing HTML output", true)
    private val logCheck = JBCheckBox("Write CLI logs to .analysis/logs", false)
    private val defaultAgentPrompt = AgentPromptProvider.defaultPrompt
    private val markdownLanguage = Language.findLanguageByID("Markdown") ?: PlainTextLanguage.INSTANCE
    private val agentPromptField = LanguageTextField(markdownLanguage, null, "", false)
    private val resetAgentPromptButton = JButton("Reset to defaults").apply {
        addActionListener { agentPromptField.text = defaultAgentPrompt }
    }
    private val agentPromptScroll = JBScrollPane(agentPromptField).apply {
        preferredSize = Dimension(0, JBUI.scale(220))
    }
    private val agentPromptPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty()
        add(agentPromptScroll, BorderLayout.CENTER)
        add(resetAgentPromptButton, BorderLayout.SOUTH)
    }

    private val formPanel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("CLI Path", codexPathField)
        .addLabeledComponent("Model", codexModelField)
        .addLabeledComponent("API Key", apiKeyField)
        .addLabeledComponent("API URL", apiUrlField)
        .addLabeledComponent("Max Concurrency", concurrencySpinner)
        .addLabeledComponent(".analysis Root", outputRootField)
        .addComponent(overwriteCheck)
        .addComponent(logCheck)
        .addLabeledComponent("Agent Prompt", agentPromptPanel)
        .panel

    private val panel: JPanel = JPanel(BorderLayout()).apply {
        add(formPanel, BorderLayout.NORTH)
    }

    override fun getDisplayName(): String = "ContextLens"

    override fun createComponent(): JComponent {
        reset()
        return panel
    }

    override fun isModified(): Boolean =
        codexPathField.text != settings.codexPath ||
                codexModelField.text != settings.codexModel ||
                apiKeyField.text != settings.apiKey ||
                apiUrlField.text != settings.apiUrl ||
                (concurrencySpinner.value as Int) != settings.maxConcurrency ||
                outputRootField.text != settings.analysisOutputRoot ||
                overwriteCheck.isSelected != settings.overwriteExistingOutput ||
                logCheck.isSelected != settings.enableCliLogs ||
                agentPromptField.text != getEffectiveAgentPrompt()

    override fun apply() {
        settings.codexPath = codexPathField.text
        settings.codexModel = codexModelField.text
        settings.apiKey = apiKeyField.text
        settings.apiUrl = apiUrlField.text
        settings.maxConcurrency = concurrencySpinner.value as Int
        settings.analysisOutputRoot = outputRootField.text
        settings.overwriteExistingOutput = overwriteCheck.isSelected
        settings.enableCliLogs = logCheck.isSelected
        settings.agentPrompt = toStoredAgentPrompt(agentPromptField.text)

        ProjectManager.getInstance().openProjects.forEach { project ->
            project.messageBus.syncPublisher(FileInsightSettings.TOPIC).onSettingsChanged(settings)
        }
    }

    override fun reset() {
        codexPathField.text = settings.codexPath
        codexModelField.text = settings.codexModel
        apiKeyField.text = settings.apiKey
        apiUrlField.text = settings.apiUrl
        concurrencySpinner.value = settings.maxConcurrency
        outputRootField.text = settings.analysisOutputRoot
        overwriteCheck.isSelected = settings.overwriteExistingOutput
        logCheck.isSelected = settings.enableCliLogs
        agentPromptField.text = getEffectiveAgentPrompt()
    }

    private fun getEffectiveAgentPrompt(): String {
        val stored = settings.agentPrompt
        return stored.takeIf { it.isNotBlank() } ?: defaultAgentPrompt
    }

    private fun toStoredAgentPrompt(value: String): String {
        val normalizedValue = normalizePrompt(value)
        if (normalizedValue.isBlank()) return ""
        val normalizedDefault = normalizePrompt(defaultAgentPrompt)
        return if (normalizedValue == normalizedDefault) "" else value
    }

    private fun normalizePrompt(value: String): String =
        value.replace("\r\n", "\n").trimEnd()
}
