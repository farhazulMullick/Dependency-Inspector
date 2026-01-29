package com.github.farhazulmullick.dependencyinspector.services

import com.github.farhazulmullick.dependencyinspector.models.DependencyInfo
import com.github.farhazulmullick.dependencyinspector.models.DependencyRequester
import com.github.farhazulmullick.dependencyinspector.models.DependencyStatus
import com.github.farhazulmullick.dependencyinspector.models.ModuleInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Service(Service.Level.PROJECT)
class DependencyAnalyzerService(private val project: Project) {

    private val logger = thisLogger()

    /**
     * Analyzes the project dependencies and returns module information.
     */
    fun analyzeDependencies(): List<ModuleInfo> {
        val projectBasePath = project.basePath ?: return emptyList()
        val projectDir = File(projectBasePath)

        return when {
            isGradleProject(projectDir) -> analyzeGradleDependencies(projectDir)
            isMavenProject(projectDir) -> analyzeMavenDependencies(projectDir)
            else -> {
                logger.info("Unknown project type. Cannot analyze dependencies.")
                emptyList()
            }
        }
    }

    private fun isGradleProject(projectDir: File): Boolean {
        return File(projectDir, "build.gradle").exists() ||
                File(projectDir, "build.gradle.kts").exists() ||
                File(projectDir, "settings.gradle").exists() ||
                File(projectDir, "settings.gradle.kts").exists()
    }

    private fun isMavenProject(projectDir: File): Boolean {
        return File(projectDir, "pom.xml").exists()
    }


    private fun analyzeGradleDependencies(projectDir: File): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()

        try {
            // Run gradle dependencies command
            val gradleWrapper = if (File(projectDir, "gradlew").exists()) {
                "./gradlew"
            } else {
                "gradle"
            }

            val process = ProcessBuilder(gradleWrapper, "lens:dependencies", "--configuration", "releaseRuntimeClasspath", "-q")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            val dependencies = parseGradleDependencyOutput(output)
            if (dependencies.isNotEmpty()) {
                modules.add(ModuleInfo(project.name, dependencies))
            }
        } catch (e: Exception) {
            logger.error("Error analyzing Gradle dependencies", e)
        }

        return modules
    }

    private fun parseGradleDependencyOutput(output: String): List<DependencyInfo> {
        val dependencies = mutableMapOf<String, DependencyInfo>()
        val lines = output.lines()

        // Stack to track parent dependencies at each indent level
        val parentStack = mutableListOf<String>() // coordinate -> depth
        val depthStack = mutableListOf<Int>()

        for (line in lines) {
            // Skip empty lines and non-dependency lines
            if (line.isBlank() || !line.contains("---")) continue

            // Calculate depth based on leading characters (each level is ~5 chars: "+--- " or "|    ")
            val trimmedLine = line.trimStart()
            val depth = (line.length - trimmedLine.length) / 5

            // Match: groupId:artifactId:requestedVersion -> resolvedVersion (status)
            // Examples:
            //   +--- com.google.code.gson:gson:2.8.6 -> 2.8.9 (*)
            //   +--- com.squareup.okhttp3:okhttp:4.9.0
            //   \--- org.jetbrains.kotlin:kotlin-stdlib:1.5.0 -> 1.6.0 (c)
            val depRegex = Regex(
                """[+\\]---\s+([\w.\-]+):([\w.\-]+):([\w.\-]+)(?:\s+->\s+([\w.\-]+))?(?:\s+\(([^)]+)\))?"""
            )

            val match = depRegex.find(trimmedLine) ?: continue

            val groupId = match.groupValues[1]
            val artifactId = match.groupValues[2]
            val requestedVersion = match.groupValues[3]
            val resolvedVersion = match.groupValues[4].ifEmpty { requestedVersion }
            val statusMarker = match.groupValues[5] // *, c, n, etc.

            val coordinate = "$groupId:$artifactId"
            val hasConflict = resolvedVersion != requestedVersion

            // Update parent stack for current depth
            while (depthStack.isNotEmpty() && depthStack.last() >= depth) {
                depthStack.removeLast()
                parentStack.removeLast()
            }

            // Determine requester name
            val requesterName = if (parentStack.isEmpty()) {
                "Project Root"
            } else {
                parentStack.last()
            }

            val isDirect = parentStack.isEmpty()

            val requester = DependencyRequester(
                name = requesterName,
                requestedVersion = requestedVersion,
                isDirect = isDirect
            )

            val existing = dependencies[coordinate]
            if (existing == null) {
                dependencies[coordinate] = DependencyInfo(
                    groupId = groupId,
                    artifactId = artifactId,
                    resolvedVersion = resolvedVersion,
                    requestedVersion = if (hasConflict) requestedVersion else null,
                    status = if (hasConflict) DependencyStatus.CONFLICT else DependencyStatus.RESOLVED,
                    requestedBy = listOf(requester)
                )
            } else {
                // Add requester if not duplicate
                val existingRequesters = existing.requestedBy.map { it.name to it.requestedVersion }
                if ((requesterName to requestedVersion) !in existingRequesters) {
                    dependencies[coordinate] = existing.copy(
                        requestedBy = existing.requestedBy + requester,
                        status = if (hasConflict || existing.status == DependencyStatus.CONFLICT)
                            DependencyStatus.CONFLICT else DependencyStatus.RESOLVED,
                        requestedVersion = existing.requestedVersion ?: if (hasConflict) requestedVersion else null
                    )
                }
            }

            // Push current dependency as potential parent for next level
            parentStack.add("$artifactId:$resolvedVersion")
            depthStack.add(depth)
        }

        return dependencies.values.toList()
    }

    private fun analyzeMavenDependencies(projectDir: File): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()

        try {
            val process = ProcessBuilder("mvn", "dependency:tree", "-DoutputType=text", "-q")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            val dependencies = parseMavenDependencyOutput(output)
            if (dependencies.isNotEmpty()) {
                modules.add(ModuleInfo(project.name, dependencies))
            }
        } catch (e: Exception) {
            logger.error("Error analyzing Maven dependencies", e)
        }

        return modules
    }

    private fun parseMavenDependencyOutput(output: String): List<DependencyInfo> {
        val dependencies = mutableListOf<DependencyInfo>()
        val lines = output.lines()

        for (line in lines) {
            // Match Maven dependency format: groupId:artifactId:packaging:version:scope
            val depMatch = Regex("""[+\\|`-]+\s*([\w.-]+):([\w.-]+):[\w.-]+:([\w.-]+):[\w.-]+""")
                .find(line.trim())

            if (depMatch != null) {
                val (groupId, artifactId, version) = depMatch.destructured
                dependencies.add(
                    DependencyInfo(
                        groupId = groupId,
                        artifactId = artifactId,
                        resolvedVersion = version,
                        status = DependencyStatus.RESOLVED,
                        requestedBy = listOf(
                            DependencyRequester(
                                name = "Project",
                                requestedVersion = version,
                                isDirect = true
                            )
                        )
                    )
                )
            }
        }

        return dependencies
    }

    /**
     * Creates sample/demo data for testing the UI.
     */
    fun getSampleDependencies(): List<ModuleInfo> {
        return listOf(
            ModuleInfo(
                name = "${project.name}.main",
                dependencies = listOf(
                    DependencyInfo(
                        groupId = "com.google.code.gson",
                        artifactId = "gson",
                        resolvedVersion = "2.8.9",
                        requestedVersion = "2.8.6",
                        status = DependencyStatus.CONFLICT,
                        requestedBy = listOf(
                            DependencyRequester("Retrofit 2.9.0", "2.8.9", false),
                            DependencyRequester("Project Root (Direct)", "2.8.6", true)
                        )
                    ),
                    DependencyInfo(
                        groupId = "com.squareup.okhttp3",
                        artifactId = "okhttp",
                        resolvedVersion = "4.9.0",
                        requestedVersion = "3.12.1",
                        status = DependencyStatus.CONFLICT,
                        requestedBy = listOf(
                            DependencyRequester("OtherLib", "4.9.0", false),
                            DependencyRequester("Retrofit 2.9.0", "3.14.9", false)
                        )
                    ),
                    DependencyInfo(
                        groupId = "org.slf4j",
                        artifactId = "slf4j-api",
                        resolvedVersion = "1.7.32",
                        status = DependencyStatus.RESOLVED,
                        requestedBy = listOf(
                            DependencyRequester("Project Root", "1.7.32", true)
                        )
                    )
                )
            )
        )
    }
}
