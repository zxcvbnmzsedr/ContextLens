package com.ztianzeng.contextlens.insight.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.ztianzeng.contextlens.insight.model.CodexRequest
import com.ztianzeng.contextlens.insight.model.CodexResponse
import com.ztianzeng.contextlens.insight.settings.FileInsightSettings
import java.io.BufferedReader
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

    fun invoke(
        request: CodexRequest,
        indicator: ProgressIndicator,
        target: AnalysisTarget?,
        onStreamUpdate: ((String) -> Unit)? = null
    ): CliInvocationResult {
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

            val captured = captureProcessOutput(process, indicator, onStreamUpdate)
            val stdout = captured.stdout
            val stderr = captured.stderr
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
        "--json",
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

    private fun captureProcessOutput(
        process: Process,
        indicator: ProgressIndicator,
        onStreamUpdate: ((String) -> Unit)?
    ): CapturedOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
        val stderrReader = process.errorStream.bufferedReader(StandardCharsets.UTF_8)
        try {
            while (true) {
                indicator.checkCanceled()
                val stdoutLines = drainAvailable(stdoutReader) { line ->
                    stdout.appendLine(line)
                    handleStreamPayload(line, onStreamUpdate)
                }
                val stderrLines = drainAvailable(stderrReader) { line ->
                    stderr.appendLine(line)
                }

                if (!process.isAlive) {
                    drainFully(stdoutReader) { line ->
                        stdout.appendLine(line)
                        handleStreamPayload(line, onStreamUpdate)
                    }
                    drainFully(stderrReader) { line ->
                        stderr.appendLine(line)
                    }
                    break
                }

                if (stdoutLines == 0 && stderrLines == 0) {
                    process.waitFor(200, TimeUnit.MILLISECONDS)
                }
            }
        } catch (ex: ProcessCanceledException) {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            throw ex
        } finally {
            stdoutReader.close()
            stderrReader.close()
        }
        return CapturedOutput(stdout.toString(), stderr.toString())
    }

    private fun drainAvailable(reader: BufferedReader, consumer: (String) -> Unit): Int {
        var read = 0
        while (reader.ready()) {
            val line = reader.readLine() ?: break
            consumer(line)
            read++
        }
        return read
    }

    private fun drainFully(reader: BufferedReader, consumer: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: break
            consumer(line)
        }
    }

    private fun handleStreamPayload(payload: String, onStreamUpdate: ((String) -> Unit)?) {
        if (onStreamUpdate == null) return
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        try {
            val element = JsonParser.parseString(trimmed)
            when {
                element.isJsonArray -> element.asJsonArray
                    .filter { it.isJsonObject }
                    .forEach { handleStreamEvent(it.asJsonObject, onStreamUpdate) }
                element.isJsonObject -> handleStreamEvent(element.asJsonObject, onStreamUpdate)
            }
        } catch (ex: Exception) {
            log.debug("Failed to parse Codex stream payload", ex)
        }
    }

    private fun handleStreamEvent(event: JsonObject, consumer: (String) -> Unit) {
        val type = event.get("type")?.asString ?: return
        if (type.startsWith("item.")) {
            val item = event.getAsJsonObject("item") ?: return
            val itemType = item.get("type")?.asString ?: return
            when (itemType) {
                "reasoning", "agent_message" -> emitStreamText(item.get("text")?.asString, consumer)
                "command_execution" -> emitStreamText(resolveCommandMessage(item), consumer)
            }
            return
        }
        if (type == "response.output_text.delta") {
            emitStreamText(event.get("delta")?.asString, consumer)
        }
    }

    private fun resolveCommandMessage(item: JsonObject): String? {
        val aggregated = item.get("aggregated_output")?.asString?.takeIf { it.isNotBlank() }
        if (aggregated != null) {
            return aggregated
        }
        val command = item.get("command")?.asString?.takeIf { it.isNotBlank() }
        val status = item.get("status")?.asString?.takeIf { it.isNotBlank() }
        val composed = listOfNotNull(command, status).joinToString(" · ")
        return composed.takeIf { it.isNotBlank() }
    }

    private fun emitStreamText(text: String?, consumer: (String) -> Unit) {
        if (text.isNullOrBlank()) return
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { consumer(it) }
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

private data class CapturedOutput(
    val stdout: String,
    val stderr: String
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
