package ai.mishell.app

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ai.mishell.app.codex.CodexApprovalRequest
import ai.mishell.app.codex.CodexConnectionState
import ai.mishell.app.codex.CodexFeature
import ai.mishell.app.codex.CodexPermissionsPreset
import ai.mishell.app.codex.CodexProject
import ai.mishell.app.codex.CodexThreadState
import ai.mishell.app.codex.CodexUiFormatter
import ai.mishell.app.codex.TimelineAdapter
import ai.mishell.app.databinding.ActivityCodexThreadBinding
import ai.mishell.app.databinding.DialogCodexApprovalBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CodexThreadActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_THREAD_ID = "thread_id"
    }

    private lateinit var binding: ActivityCodexThreadBinding
    private val repository by lazy { CodexFeature.repository(this) }
    private lateinit var project: CodexProject
    private lateinit var threadId: String
    private val adapter = TimelineAdapter()
    private var approvalDialog: BottomSheetDialog? = null
    private var visibleApprovalKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodexThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        threadId = intent.getStringExtra(EXTRA_THREAD_ID).orEmpty()
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener {
            lifecycleScope.launch {
                loadThread(forceRefresh = true)
            }
        }
        binding.sendButton.setOnClickListener {
            val prompt = binding.composerInput.text?.toString()?.trim().orEmpty()
            if (prompt.isBlank()) {
                return@setOnClickListener
            }
            binding.composerInput.setText("")
            lifecycleScope.launch {
                runCatching {
                    repository.sendTurn(project, threadId, prompt)
                }.onFailure(::showError)
            }
        }
        binding.interruptButton.setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    repository.interruptTurn(threadId)
                }.onFailure(::showError)
            }
        }

        binding.timelineList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        binding.timelineList.adapter = adapter

        lifecycleScope.launch {
            val loadedProject = repository.getProject(projectId)
            if (loadedProject == null || threadId.isBlank()) {
                finish()
                return@launch
            }
            project = loadedProject
            loadThread(forceRefresh = true)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.connectionState.collect { state ->
                        updateConnectionState(state)
                    }
                }
                launch {
                    repository.threadStates.collect { states ->
                        states[threadId]?.let(::renderThreadState)
                    }
                }
                launch {
                    repository.pendingApprovals.collect {
                        maybeShowApproval(repository.getPendingApproval(threadId))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onDisplayForegrounded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            lockLandscapeToCurrentRotation()
            enableImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onDisplayTouchInteraction(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        approvalDialog?.dismiss()
        approvalDialog = null
        clearDisplayPowerModeTimer()
        super.onDestroy()
    }

    private suspend fun loadThread(forceRefresh: Boolean) {
        if (!forceRefresh && repository.getThreadState(threadId) != null) {
            return
        }
        runCatching {
            repository.openThread(project, threadId)
        }.onFailure(::showError)
    }

    private fun renderThreadState(state: CodexThreadState) {
        binding.titleText.text = state.threadTitle
        binding.subtitleText.text = getString(
            R.string.codex_thread_subtitle,
            state.projectName,
            state.sandboxModeLabel
        )
        binding.fullAccessBanner.visibility = if (project.permissionsPreset == CodexPermissionsPreset.DANGER_FULL_ACCESS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.errorText.visibility = if (state.lastError.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.errorText.text = state.lastError
        binding.interruptButton.isEnabled = state.isActiveTurn
        adapter.submitList(state.timeline)
        if (state.timeline.isNotEmpty()) {
            binding.timelineList.scrollToPosition(state.timeline.lastIndex)
        }
    }

    private fun updateConnectionState(state: CodexConnectionState) {
        binding.connectionChip.text = CodexUiFormatter.connectionLabel(state)
    }

    private fun maybeShowApproval(request: CodexApprovalRequest?) {
        if (request == null) {
            visibleApprovalKey = null
            approvalDialog?.dismiss()
            approvalDialog = null
            return
        }
        if (visibleApprovalKey == request.requestKey && approvalDialog?.isShowing == true) {
            return
        }
        visibleApprovalKey = request.requestKey
        approvalDialog?.dismiss()

        val dialogBinding = DialogCodexApprovalBinding.inflate(layoutInflater)
        dialogBinding.summaryText.text = request.summary
        dialogBinding.detailText.text = request.detail

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(false)
        dialogBinding.allowOnceButton.setOnClickListener {
            lifecycleScope.launch {
                repository.respondToApproval(request, "accept")
            }
            dialog.dismiss()
        }
        dialogBinding.allowSessionButton.setOnClickListener {
            lifecycleScope.launch {
                repository.respondToApproval(request, "acceptForSession")
            }
            dialog.dismiss()
        }
        dialogBinding.declineButton.setOnClickListener {
            lifecycleScope.launch {
                repository.respondToApproval(request, "decline")
            }
            dialog.dismiss()
        }
        dialogBinding.cancelButton.setOnClickListener {
            lifecycleScope.launch {
                repository.respondToApproval(request, "cancel")
            }
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            if (visibleApprovalKey == request.requestKey) {
                visibleApprovalKey = null
            }
        }
        approvalDialog = dialog
        dialog.show()
    }

    private fun showError(error: Throwable) {
        if (error is CancellationException) {
            return
        }
        Toast.makeText(
            this,
            getString(R.string.codex_open_failed, error.message ?: "unknown error"),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun enableImmersiveMode() {
        applyAlwaysOnUltraDimMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
