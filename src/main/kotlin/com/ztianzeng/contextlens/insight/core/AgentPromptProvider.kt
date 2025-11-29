package com.ztianzeng.contextlens.insight.core

import com.intellij.openapi.diagnostic.Logger
import com.ztianzeng.contextlens.insight.settings.FileInsightSettings
import java.nio.charset.StandardCharsets

object AgentPromptProvider {
    private val log = Logger.getInstance(AgentPromptProvider::class.java)

    val defaultPrompt: String by lazy {
        val loaded = loadDefaultPrompt()
        require(loaded.isNotBlank()) { "prompts/AGENTS.md is missing or empty" }
        loaded
    }

    val prompt: String
        get() {
            val override = FileInsightSettings.getInstance().current.agentPrompt
            return override.takeIf { it.isNotBlank() } ?: defaultPrompt
        }

    private fun loadDefaultPrompt(): String {
        val stream = AgentPromptProvider::class.java.classLoader
            ?.getResourceAsStream("prompts/AGENTS.md")
        if (stream == null) {
            log.warn("Failed to load default AGENTS.md from resources")
            return ""
        }
        return stream.bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText().trim() }
    }
}
