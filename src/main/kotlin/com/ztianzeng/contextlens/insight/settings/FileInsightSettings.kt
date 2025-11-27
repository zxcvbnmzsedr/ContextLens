package com.ztianzeng.contextlens.insight.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

@State(name = "FileInsightSettings", storages = [Storage("contextLens.xml")])
class FileInsightSettings : PersistentStateComponent<FileInsightState> {

    private var data = FileInsightState()

    val current: FileInsightState
        get() = data

    override fun getState(): FileInsightState = data

    override fun loadState(state: FileInsightState) {
        data = state
    }

    companion object {
        val TOPIC: Topic<FileInsightSettingsListener> =
            Topic.create("ContextLens Settings", FileInsightSettingsListener::class.java)

        fun getInstance(): FileInsightSettings = service()
    }
}

data class FileInsightState(
    var codexPath: String = "codex",
    var codexModel: String = "code-navigator",
    var apiKey: String = "",
    var apiUrl: String = "",
    var maxConcurrency: Int = 2,
    var requestTimeoutSec: Int = 120,
    var analysisOutputRoot: String = ".analysis",
    var overwriteExistingOutput: Boolean = true,
    var enableCliLogs: Boolean = false,
    var enableGitDiff: Boolean = true
)

fun interface FileInsightSettingsListener {
    fun onSettingsChanged(state: FileInsightState)
}
