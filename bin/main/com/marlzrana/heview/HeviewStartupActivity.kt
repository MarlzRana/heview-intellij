package com.marlzrana.heview

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Phase 0 smoke marker: confirms the plugin is loaded in the sandbox IDE.
 *
 * Real functionality — the comment store, inline inlay UI, hook install/registration,
 * and the consumption watcher — arrives in later phases (see plan.html).
 */
internal class HeviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("heview: plugin loaded for project '${project.name}'")
    }
}
