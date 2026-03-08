package ai.mishell.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import ai.mishell.app.codex.CodexUiFormatter
import ai.mishell.app.codex.ProjectListAdapter
import ai.mishell.app.databinding.ActivityCodexProjectsBinding
import ai.mishell.app.databinding.DialogCodexProjectBinding
import ai.mishell.app.databinding.DialogCodexServerBinding
import kotlinx.coroutines.launch

class CodexProjectsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCodexProjectsBinding
    private val repository by lazy { CodexFeature.repository(this) }
    private val adapter = ProjectListAdapter { project ->
        startActivity(
            Intent(this, CodexThreadsActivity::class.java)
                .putExtra(CodexThreadsActivity.EXTRA_PROJECT_ID, project.id)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodexProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        binding.backButton.setOnClickListener { finish() }
        binding.serverSettingsButton.setOnClickListener { showServerDialog() }
        binding.addProjectButton.setOnClickListener { showAddProjectDialog() }
        binding.refreshButton.setOnClickListener {
            lifecycleScope.launch {
                refreshProjects()
            }
        }

        binding.projectsList.layoutManager = LinearLayoutManager(this)
        binding.projectsList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.connectionState.collect { state ->
                        binding.connectionChip.text = CodexUiFormatter.connectionLabel(state)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onDisplayForegrounded()
        binding.serverUrlText.text = AppSettings.getCodexServerUrl(this)
        lifecycleScope.launch {
            refreshProjects()
        }
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

    private suspend fun refreshProjects() {
        binding.serverUrlText.text = AppSettings.getCodexServerUrl(this)
        val projects = repository.listProjects()
        adapter.submitList(projects)
        binding.emptyText.visibility = if (projects.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        repository.refreshModels()
    }

    private fun showServerDialog() {
        val dialogBinding = DialogCodexServerBinding.inflate(layoutInflater)
        dialogBinding.serverUrlInput.setText(AppSettings.getCodexServerUrl(this))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.codex_server_settings))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.wispr_send)) { _, _ ->
                AppSettings.setCodexServerUrl(this, dialogBinding.serverUrlInput.text?.toString().orEmpty())
                binding.serverUrlText.text = AppSettings.getCodexServerUrl(this)
                Toast.makeText(this, R.string.codex_server_url_saved, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { repository.refreshModels() }
            }
            .setNegativeButton(getString(R.string.wispr_modal_cancel), null)
            .show()
    }

    private fun showAddProjectDialog() {
        val dialogBinding = DialogCodexProjectBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.codex_add_project))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.codex_add_project), null)
            .setNegativeButton(getString(R.string.wispr_modal_cancel), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = dialogBinding.projectNameInput.text?.toString()?.trim().orEmpty()
                        val path = dialogBinding.projectPathInput.text?.toString()?.trim().orEmpty()
                        if (name.isBlank() || path.isBlank() || !path.startsWith("/")) {
                            Toast.makeText(this, R.string.codex_project_invalid, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val preset = if (dialogBinding.fullAccessSwitch.isChecked) {
                            CodexPermissionsPreset.DANGER_FULL_ACCESS
                        } else {
                            CodexPermissionsPreset.WORKSPACE_WRITE
                        }
                        lifecycleScope.launch {
                            repository.addProject(name, path, preset)
                            refreshProjects()
                            Toast.makeText(this@CodexProjectsActivity, R.string.codex_project_added, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
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
