package com.github.farhazulmullick.dependencyinspector.hints

import com.github.farhazulmullick.dependencyinspector.services.DependencyAnalyzerService
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class VersionCatalogInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("version.catalog.hints")
    override val name: String = "Version Catalog Resolved Versions"
    override val previewText: String = "[libraries]\ngson = \"com.google.code.gson:gson:2.8.6\""

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        if (!file.name.endsWith(".toml") || !file.name.contains("libs")) {
            return null
        }

        val project = file.project
        val analyzerService = project.service<DependencyAnalyzerService>()

        // Get resolved versions from the analyzer service
        val resolvedVersions = getResolvedVersionsMap(analyzerService)

        return VersionCatalogInlayHintsCollector(editor, resolvedVersions)
    }

    private fun getResolvedVersionsMap(analyzerService: DependencyAnalyzerService): Map<String, String> {
        val modules = analyzerService.analyzeDependencies()
        val resolvedVersions = mutableMapOf<String, String>()

        for (module in modules) {
            for (dep in module.dependencies) {
                val coordinate = "${dep.groupId}:${dep.artifactId}"
                resolvedVersions[coordinate] = dep.resolvedVersion
            }
        }

        return resolvedVersions
    }
}