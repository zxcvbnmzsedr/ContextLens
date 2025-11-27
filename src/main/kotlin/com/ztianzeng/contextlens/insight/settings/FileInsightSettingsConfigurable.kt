package com.ztianzeng.contextlens.insight.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
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
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(120, 30, 600, 10))
    private val outputRootField = JBTextField()
    private val overwriteCheck = JBCheckBox("Overwrite existing HTML output", true)
    private val logCheck = JBCheckBox("Write CLI logs to .analysis/logs", false)

    private val formPanel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("CLI Path", codexPathField)
        .addLabeledComponent("Model", codexModelField)
        .addLabeledComponent("API Key", apiKeyField)
        .addLabeledComponent("API URL", apiUrlField)
        .addLabeledComponent("Max Concurrency", concurrencySpinner)
        .addLabeledComponent("Timeout (sec)", timeoutSpinner)
        .addLabeledComponent(".analysis Root", outputRootField)
        .addComponent(overwriteCheck)
        .addComponent(logCheck)
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
            (timeoutSpinner.value as Int) != settings.requestTimeoutSec ||
            outputRootField.text != settings.analysisOutputRoot ||
            overwriteCheck.isSelected != settings.overwriteExistingOutput ||
            logCheck.isSelected != settings.enableCliLogs

    override fun apply() {
        settings.codexPath = codexPathField.text
        settings.codexModel = codexModelField.text
        settings.apiKey = apiKeyField.text
        settings.apiUrl = apiUrlField.text
        settings.maxConcurrency = concurrencySpinner.value as Int
        settings.requestTimeoutSec = timeoutSpinner.value as Int
        settings.analysisOutputRoot = outputRootField.text
        settings.overwriteExistingOutput = overwriteCheck.isSelected
        settings.enableCliLogs = logCheck.isSelected

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
        timeoutSpinner.value = settings.requestTimeoutSec
        outputRootField.text = settings.analysisOutputRoot
        overwriteCheck.isSelected = settings.overwriteExistingOutput
        logCheck.isSelected = settings.enableCliLogs
    }
}
