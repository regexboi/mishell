package ai.mishell.app.codex

data class CodexProject(
    val id: String,
    val name: String,
    val localPath: String,
    val codexCwd: String,
    val permissionsPreset: CodexPermissionsPreset = CodexPermissionsPreset.WORKSPACE_WRITE,
    val archived: Boolean = false,
    val displayOrder: Int = 0
)

enum class CodexPermissionsPreset {
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS
}

data class CodexModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val supportsPersonality: Boolean,
    val defaultReasoningEffort: String
)

data class CodexThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val cwd: String,
    val updatedAtMillis: Long,
    val createdAtMillis: Long,
    val archived: Boolean
)

sealed interface CodexConnectionState {
    data object Disconnected : CodexConnectionState
    data object Connecting : CodexConnectionState
    data class Connected(val userAgent: String) : CodexConnectionState
    data class Reconnecting(val attempt: Int, val reason: String?) : CodexConnectionState
    data class Failed(val message: String) : CodexConnectionState
}

enum class CodexTimelineSpeaker {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class CodexTimelineKind {
    USER_MESSAGE,
    AGENT_MESSAGE,
    PLAN,
    REASONING,
    COMMAND_EXECUTION,
    FILE_CHANGE,
    MCP_TOOL,
    DYNAMIC_TOOL,
    WEB_SEARCH,
    IMAGE_VIEW,
    REVIEW_MODE,
    CONTEXT_COMPACTION,
    DIFF,
    STATUS
}

data class CodexTimelineEntry(
    val itemId: String,
    val turnId: String?,
    val kind: CodexTimelineKind,
    val speaker: CodexTimelineSpeaker,
    val title: String,
    val body: String,
    val detail: String? = null,
    val status: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class CodexPlanStep(
    val step: String,
    val status: String
)

data class CodexThreadState(
    val threadId: String,
    val projectId: String,
    val threadTitle: String,
    val projectName: String,
    val projectPath: String,
    val timeline: List<CodexTimelineEntry> = emptyList(),
    val isActiveTurn: Boolean = false,
    val currentTurnId: String? = null,
    val aggregateDiff: String = "",
    val planSteps: List<CodexPlanStep> = emptyList(),
    val planExplanation: String? = null,
    val sandboxModeLabel: String = "workspace-write",
    val lastError: String? = null
)

enum class CodexApprovalKind {
    COMMAND,
    FILE_CHANGE
}

data class CodexApprovalRequest(
    val requestId: Any,
    val requestKey: String,
    val kind: CodexApprovalKind,
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val title: String,
    val summary: String,
    val detail: String,
    val proposedExecPolicyAmendment: List<String> = emptyList(),
    val proposedNetworkHost: String? = null,
    val proposedNetworkAction: String? = null
)

data class CodexThreadSubscription(
    val threadId: String,
    val projectId: String,
    val cwd: String,
    val model: String?,
    val sandboxMode: CodexPermissionsPreset
)
