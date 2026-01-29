package com.github.farhazulmullick.dependencyinspector.toolWindow

import com.github.farhazulmullick.dependencyinspector.models.DependencyInfo
import com.github.farhazulmullick.dependencyinspector.models.DependencyRequester
import com.github.farhazulmullick.dependencyinspector.models.DependencyStatus
import com.github.farhazulmullick.dependencyinspector.models.ModuleInfo
import com.github.farhazulmullick.dependencyinspector.services.DependencyAnalyzerService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class DependencyInspectorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyInspectorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

class DependencyInspectorPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val logger = project.thisLogger()
    private val dependencyTree: Tree
    private val rootNode = DefaultMutableTreeNode("Dependencies")
    private val treeModel = DefaultTreeModel(rootNode)
    private val analyzerService = project.service<DependencyAnalyzerService>()

    init {
        dependencyTree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = DependencyTreeCellRenderer()
        }

        val scrollPane = JBScrollPane(dependencyTree)
        add(createToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Load sample data initially
        loadDependencies()
    }

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(RefreshAction())
            add(ExpandAllAction())
            add(CollapseAllAction())
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("DependencyInspector", actionGroup, true)
        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            border = JBUI.Borders.emptyBottom(2)
        }
    }

    private fun loadDependencies() {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Use sample data for demo - in production, use analyzerService.analyzeDependencies()
            val modules = analyzerService.analyzeDependencies()

            ApplicationManager.getApplication().invokeLater {
                updateTree(modules)
            }
        }
    }

    private fun updateTree(modules: List<ModuleInfo>) {
        rootNode.removeAllChildren()

        for (module in modules) {
            val moduleNode = DefaultMutableTreeNode(ModuleNodeData(module.name, module.conflictCount))

            for (dependency in module.dependencies) {
                val depNode = DefaultMutableTreeNode(DependencyNodeData(dependency))

                for (requester in dependency.requestedBy) {
                    val requesterNode = DefaultMutableTreeNode(RequesterNodeData(requester))
                    depNode.add(requesterNode)
                }

                // Add conflict info if no requesters
                if (dependency.requestedBy.isEmpty() && !dependency.hasConflict) {
                    val noConflictNode = DefaultMutableTreeNode(InfoNodeData("No conflicts detected."))
                    depNode.add(noConflictNode)
                }

                moduleNode.add(depNode)
            }

            rootNode.add(moduleNode)
        }

        treeModel.reload()
        expandAllNodes()
    }

    private fun expandAllNodes() {
        var row = 0
        while (row < dependencyTree.rowCount) {
            dependencyTree.expandRow(row)
            row++
        }
    }

    private fun collapseAllNodes() {
        var row = dependencyTree.rowCount - 1
        while (row >= 0) {
            dependencyTree.collapseRow(row)
            row--
        }
    }

    // Actions
    private inner class RefreshAction : AnAction("Refresh", "Refresh dependencies", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            loadDependencies()
        }
    }

    private inner class ExpandAllAction : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            expandAllNodes()
        }
    }

    private inner class CollapseAllAction : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            collapseAllNodes()
        }
    }
}

// Node Data Classes
data class ModuleNodeData(val name: String, val conflictCount: Int)
data class DependencyNodeData(val dependency: DependencyInfo)
data class RequesterNodeData(val requester: DependencyRequester)
data class InfoNodeData(val message: String)

// Custom Tree Cell Renderer
class DependencyTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject

        when (userObject) {
            is ModuleNodeData -> renderModuleNode(userObject)
            is DependencyNodeData -> renderDependencyNode(userObject)
            is RequesterNodeData -> renderRequesterNode(userObject)
            is InfoNodeData -> renderInfoNode(userObject)
            else -> append(userObject.toString())
        }
    }

    private fun renderModuleNode(data: ModuleNodeData) {
        icon = AllIcons.Nodes.Module
        append(data.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        if (data.conflictCount > 0) {
            append(" (${data.conflictCount} conflicts)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun renderDependencyNode(data: DependencyNodeData) {
        val dep = data.dependency

        icon = when (dep.status) {
            DependencyStatus.CONFLICT -> AllIcons.General.Warning
            DependencyStatus.RESOLVED -> AllIcons.Nodes.PpLib
            DependencyStatus.MISSING -> AllIcons.General.Error
            DependencyStatus.OUTDATED -> AllIcons.General.Information
        }

        append(dep.coordinate, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(" ")

        // Version with color based on status
        val versionAttributes = when (dep.status) {
            DependencyStatus.CONFLICT -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.ORANGE)
            DependencyStatus.MISSING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.RED)
            else -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GREEN.darker())
        }
        append(dep.resolvedVersion, versionAttributes)

        append(" (Resolved)", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        // Show original version if there was a conflict
        if (dep.requestedVersion != null && dep.requestedVersion != dep.resolvedVersion) {
            append(" ")
            append("⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE))
            append(" (was ${dep.requestedVersion})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun renderRequesterNode(data: RequesterNodeData) {
        val requester = data.requester

        icon = AllIcons.General.ArrowRight
        append("↳ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("Requested by: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(requester.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

        if (requester.isDirect) {
            append(" (Direct)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        append(" -> ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(requester.requestedVersion, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.BLUE))
    }

    private fun renderInfoNode(data: InfoNodeData) {
        icon = AllIcons.General.Information
        append("↳ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(data.message, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
    }
}
