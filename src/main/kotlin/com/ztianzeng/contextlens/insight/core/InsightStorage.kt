package com.ztianzeng.contextlens.insight.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.ztianzeng.contextlens.insight.settings.FileInsightSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class InsightStorage(private val project: Project) {

    private val log = Logger.getInstance(InsightStorage::class.java)
    private val settings get() = FileInsightSettings.getInstance().current

    fun resolveAnalysisTarget(originalFile: VirtualFile): AnalysisTarget? {
        val basePath = project.basePath?.let { Path.of(it) }
        if (basePath == null) {
            log.warn("Cannot locate project base path for ${originalFile.path}")
            return null
        }
        val relativeSource = relativeToProject(originalFile, basePath)
        val outputRoot = ensureAnalysisRoot(basePath)
        val outputFile = outputRoot.resolve("$relativeSource.html")
        Files.createDirectories(outputFile.parent)
        val displayPath = normalizeRelative(relativeSource)
        return AnalysisTarget(displayPath, outputFile)
    }

    fun findExistingAnalysisFile(originalFile: VirtualFile): Path? {
        val basePath = project.basePath?.let { Path.of(it) } ?: return null
        val relativeSource = relativeToProject(originalFile, basePath)
        val outputRoot = basePath.resolve(settings.analysisOutputRoot.trim().ifBlank { ".analysis" })
        val outputFile = outputRoot.resolve("$relativeSource.html")
        return if (Files.exists(outputFile)) outputFile else null
    }

    fun isAnalysisOutput(file: VirtualFile): Boolean {
        val basePath = project.basePath?.let { Path.of(it) } ?: return false
        val configured = settings.analysisOutputRoot.trim().ifBlank { ".analysis" }
        val analysisRoot = basePath.resolve(configured).normalize()
        return try {
            val filePath = Path.of(file.path).normalize()
            filePath.startsWith(analysisRoot)
        } catch (ex: Exception) {
            log.warn("Failed to resolve path for ${file.path}", ex)
            false
        }
    }

    fun appendLog(section: String, text: String) {
        if (!settings.enableCliLogs) return
        val basePath = project.basePath?.let { Path.of(it) } ?: return
        val logDir = ensureAnalysisRoot(basePath).resolve("logs")
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("last-run.log")
        Files.writeString(
            logFile,
            "[${timestamp()}][$section] $text\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        )
    }

    private fun ensureAnalysisRoot(basePath: Path): Path {
        val configured = settings.analysisOutputRoot.trim().ifBlank { ".analysis" }
        val root = basePath.resolve(configured)
        Files.createDirectories(root)
        return root
    }

    private fun relativeToProject(file: VirtualFile, basePath: Path): Path {
        val filePath = Path.of(file.path)
        return try {
            basePath.relativize(filePath)
        } catch (ex: IllegalArgumentException) {
            log.warn("File $filePath is outside $basePath", ex)
            filePath.fileName ?: filePath
        }
    }

    private fun normalizeRelative(relative: Path): String =
        relative.toString().replace(File.separatorChar, '/')

    private fun timestamp(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    companion object
}

data class AnalysisTarget(
    val relativeSource: String,
    val outputFile: Path
)
