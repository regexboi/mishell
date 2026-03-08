package ai.mishell.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ai.mishell.app.codex.CodexFeature
import ai.mishell.app.codex.CodexPermissionsPreset
import ai.mishell.app.codex.CodexProject
import ai.mishell.app.codex.CodexUiFormatter
import ai.mishell.app.codex.ThreadListAdapter
import ai.mishell.app.databinding.ActivityCodexThreadsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CodexThreadsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var binding: ActivityCodexThreadsBinding
    private val repository by lazy { CodexFeature.repository(this) }
    private lateinit var project: CodexProject
    private val adapter = ThreadListAdapter { thread ->
        startActivity(
            Intent(this, CodexThreadActivity::class.java)
                .putExtra(CodexThreadActivity.EXTRA_PROJECT_ID, project.id)
                .putExtra(CodexThreadActivity.EXTRA_THREAD_ID, thread.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodexThreadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener {
            lifecycleScope.launch {
                refreshThreads()
            }
        }
        binding.newThreadButton.setOnClickListener {
            lifecycleScope.launch {
                runCatching {
                    repository.createThread(project)
                }.onSuccess { thread ->
                    startActivity(
                        Intent(this@CodexThreadsActivity, CodexThreadActivity::class.java)
                            .putExtra(CodexThreadActivity.EXTRA_PROJECT_ID, project.id)
                            .putExtra(CodexThreadActivity.EXTRA_THREAD_ID, thread.id)
                    )
                }.onFailure { error ->
                    showError(error)
                }
            }
        }

        binding.threadsList.layoutManager = LinearLayoutManager(this)
        binding.threadsList.adapter = adapter

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        lifecycleScope.launch {
            val loadedProject = repository.getProject(projectId)
            if (loadedProject == null) {
                finish()
                return@launch
            }
            project = loadedProject
            bindProject()
            refreshThreads()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.connectionState.collect { state ->
                    binding.statusText.text = getString(
                        R.string.codex_connection_status,
                        "${CodexUiFormatter.connectionLabel(state)} • ${AppSettings.getCodexServerUrl(this@CodexThreadsActivity)}"
                    )
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
        clearDisplayPowerModeTimer()
        super.onDestroy()
    }

    private fun bindProject() {
        binding.titleText.text = project.name
        binding.pathText.text = project.codexCwd
        binding.permissionsChip.text = when (project.permissionsPreset) {
            CodexPermissionsPreset.WORKSPACE_WRITE -> "workspace-write"
            CodexPermissionsPreset.DANGER_FULL_ACCESS -> "danger-full-access"
        }
    }

    private suspend fun refreshThreads() {
        runCatching {
            repository.listThreads(project)
        }.onSuccess { threads ->
            adapter.submitList(threads)
            binding.emptyText.visibility = if (threads.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }.onFailure { error ->
            showError(error)
        }
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
