package com.ztianzeng.contextlens.insight.model

import com.intellij.openapi.vfs.VirtualFile

data class CollectedContext(
    val filePath: String,
    val language: String,
    val fileContent: String,
    val moduleName: String?,
    val neighbors: List<String>,
    val gitRevision: String?,
    val notes: Map<String, String>
)

data class CodexRequest(
    val filePath: String,
    val language: String,
    val fileContent: String,
    val extraContext: Map<String, Any?>
)

data class CodexResponse(
    val status: String,
    val raw: String? = null
)

data class FileInsightResult(
    val requestId: String,
    val originalFile: VirtualFile,
    val response: CodexResponse
)
