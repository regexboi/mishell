package ai.mishell.app

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ai.mishell.app.databinding.ActivityConfigBinding
import ai.mishell.app.network.ClawdiaGatewayClient
import ai.mishell.app.network.probeTlsFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.coroutines.resume

class ConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigBinding
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        binding.wisprSwitch.isChecked = AppSettings.isWisprTextModeEnabled(this)
        binding.wisprSwitch.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setWisprTextModeEnabled(this, enabled)
        }

        binding.displayPowerSwitch.isChecked = AppSettings.isAlwaysOnUltraDimEnabled(this)
        binding.displayPowerSwitch.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setAlwaysOnUltraDimEnabled(this, enabled)
            applyAlwaysOnUltraDimMode()
        }

        binding.orientationLockSwitch.isChecked = AppSettings.isOrientationLockEnabled(this)
        binding.orientationLockSwitch.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setOrientationLockEnabled(this, enabled)
            lockLandscapeToCurrentRotation()
        }

        when (AppSettings.getBackendMode(this)) {
            AppSettings.BackendMode.MISHELL -> binding.backendMishellRadio.isChecked = true
            AppSettings.BackendMode.CLAWDIA -> binding.backendClawdiaRadio.isChecked = true
        }
        binding.backendRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.backendClawdiaRadio.id) {
                AppSettings.BackendMode.CLAWDIA
            } else {
                AppSettings.BackendMode.MISHELL
            }
            AppSettings.setBackendMode(this, mode)
        }

        binding.clawdiaUrlInput.setText(AppSettings.getClawdiaGatewayUrl(this))
        binding.clawdiaTokenInput.setText(AppSettings.getClawdiaToken(this))
        binding.clawdiaPasswordInput.setText(AppSettings.getClawdiaPassword(this))
        binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_idle)

        binding.clawdiaSaveTestButton.setOnClickListener {
            saveAndTestClawdia()
        }

        binding.backButton.setOnClickListener { finish() }
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

    private fun saveAndTestClawdia() {
        val url = binding.clawdiaUrlInput.text?.toString()?.trim().orEmpty()
        val token = binding.clawdiaTokenInput.text?.toString()?.trim().orEmpty()
        val password = binding.clawdiaPasswordInput.text?.toString()?.trim().orEmpty()

        AppSettings.setClawdiaGatewayUrl(this, url)
        AppSettings.setClawdiaToken(this, token)
        AppSettings.setClawdiaPassword(this, password)

        if (token.isBlank() && password.isBlank()) {
            binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_error, getString(R.string.config_clawdia_missing_auth))
            return
        }

        val initialConfig = AppSettings.getClawdiaConnectionConfig(this)
        if (initialConfig == null) {
            binding.clawdiaStatusText.text = getString(
                R.string.config_clawdia_status_error,
                getString(R.string.config_clawdia_invalid_url)
            )
            return
        }

        lifecycleScope.launch {
            binding.clawdiaSaveTestButton.isEnabled = false
            try {
                binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_saving)
                val config = ensureTlsTrustedIfNeeded(initialConfig)
                binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_testing)

                val testResult = withContext(Dispatchers.IO) {
                    ClawdiaGatewayClient(applicationContext, httpClient).testConnection(config)
                }
                binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_ok) + "\n" + testResult
            } catch (error: Exception) {
                val message = error.message?.trim().orEmpty().ifBlank { "unknown error" }
                binding.clawdiaStatusText.text = getString(R.string.config_clawdia_status_error, message)
            } finally {
                binding.clawdiaSaveTestButton.isEnabled = true
            }
        }
    }

    private suspend fun ensureTlsTrustedIfNeeded(
        config: AppSettings.ClawdiaConnectionConfig
    ): AppSettings.ClawdiaConnectionConfig {
        if (!config.endpoint.tls || !config.expectedFingerprint.isNullOrBlank()) {
            return config
        }

        val fingerprint = withContext(Dispatchers.IO) {
            probeTlsFingerprint(config.endpoint.host, config.endpoint.port)
        } ?: throw IOException("Failed to read TLS fingerprint from gateway")

        val trustAccepted = promptFingerprintTrust(fingerprint)
        if (!trustAccepted) {
            throw IOException("TLS trust cancelled")
        }

        AppSettings.setTrustedTlsFingerprint(this, config.endpoint.stableId, fingerprint)
        return AppSettings.getClawdiaConnectionConfig(this)
            ?: throw IOException("Failed to reload trusted Clawdia config")
    }

    private suspend fun promptFingerprintTrust(fingerprint: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.config_clawdia_tls_trust_title))
                .setMessage(getString(R.string.config_clawdia_tls_trust_message, fingerprint))
                .setPositiveButton(getString(R.string.config_clawdia_tls_trust_confirm)) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(getString(R.string.config_clawdia_tls_trust_cancel)) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }
                .create()

            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private fun enableImmersiveMode() {
        applyAlwaysOnUltraDimMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
