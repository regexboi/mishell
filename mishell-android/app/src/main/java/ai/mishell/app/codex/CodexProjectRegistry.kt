package ai.mishell.app.codex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CodexProjectRegistry(
    context: Context
) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "codex-projects.json")
    private val mutex = Mutex()

    suspend fun listProjects(includeArchived: Boolean = false): List<CodexProject> =
        mutex.withLock {
            val projects = readProjectsLocked()
                .sortedBy { it.displayOrder }
            if (includeArchived) {
                projects
            } else {
                projects.filterNot { it.archived }
            }
        }

    suspend fun getProject(projectId: String): CodexProject? =
        mutex.withLock {
            readProjectsLocked().firstOrNull { it.id == projectId }
        }

    suspend fun addProject(
        name: String,
        localPath: String,
        permissionsPreset: CodexPermissionsPreset
    ): CodexProject = mutex.withLock {
        val current = readProjectsLocked().toMutableList()
        val order = (current.maxOfOrNull { it.displayOrder } ?: -1) + 1
        val project = CodexProject(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            localPath = localPath.trim(),
            codexCwd = localPath.trim(),
            permissionsPreset = permissionsPreset,
            displayOrder = order
        )
        current += project
        writeProjectsLocked(current)
        project
    }

    suspend fun updateProject(project: CodexProject) = mutex.withLock {
        val updated = readProjectsLocked().map {
            if (it.id == project.id) project else it
        }
        writeProjectsLocked(updated)
    }

    suspend fun archiveProject(projectId: String) = mutex.withLock {
        val updated = readProjectsLocked().map {
            if (it.id == projectId) it.copy(archived = true) else it
        }
        writeProjectsLocked(updated)
    }

    private suspend fun readProjectsLocked(): List<CodexProject> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext emptyList()
        }
        val raw = file.readText().trim()
        if (raw.isEmpty()) {
            return@withContext emptyList()
        }
        val root = JSONObject(raw)
        root.optJSONArrayOrNull("projects")
            ?.toJsonObjectList()
            ?.mapNotNull(::parseProject)
            .orEmpty()
    }

    private suspend fun writeProjectsLocked(projects: List<CodexProject>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        projects.forEach { project ->
            array.put(
                jsonObjectOf(
                    "id" to project.id,
                    "name" to project.name,
                    "localPath" to project.localPath,
                    "codexCwd" to project.codexCwd,
                    "permissionsPreset" to project.permissionsPreset.name,
                    "archived" to project.archived,
                    "displayOrder" to project.displayOrder
                )
            )
        }
        file.writeText(jsonObjectOf("projects" to array).toString(2))
    }

    private fun parseProject(json: JSONObject): CodexProject? {
        val id = json.optStringOrNull("id") ?: return null
        val name = json.optStringOrNull("name") ?: return null
        val localPath = json.optStringOrNull("localPath") ?: return null
        val codexCwd = json.optStringOrNull("codexCwd") ?: localPath
        val permissionsPreset = runCatching {
            CodexPermissionsPreset.valueOf(
                json.optStringOrNull("permissionsPreset")
                    ?.ifBlank { CodexPermissionsPreset.WORKSPACE_WRITE.name }
                    ?: CodexPermissionsPreset.WORKSPACE_WRITE.name
            )
        }.getOrDefault(CodexPermissionsPreset.WORKSPACE_WRITE)
        return CodexProject(
            id = id,
            name = name,
            localPath = localPath,
            codexCwd = codexCwd,
            permissionsPreset = permissionsPreset,
            archived = json.optBooleanOrNull("archived") ?: false,
            displayOrder = json.optIntOrNull("displayOrder") ?: 0
        )
    }
}
