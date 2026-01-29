package com.github.farhazulmullick.dependencyinspector.models

/**
 * Represents information about a library dependency.
 */
data class DependencyInfo(
    val groupId: String,
    val artifactId: String,
    val resolvedVersion: String,
    val requestedVersion: String? = null,
    val status: DependencyStatus = DependencyStatus.RESOLVED,
    val requestedBy: List<DependencyRequester> = emptyList()
) {
    val coordinate: String
        get() = "$groupId:$artifactId"

    val fullCoordinate: String
        get() = "$groupId:$artifactId:$resolvedVersion"

    val hasConflict: Boolean
        get() = requestedBy.any { it.requestedVersion != resolvedVersion }
}

/**
 * Represents who requested a dependency and what version they requested.
 */
data class DependencyRequester(
    val name: String,
    val requestedVersion: String,
    val isDirect: Boolean = false
)

/**
 * Status of a dependency resolution.
 */
enum class DependencyStatus {
    RESOLVED,
    CONFLICT,
    MISSING,
    OUTDATED
}

/**
 * Represents a module in the project (e.g., app, lib).
 */
data class ModuleInfo(
    val name: String,
    val dependencies: List<DependencyInfo>
) {
    val conflictCount: Int
        get() = dependencies.count { it.hasConflict }
}
