package com.ztianzeng.contextlens.insight.core

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.ztianzeng.contextlens.insight.model.CollectedContext
import git4idea.GitUtil
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ContextCollector(private val project: Project) {

    fun collect(psiFile: PsiFile): CollectedContext {
        val virtualFile = psiFile.virtualFile ?: error("File must be backed by VirtualFile")
        val fileText = ReadAction.compute<String, RuntimeException> { psiFile.text }
        val module = ModuleUtil.findModuleForFile(virtualFile, project)?.name
        val neighbors = collectNeighborFiles(virtualFile)
        val gitRevision = resolveGitRevision(virtualFile)
        val notes = mutableMapOf<String, String>()
        module?.let { notes["module"] = it }
        notes["size"] = fileText.length.toString()
        notes["language"] = psiFile.language.displayName

        return CollectedContext(
            filePath = virtualFile.path,
            language = psiFile.language.id.lowercase(),
            fileContent = fileText,
            moduleName = module,
            neighbors = neighbors,
            gitRevision = gitRevision,
            notes = notes
        )
    }

    private fun collectNeighborFiles(file: VirtualFile): List<String> {
        val parent = file.parent ?: return emptyList()
        val baseVFile = project.basePath?.let { VfsUtil.findFile(Path.of(it), true) }
        return parent.children
            .filter { it != file && !it.isDirectory }
            .take(5)
            .map { child ->
                baseVFile?.let { VfsUtil.getRelativePath(child, it) } ?: child.path
            }
    }

    private fun resolveGitRevision(file: VirtualFile): String? {
        val repo = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(file) ?: return null
        return repo.currentRevision
    }
}
