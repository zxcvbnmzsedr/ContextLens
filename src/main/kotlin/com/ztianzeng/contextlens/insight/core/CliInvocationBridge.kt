package com.ztianzeng.contextlens.insight.core

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.ztianzeng.contextlens.insight.model.CodexRequest
import com.ztianzeng.contextlens.insight.model.CodexResponse
import com.ztianzeng.contextlens.insight.settings.FileInsightSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CliInvocationBridge(
    private val project: Project
) {
    private val gson = Gson()
    private val log = Logger.getInstance(CliInvocationBridge::class.java)
    private val settings get() = FileInsightSettings.getInstance().current
    private val storage = project.getService(InsightStorage::class.java)

    fun invoke(request: CodexRequest, indicator: ProgressIndicator, target: AnalysisTarget?): CliInvocationResult {
        val state = settings
        val cliHome = prepareCliHome()
        val outputPlan = resolveOutputFile(target)
        val commandLine = buildCommand(state.codexPath, state.codexModel, outputPlan.outputFile)

        try {
            val processBuilder = ProcessBuilder(commandLine)
            setupEnvironment(processBuilder, state.apiKey, state.apiUrl, cliHome, state.codexPath)
            log.info("Executing Codex command: ${commandLine.joinToString(" ")}")

            val process = processBuilder.start()
            process.outputStream.use { output ->
                val json = gson.toJson(request)
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }

            waitForCompletion(process, indicator)

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (stderr.isNotBlank()) {
                storage.appendLog("stderr", stderr)
                log.warn("Codex stderr: $stderr")
            }

            if (process.exitValue() != 0) {
                throw IllegalStateException("Codex CLI returned exit ${process.exitValue()}: $stderr")
            }

            val raw = loadOutput(outputPlan.outputFile, stdout)
            val response = CodexResponse(status = "ok", raw = raw)

            return CliInvocationResult(
                response = response,
                commandLine = commandLine.toList(),
                rawOutput = raw,
                wroteToTarget = !outputPlan.cleanup
            )
        } finally {
            if (outputPlan.cleanup) {
                try {
                    Files.deleteIfExists(outputPlan.outputFile)
                } catch (ex: Exception) {
                    log.warn("Failed to delete temporary Codex output ${outputPlan.outputFile}", ex)
                }
            }
        }
    }

    private fun waitForCompletion(process: Process, indicator: ProgressIndicator) {
        try {
            while (true) {
                indicator.checkCanceled()
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    return
                }
            }
        } catch (ex: ProcessCanceledException) {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            throw ex
        }
    }

    private fun buildCommand(
        codexPath: String,
        model: String,
        outputFile: Path
    ): MutableList<String> = mutableListOf<String>().apply {
        add(codexPath)
        addAll(defaultArguments(model, outputFile))
    }

    private fun defaultArguments(model: String, outputFile: Path): List<String> = listOf(
        "exec",
        "--model",
        model,
        "-o",
        outputFile.toAbsolutePath().toString(),
        "-"
    )

    private fun setupEnvironment(
        processBuilder: ProcessBuilder,
        apiKey: String,
        apiUrl: String,
        cliHome: Path,
        codexPath: String
    ) {
        val env = processBuilder.environment()
        val resolvedKey = apiKey.trim().ifBlank { System.getenv("CODEX_API_KEY")?.trim().orEmpty() }
        if (resolvedKey.isBlank()) {
            log.warn("CODEX_API_KEY not configured; invocation will likely fail")
        } else {
            env["CODEX_API_KEY"] = resolvedKey
        }

        if (apiUrl.isNotBlank()) {
            env["OPENAI_BASE_URL"] = apiUrl
            env["CODEX_API_URL"] = apiUrl
        }
        env["CODEX_HOME"] = cliHome.toString()
        extendPathIfNeeded(env, codexPath)
        processBuilder.directory(project.basePath?.let { File(it) })
    }

    private fun extendPathIfNeeded(env: MutableMap<String, String>, codexPath: String) {
        val codexParent = try {
            val path = Path.of(codexPath)
            if (path.isAbsolute) path.parent else null
        } catch (_: Exception) {
            null
        }
        if (codexParent == null) return
        val current = env["PATH"].orEmpty()
        val separator = File.pathSeparator
        val segments = current.split(separator).filter { it.isNotBlank() }.toMutableList()
        if (!segments.contains(codexParent.toString())) {
            segments.add(codexParent.toString())
            env["PATH"] = segments.joinToString(separator)
        }
    }

    private fun prepareCliHome(): Path {
        val userHome = System.getProperty("user.home") ?: error("user.home not set")
        val home = Path.of(userHome).resolve(".codex").resolve("analysis")
        Files.createDirectories(home)
        syncAgentsFile(home)
        return home
    }

    private fun syncAgentsFile(targetHome: Path) {
        val prompt = AgentPromptProvider.prompt
        if (prompt.isBlank()) {
            log.warn("Agent prompt is blank; skipping AGENTS.md sync")
            return
        }
        val target = targetHome.resolve("AGENTS.md")
        try {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                prompt,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        } catch (ex: Exception) {
            log.warn("Failed to sync AGENTS.md to $target", ex)
        }
    }

    private fun resolveOutputFile(target: AnalysisTarget?): OutputPlan {
        val state = settings
        if (target == null) {
            return OutputPlan(Files.createTempFile("codex-output", ".json"), cleanup = true)
        }
        val shouldOverwrite = state.overwriteExistingOutput || !Files.exists(target.outputFile)
        return if (shouldOverwrite) {
            OutputPlan(target.outputFile, cleanup = false)
        } else {
            OutputPlan(Files.createTempFile("codex-output", ".json"), cleanup = true)
        }
    }

    private fun loadOutput(outputFile: Path, stdoutFallback: String): String {
        if (Files.exists(outputFile)) {
            return Files.readString(outputFile, StandardCharsets.UTF_8)
        }
        if (stdoutFallback.isNotBlank()) {
            log.warn("Output file $outputFile missing, falling back to stdout content")
            return stdoutFallback
        }
        throw IllegalStateException("Codex CLI did not produce output file at $outputFile")
    }
}

data class CliInvocationResult(
    val response: CodexResponse,
    val commandLine: List<String>,
    val rawOutput: String,
    val wroteToTarget: Boolean
)

private data class OutputPlan(
    val outputFile: Path,
    val cleanup: Boolean
)

private object AgentPromptProvider {
    val prompt: String by lazy {
        CliInvocationBridge::class.java.classLoader
            .getResourceAsStream("prompts/AGENTS.md")
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText().trim() }
            .orEmpty()
    }
}
