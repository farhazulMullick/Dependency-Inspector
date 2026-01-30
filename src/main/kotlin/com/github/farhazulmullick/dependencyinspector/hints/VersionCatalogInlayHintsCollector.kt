package com.github.farhazulmullick.dependencyinspector.hints

import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

@Suppress("UnstableApiUsage")
class VersionCatalogInlayHintsCollector(
    editor: Editor,
    private val resolvedVersions: Map<String, String> // coordinate -> resolvedVersion
) : InlayHintsCollector {

    private val factory = PresentationFactory(editor)

    // Map to store version references from [versions] section
    private val versionRefs = mutableMapOf<String, String>()
    private var versionsParsed = false

    // Pattern 1: Inline version - gson = "com.google.code.gson:gson:2.8.6"
    private val inlineVersionRegex = Regex("""^\s*[\w-]+\s*=\s*"([\w.-]+):([\w.-]+):([\w.-]+)"\s*$""")

    // Pattern 2: Module with version.ref - name = { module = "group:artifact", version.ref = "versionKey" }
    // Handles both orders: module first or version.ref first
    private val moduleWithRefRegex = Regex("""^\s*[\w-]+\s*=\s*\{[^}]*module\s*=\s*"([\w.-]+):([\w.-]+)"[^}]*version\.ref\s*=\s*"([\w.-]+)"[^}]*}""")
    private val refWithModuleRegex = Regex("""^\s*[\w-]+\s*=\s*\{[^}]*version\.ref\s*=\s*"([\w.-]+)"[^}]*module\s*=\s*"([\w.-]+):([\w.-]+)"[^}]*}""")

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        // Parse versions section once from the full file
        if (!versionsParsed) {
            val file = element.containingFile
            if (file != null) {
                parseVersionsSection(file.text)
                versionsParsed = true
            }
        }

        val text = element.text

        // Skip if this doesn't look like a dependency line
        if (!text.contains(":") || text.startsWith("[")) {
            return true
        }

        val (coordinate, declaredVersion) = parseDependencyLine(text) ?: return true

        val resolvedVersion = resolvedVersions[coordinate]

        if (resolvedVersion != null && resolvedVersion != declaredVersion) {
            val presentation = factory.roundWithBackground(
                factory.smallText(" → $resolvedVersion")
            )

            val offset = element.textRange.endOffset
            sink.addInlineElement(offset, false, presentation, false)
        }

        return true
    }

    private fun parseDependencyLine(text: String): Pair<String, String>? {
        // Try inline version pattern
        inlineVersionRegex.find(text)?.let { match ->
            val groupId = match.groupValues[1]
            val artifactId = match.groupValues[2]
            val version = match.groupValues[3]
            return "$groupId:$artifactId" to version
        }

        // Try module with version.ref pattern (module first)
        moduleWithRefRegex.find(text)?.let { match ->
            val groupId = match.groupValues[1]
            val artifactId = match.groupValues[2]
            val versionRef = match.groupValues[3]
            val version = resolveVersionRef(versionRef)
            return "$groupId:$artifactId" to version
        }

        // Try version.ref with module pattern (version.ref first)
        refWithModuleRegex.find(text)?.let { match ->
            val versionRef = match.groupValues[1]
            val groupId = match.groupValues[2]
            val artifactId = match.groupValues[3]
            val version = resolveVersionRef(versionRef)
            return "$groupId:$artifactId" to version
        }

        return null
    }

    private fun resolveVersionRef(versionRef: String): String {
        return versionRefs[versionRef] ?: versionRef
    }

    private fun parseVersionsSection(fileText: String) {
        val versionRegex = Regex("""^\s*([\w-]+)\s*=\s*"([^"]+)"\s*$""", RegexOption.MULTILINE)
        val versionsSection = fileText.substringAfter("[versions]", "")
            .substringBefore("\n[", "")

        versionRegex.findAll(versionsSection).forEach { match ->
            versionRefs[match.groupValues[1]] = match.groupValues[2]
        }
    }
}