package ai.mishell.app.codex

import android.content.Context
import ai.mishell.app.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class CodexSessionRepository(
    context: Context
) {
    companion object {
        private const val SERVICE_NAME = "mishell-android"
        private const val DEFAULT_APPROVAL_POLICY = "on-request"
        private const val DEFAULT_PERSONALITY = "pragmatic"
        private const val DEFAULT_REASONING_EFFORT = "xhigh"
        private const val DEFAULT_REASONING_SUMMARY = "detailed"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsClient = CodexWsClient(appContext)
    private val projectRegistry = CodexProjectRegistry(appContext)
    private val subscriptionMutex = Mutex()
    private val activeSubscriptions = linkedMapOf<String, CodexThreadSubscription>()
    private val _models = MutableStateFlow<List<CodexModelOption>>(emptyList())
    private val _threadStates = MutableStateFlow<Map<String, CodexThreadState>>(emptyMap())
    private val _pendingApprovals = MutableStateFlow<Map<String, CodexApprovalRequest>>(emptyMap())

    val connectionState: StateFlow<CodexConnectionState> = wsClient.connectionState
    val models: StateFlow<List<CodexModelOption>> = _models.asStateFlow()
    val threadStates: StateFlow<Map<String, CodexThreadState>> = _threadStates.asStateFlow()
    val pendingApprovals: StateFlow<Map<String, CodexApprovalRequest>> = _pendingApprovals.asStateFlow()

    init {
        scope.launch {
            wsClient.notifications.collectLatest { notification ->
                handleNotification(notification.method, notification.params)
            }
        }
        scope.launch {
            wsClient.serverRequests.collectLatest { request ->
                handleServerRequest(request)
            }
        }
        scope.launch {
            wsClient.connectionState.collectLatest { state ->
                if (state is CodexConnectionState.Connected) {
                    refreshModels()
                    resumeSubscribedThreads()
                }
            }
        }
    }

    suspend fun listProjects(includeArchived: Boolean = false): List<CodexProject> =
        projectRegistry.listProjects(includeArchived)

    suspend fun getProject(projectId: String): CodexProject? =
        projectRegistry.getProject(projectId)

    suspend fun addProject(
        name: String,
        localPath: String,
        permissionsPreset: CodexPermissionsPreset
    ): CodexProject = projectRegistry.addProject(name, localPath, permissionsPreset)

    suspend fun updateProject(project: CodexProject) = projectRegistry.updateProject(project)

    suspend fun archiveProject(projectId: String) = projectRegistry.archiveProject(projectId)

    suspend fun refreshModels(): List<CodexModelOption> {
        return runCatching {
            val response = wsClient.request(serverUrl(), "model/list", null) as? JSONObject
            val items = response?.optJSONArrayOrNull("data")
                ?.toJsonObjectList()
                ?.map(::parseModel)
                .orEmpty()
            _models.value = items
            items
        }.getOrElse {
            _models.value
        }
    }

    suspend fun listThreads(project: CodexProject): List<CodexThreadSummary> {
        ensureConnected()
        val response = wsClient.request(
            serverUrl(),
            "thread/list",
            jsonObjectOf(
                "cwd" to project.codexCwd,
                "sortKey" to "updated_at",
                "sourceKinds" to listOf("appServer")
            )
        ) as? JSONObject ?: return emptyList()
        return response.optJSONArrayOrNull("data")
            ?.toJsonObjectList()
            ?.map { parseThreadSummary(it, archived = false) }
            .orEmpty()
    }

    suspend fun createThread(project: CodexProject): CodexThreadSummary {
        ensureConnected()
        val response = wsClient.request(
            serverUrl(),
            "thread/start",
            jsonObjectOf(
                "cwd" to project.codexCwd,
                "approvalPolicy" to DEFAULT_APPROVAL_POLICY,
                "sandbox" to sandboxModeString(project.permissionsPreset),
                "serviceName" to SERVICE_NAME,
                "personality" to DEFAULT_PERSONALITY,
                "model" to currentModelName()
            )
        ) as? JSONObject ?: error("thread/start returned no result")
        val thread = response.optJSONObjectOrNull("thread") ?: error("thread/start missing thread")
        val summary = parseThreadSummary(thread, archived = false)
        subscriptionMutex.withLock {
            activeSubscriptions[summary.id] = CodexThreadSubscription(
                threadId = summary.id,
                projectId = project.id,
                cwd = project.codexCwd,
                model = currentModelName(),
                sandboxMode = project.permissionsPreset
            )
        }
        return summary
    }

    suspend fun openThread(project: CodexProject, threadId: String): CodexThreadState {
        ensureConnected()
        val readResponse = tryReadThread(threadId, includeTurns = true)
            ?: tryReadThread(threadId, includeTurns = false)
            ?: error("thread/read returned no result")
        val thread = readResponse.optJSONObjectOrNull("thread") ?: error("thread/read missing thread")
        val threadState = parseThreadState(thread = thread, project = project)
        _threadStates.update { existing -> existing + (threadId to threadState) }

        runCatching {
            wsClient.request(
                serverUrl(),
                "thread/resume",
                jsonObjectOf(
                    "threadId" to threadId,
                    "cwd" to project.codexCwd,
                    "approvalPolicy" to DEFAULT_APPROVAL_POLICY,
                    "sandbox" to sandboxModeString(project.permissionsPreset),
                    "personality" to DEFAULT_PERSONALITY,
                    "model" to currentModelName()
                )
            )
        }
        subscriptionMutex.withLock {
            activeSubscriptions[threadId] = CodexThreadSubscription(
                threadId = threadId,
                projectId = project.id,
                cwd = project.codexCwd,
                model = currentModelName(),
                sandboxMode = project.permissionsPreset
            )
        }
        return threadState
    }

    suspend fun sendTurn(
        project: CodexProject,
        threadId: String,
        inputText: String
    ) {
        ensureConnected()
        wsClient.request(
            serverUrl(),
            "turn/start",
            jsonObjectOf(
                "threadId" to threadId,
                "cwd" to project.codexCwd,
                "model" to currentModelName(),
                "personality" to DEFAULT_PERSONALITY,
                "approvalPolicy" to DEFAULT_APPROVAL_POLICY,
                "effort" to DEFAULT_REASONING_EFFORT,
                "summary" to DEFAULT_REASONING_SUMMARY,
                "sandboxPolicy" to buildSandboxPolicy(project),
                "input" to listOf(
                    jsonObjectOf(
                        "type" to "text",
                        "text" to inputText
                    )
                )
            )
        )
        _threadStates.update { states ->
            states + (threadId to (states[threadId]?.copy(isActiveTurn = true) ?: return@update states))
        }
    }

    suspend fun interruptTurn(threadId: String) {
        ensureConnected()
        wsClient.request(
            serverUrl(),
            "turn/interrupt",
            jsonObjectOf("threadId" to threadId)
        )
    }

    fun getThreadState(threadId: String): CodexThreadState? = _threadStates.value[threadId]

    fun getPendingApproval(threadId: String): CodexApprovalRequest? =
        _pendingApprovals.value.values.firstOrNull { it.threadId == threadId }

    suspend fun respondToApproval(
        request: CodexApprovalRequest,
        decision: String
    ) {
        val result = when (request.kind) {
            CodexApprovalKind.COMMAND -> {
                if (decision == "applyNetworkPolicyAmendment" &&
                    request.proposedNetworkHost != null &&
                    request.proposedNetworkAction != null
                ) {
                    jsonObjectOf(
                        "decision" to jsonObjectOf(
                            "applyNetworkPolicyAmendment" to jsonObjectOf(
                                "network_policy_amendment" to jsonObjectOf(
                                    "action" to request.proposedNetworkAction,
                                    "host" to request.proposedNetworkHost
                                )
                            )
                        )
                    )
                } else {
                    jsonObjectOf("decision" to decision)
                }
            }
            CodexApprovalKind.FILE_CHANGE -> jsonObjectOf("decision" to decision)
        }
        wsClient.respondSuccess(request.requestId, result)
        _pendingApprovals.update { it - request.requestKey }
    }

    private suspend fun ensureConnected() {
        wsClient.ensureConnected(serverUrl())
    }

    private suspend fun tryReadThread(threadId: String, includeTurns: Boolean): JSONObject? {
        return runCatching {
            wsClient.request(
                serverUrl(),
                "thread/read",
                jsonObjectOf(
                    "threadId" to threadId,
                    "includeTurns" to includeTurns
                )
            ) as? JSONObject
        }.getOrNull()
    }

    private suspend fun resumeSubscribedThreads() {
        val subscriptions = subscriptionMutex.withLock { activeSubscriptions.values.toList() }
        subscriptions.forEach { subscription ->
            val project = getProject(subscription.projectId) ?: return@forEach
            runCatching {
                wsClient.request(
                    serverUrl(),
                    "thread/resume",
                    jsonObjectOf(
                        "threadId" to subscription.threadId,
                        "cwd" to subscription.cwd,
                        "approvalPolicy" to DEFAULT_APPROVAL_POLICY,
                        "sandbox" to sandboxModeString(project.permissionsPreset),
                        "personality" to DEFAULT_PERSONALITY,
                        "model" to subscription.model
                    )
                )
            }
        }
    }

    private suspend fun handleServerRequest(request: CodexWsClient.ServerRequest) {
        when (request.method) {
            "item/commandExecution/requestApproval" -> {
                val params = request.params ?: return
                val approval = CodexApprovalRequest(
                    requestId = request.id,
                    requestKey = request.id.requestIdKey(),
                    kind = CodexApprovalKind.COMMAND,
                    threadId = params.optStringOrNull("threadId").orEmpty(),
                    turnId = params.optStringOrNull("turnId").orEmpty(),
                    itemId = params.optStringOrNull("itemId").orEmpty(),
                    title = if (params.optJSONObjectOrNull("networkApprovalContext") != null) {
                        "Network approval needed"
                    } else {
                        "Command approval needed"
                    },
                    summary = params.optStringOrNull("reason")
                        ?: params.optStringOrNull("command")
                        ?: "Codex requested permission to run a command.",
                    detail = buildString {
                        appendLine(params.optStringOrNull("command") ?: "(command unavailable)")
                        params.optStringOrNull("cwd")?.let {
                            appendLine()
                            append("cwd: ").append(it)
                        }
                    }.trim(),
                    proposedExecPolicyAmendment = params.optJSONArrayOrNull("proposedExecpolicyAmendment")
                        ?.toStringList()
                        .orEmpty(),
                    proposedNetworkHost = params.optJSONArrayOrNull("proposedNetworkPolicyAmendments")
                        ?.optJSONObject(0)
                        ?.optStringOrNull("host"),
                    proposedNetworkAction = params.optJSONArrayOrNull("proposedNetworkPolicyAmendments")
                        ?.optJSONObject(0)
                        ?.optStringOrNull("action")
                )
                _pendingApprovals.update { current -> current + (approval.requestKey to approval) }
            }

            "item/fileChange/requestApproval" -> {
                val params = request.params ?: return
                val approval = CodexApprovalRequest(
                    requestId = request.id,
                    requestKey = request.id.requestIdKey(),
                    kind = CodexApprovalKind.FILE_CHANGE,
                    threadId = params.optStringOrNull("threadId").orEmpty(),
                    turnId = params.optStringOrNull("turnId").orEmpty(),
                    itemId = params.optStringOrNull("itemId").orEmpty(),
                    title = "File change approval needed",
                    summary = params.optStringOrNull("reason")
                        ?: "Codex requested permission to apply file changes.",
                    detail = params.optStringOrNull("grantRoot")
                        ?.let { "grant root: $it" }
                        ?: "Review the pending file change item before approving."
                )
                _pendingApprovals.update { current -> current + (approval.requestKey to approval) }
            }

            else -> wsClient.respondError(
                request.id,
                -32601,
                "Unsupported server request method: ${request.method}"
            )
        }
    }

    private fun handleNotification(method: String, params: JSONObject?) {
        when (method) {
            "turn/started" -> {
                val threadId = params.threadId() ?: return
                val turnId = params.turnId()
                _threadStates.update { states ->
                    states.updateThread(threadId) {
                        copy(
                            isActiveTurn = true,
                            currentTurnId = turnId ?: currentTurnId
                        )
                    }
                }
            }

            "turn/completed" -> {
                val threadId = params.threadId() ?: return
                _threadStates.update { states ->
                    states.updateThread(threadId) {
                        copy(
                            isActiveTurn = false,
                            currentTurnId = null,
                            lastError = params?.optJSONObjectOrNull("turn")
                                ?.optJSONObjectOrNull("error")
                                ?.optStringOrNull("message")
                                ?: lastError
                        )
                    }
                }
            }

            "turn/plan/updated" -> {
                val threadId = params.threadId() ?: return
                val turnId = params.turnId()
                val explanation = params?.optStringOrNull("explanation")
                val planSteps = params?.optJSONArrayOrNull("plan")
                    ?.toJsonObjectList()
                    ?.map {
                        CodexPlanStep(
                            step = it.optStringOrNull("step").orEmpty(),
                            status = it.optStringOrNull("status").orEmpty()
                        )
                    }
                    .orEmpty()
                val planText = buildString {
                    explanation?.takeIf { it.isNotBlank() }?.let {
                        appendLine(it)
                        appendLine()
                    }
                    planSteps.forEach { step ->
                        val marker = when (step.status) {
                            "completed" -> "[x]"
                            "inProgress" -> "[~]"
                            else -> "[ ]"
                        }
                        appendLine("$marker ${step.step}")
                    }
                }.trim()
                val syntheticItem = CodexTimelineEntry(
                    itemId = "plan-$turnId",
                    turnId = turnId,
                    kind = CodexTimelineKind.PLAN,
                    speaker = CodexTimelineSpeaker.SYSTEM,
                    title = "Plan",
                    body = planText,
                    status = planSteps.lastOrNull { it.status == "inProgress" }?.status ?: planSteps.lastOrNull()?.status
                )
                _threadStates.update { states ->
                    states.updateThread(threadId) {
                        copy(
                            planSteps = planSteps,
                            planExplanation = explanation,
                            timeline = timeline.upsertTimeline(syntheticItem)
                        )
                    }
                }
            }

            "turn/diff/updated" -> {
                val threadId = params.threadId() ?: return
                val turnId = params.turnId()
                val diff = params?.optStringOrNull("diff").orEmpty()
                val syntheticItem = CodexTimelineEntry(
                    itemId = "diff-$turnId",
                    turnId = turnId,
                    kind = CodexTimelineKind.DIFF,
                    speaker = CodexTimelineSpeaker.SYSTEM,
                    title = "Aggregated diff",
                    body = diff.ifBlank { "(No diff available)" }
                )
                _threadStates.update { states ->
                    states.updateThread(threadId) {
                        copy(
                            aggregateDiff = diff,
                            timeline = timeline.upsertTimeline(syntheticItem)
                        )
                    }
                }
            }

            "item/started",
            "item/completed" -> {
                val threadId = params.threadId() ?: return
                val turnId = params.turnId()
                val item = params?.optJSONObjectOrNull("item") ?: return
                val entry = parseTimelineEntry(item = item, turnId = turnId)
                _threadStates.update { states ->
                    states.updateThread(threadId) {
                        copy(timeline = timeline.upsertTimeline(entry))
                    }
                }
            }

            "item/agentMessage/delta" -> appendToTimeline(
                threadId = params.threadId(),
                itemId = params?.optStringOrNull("itemId"),
                appendToBody = params?.optStringOrNull("delta").orEmpty()
            )

            "item/reasoning/summaryTextDelta" -> appendToTimeline(
                threadId = params.threadId(),
                itemId = params?.optStringOrNull("itemId"),
                appendToBody = params?.optStringOrNull("delta").orEmpty()
            )

            "item/reasoning/textDelta" -> appendToTimeline(
                threadId = params.threadId(),
                itemId = params?.optStringOrNull("itemId"),
                appendToDetail = params?.optStringOrNull("delta").orEmpty()
            )

            "item/commandExecution/outputDelta" -> appendToTimeline(
                threadId = params.threadId(),
                itemId = params?.optStringOrNull("itemId"),
                appendToDetail = params?.optStringOrNull("delta").orEmpty()
            )

            "item/fileChange/outputDelta" -> appendToTimeline(
                threadId = params.threadId(),
                itemId = params?.optStringOrNull("itemId"),
                appendToDetail = params?.optStringOrNull("delta").orEmpty()
            )

            "serverRequest/resolved" -> {
                val requestId = params?.opt("requestId").requestIdKey()
                _pendingApprovals.update { current ->
                    current.filterKeys { it != requestId }
                }
            }
        }
    }

    private fun appendToTimeline(
        threadId: String?,
        itemId: String?,
        appendToBody: String = "",
        appendToDetail: String = ""
    ) {
        if (threadId.isNullOrBlank() || itemId.isNullOrBlank()) {
            return
        }
        _threadStates.update { states ->
            states.updateThread(threadId) {
                copy(
                    timeline = timeline.map { item ->
                        if (item.itemId != itemId) {
                            item
                        } else {
                            item.copy(
                                body = item.body + appendToBody,
                                detail = (item.detail.orEmpty() + appendToDetail).ifBlank { null }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun parseThreadState(
        thread: JSONObject,
        project: CodexProject
    ): CodexThreadState {
        val title = thread.optStringOrNull("name")
            ?.takeIf { it.isNotBlank() }
            ?: thread.optStringOrNull("preview")
            ?.lineSequence()
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Untitled thread"
        val timeline = buildList {
            val turns = thread.optJSONArrayOrNull("turns")
                ?.toJsonObjectList()
                .orEmpty()
            turns.forEach { turn ->
                val turnId = turn.optStringOrNull("id")
                turn.optJSONArrayOrNull("items")
                    ?.toJsonObjectList()
                    ?.mapTo(this) { item -> parseTimelineEntry(item, turnId) }
            }
        }
        return CodexThreadState(
            threadId = thread.optStringOrNull("id").orEmpty(),
            projectId = project.id,
            threadTitle = title,
            projectName = project.name,
            projectPath = project.codexCwd,
            timeline = timeline,
            sandboxModeLabel = sandboxModeString(project.permissionsPreset)
        )
    }

    private fun parseThreadSummary(
        thread: JSONObject,
        archived: Boolean
    ): CodexThreadSummary {
        val preview = thread.optStringOrNull("preview").orEmpty()
        return CodexThreadSummary(
            id = thread.optStringOrNull("id").orEmpty(),
            title = thread.optStringOrNull("name")
                ?.takeIf { it.isNotBlank() }
                ?: preview.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "Untitled thread",
            preview = preview,
            cwd = thread.optStringOrNull("cwd").orEmpty(),
            updatedAtMillis = thread.optLongOrNull("updatedAt") ?: 0L,
            createdAtMillis = thread.optLongOrNull("createdAt") ?: 0L,
            archived = archived
        )
    }

    private fun parseModel(json: JSONObject): CodexModelOption {
        return CodexModelOption(
            id = json.optStringOrNull("id").orEmpty(),
            model = json.optStringOrNull("model").orEmpty(),
            displayName = json.optStringOrNull("displayName").orEmpty(),
            description = json.optStringOrNull("description").orEmpty(),
            isDefault = json.optBooleanOrNull("isDefault") ?: false,
            supportsPersonality = json.optBooleanOrNull("supportsPersonality") ?: false,
            defaultReasoningEffort = json.optStringOrNull("defaultReasoningEffort").orEmpty()
        )
    }

    private fun parseTimelineEntry(item: JSONObject, turnId: String?): CodexTimelineEntry {
        val type = item.optStringOrNull("type").orEmpty()
        val itemId = item.optStringOrNull("id").orEmpty()
        return when (type) {
            "userMessage" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.USER_MESSAGE,
                speaker = CodexTimelineSpeaker.USER,
                title = "You",
                body = parseUserContent(item.optJSONArrayOrNull("content"))
            )

            "agentMessage" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.AGENT_MESSAGE,
                speaker = CodexTimelineSpeaker.ASSISTANT,
                title = "Codex",
                body = item.optStringOrNull("text").orEmpty(),
                status = item.optStringOrNull("phase")
            )

            "plan" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.PLAN,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Plan item",
                body = item.optStringOrNull("text").orEmpty()
            )

            "reasoning" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.REASONING,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Reasoning",
                body = item.optJSONArrayOrNull("summary")?.toStringList()?.joinToString("\n").orEmpty(),
                detail = item.optJSONArrayOrNull("content")?.toStringList()?.joinToString("\n")
            )

            "commandExecution" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.COMMAND_EXECUTION,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Command execution",
                body = buildString {
                    append(item.optStringOrNull("command").orEmpty())
                    val cwd = item.optStringOrNull("cwd")
                    if (!cwd.isNullOrBlank()) {
                        append("\n")
                        append("cwd: ").append(cwd)
                    }
                },
                detail = item.optStringOrNull("aggregatedOutput"),
                status = item.optStringOrNull("status")
            )

            "fileChange" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.FILE_CHANGE,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "File changes",
                body = item.optJSONArrayOrNull("changes")
                    ?.toJsonObjectList()
                    ?.joinToString("\n") { change ->
                        "${change.optStringOrNull("kind").orEmpty()}: ${change.optStringOrNull("path").orEmpty()}"
                    }
                    .orEmpty(),
                detail = item.optJSONArrayOrNull("changes")
                    ?.toJsonObjectList()
                    ?.joinToString("\n\n") { change ->
                        change.optStringOrNull("diff").orEmpty()
                    },
                status = item.optStringOrNull("status")
            )

            "mcpToolCall" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.MCP_TOOL,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "MCP tool call",
                body = buildString {
                    append(item.optStringOrNull("server").orEmpty())
                    append(" / ")
                    append(item.optStringOrNull("tool").orEmpty())
                },
                detail = buildString {
                    item.opt("arguments")?.takeUnless { it == JSONObject.NULL }?.let {
                        append("args:\n").append(it.toString())
                    }
                    item.opt("result")?.takeUnless { it == JSONObject.NULL }?.let {
                        if (isNotBlank()) append("\n\n")
                        append("result:\n").append(it.toString())
                    }
                    item.opt("error")?.takeUnless { it == JSONObject.NULL }?.let {
                        if (isNotBlank()) append("\n\n")
                        append("error:\n").append(it.toString())
                    }
                }.ifBlank { null },
                status = item.optStringOrNull("status")
            )

            "dynamicToolCall" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.DYNAMIC_TOOL,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Dynamic tool call",
                body = item.optStringOrNull("tool").orEmpty(),
                detail = buildString {
                    item.opt("arguments")?.takeUnless { it == JSONObject.NULL }?.let {
                        append("args:\n").append(it.toString())
                    }
                    val content = item.optJSONArrayOrNull("contentItems")
                        ?.toJsonObjectList()
                        ?.joinToString("\n") { contentItem ->
                            contentItem.optStringOrNull("text")
                                ?: contentItem.optStringOrNull("imageUrl")
                                ?: contentItem.toString()
                        }
                    if (!content.isNullOrBlank()) {
                        if (isNotBlank()) append("\n\n")
                        append("content:\n").append(content)
                    }
                }.ifBlank { null },
                status = item.optStringOrNull("status")
            )

            "webSearch" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.WEB_SEARCH,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Web search",
                body = item.optStringOrNull("query").orEmpty(),
                detail = item.optJSONObjectOrNull("action")?.toString()
            )

            "imageView" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.IMAGE_VIEW,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Image view",
                body = item.optStringOrNull("path").orEmpty()
            )

            "enteredReviewMode",
            "exitedReviewMode" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.REVIEW_MODE,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = if (type == "enteredReviewMode") "Entered review mode" else "Exited review mode",
                body = item.optStringOrNull("review").orEmpty()
            )

            "contextCompaction" -> CodexTimelineEntry(
                itemId = itemId,
                turnId = turnId,
                kind = CodexTimelineKind.CONTEXT_COMPACTION,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Context compaction",
                body = "Codex compacted the thread context."
            )

            else -> CodexTimelineEntry(
                itemId = itemId.ifBlank { "unknown-${System.nanoTime()}" },
                turnId = turnId,
                kind = CodexTimelineKind.STATUS,
                speaker = CodexTimelineSpeaker.SYSTEM,
                title = "Event",
                body = item.toString()
            )
        }
    }

    private fun parseUserContent(content: JSONArray?): String {
        if (content == null) {
            return ""
        }
        return content.toJsonObjectList().joinToString("\n") { part ->
            when (part.optStringOrNull("type")) {
                "text", "input_text" -> part.optStringOrNull("text").orEmpty()
                "image", "input_image" -> "[image] " + (
                    part.optStringOrNull("url")
                        ?: part.optStringOrNull("image_url")
                        ?: ""
                    )
                else -> part.toString()
            }
        }
    }

    private fun sandboxModeString(preset: CodexPermissionsPreset): String {
        return when (preset) {
            CodexPermissionsPreset.WORKSPACE_WRITE -> "workspace-write"
            CodexPermissionsPreset.DANGER_FULL_ACCESS -> "danger-full-access"
        }
    }

    private fun buildSandboxPolicy(project: CodexProject): JSONObject {
        return when (project.permissionsPreset) {
            CodexPermissionsPreset.DANGER_FULL_ACCESS -> {
                jsonObjectOf("type" to "dangerFullAccess")
            }

            CodexPermissionsPreset.WORKSPACE_WRITE -> {
                jsonObjectOf(
                    "type" to "workspaceWrite",
                    "networkAccess" to false,
                    "excludeSlashTmp" to true,
                    "excludeTmpdirEnvVar" to true,
                    "readOnlyAccess" to jsonObjectOf(
                        "type" to "restricted",
                        "includePlatformDefaults" to false,
                        "readableRoots" to listOf(project.codexCwd)
                    ),
                    "writableRoots" to listOf(project.codexCwd)
                )
            }
        }
    }

    private fun currentModelName(): String? {
        return _models.value.firstOrNull { it.isDefault }?.model
            ?: _models.value.firstOrNull()?.model
    }

    private fun serverUrl(): String = AppSettings.getCodexServerUrl(appContext)
}

private fun JSONObject?.threadId(): String? {
    if (this == null) return null
    return optStringOrNull("threadId")
        ?: optJSONObjectOrNull("thread")?.optStringOrNull("id")
}

private fun JSONObject?.turnId(): String? {
    if (this == null) return null
    return optStringOrNull("turnId")
        ?: optJSONObjectOrNull("turn")?.optStringOrNull("id")
}

private fun Map<String, CodexThreadState>.updateThread(
    threadId: String,
    block: CodexThreadState.() -> CodexThreadState
): Map<String, CodexThreadState> {
    val existing = this[threadId] ?: return this
    return this + (threadId to existing.block())
}

private fun List<CodexTimelineEntry>.upsertTimeline(item: CodexTimelineEntry): List<CodexTimelineEntry> {
    val index = indexOfFirst { it.itemId == item.itemId }
    if (index == -1) {
        return this + item
    }
    return toMutableList().apply {
        this[index] = item
    }
}
