package ai.mishell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import ai.mishell.app.databinding.ActivityMainBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STT_URL = "https://mishell.mishcaslab.com/api/speech/transcribe"
        private const val STT_API_KEY = "REDACTED_REMOVED"
    }

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var isUploading = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val httpClient = OkHttpClient()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            showTerminalError(getString(R.string.mic_permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()
        setupTiles()
        setupMicButton()

        binding.bottomBanner.isSelected = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun setupTiles() {
        val tiles = listOf(
            binding.tile1,
            binding.tile2,
            binding.tile3,
            binding.tile4
        )

        tiles.forEachIndexed { index, view ->
            view.setOnClickListener {
                startActivity(
                    Intent(this, PlaceholderActivity::class.java)
                        .putExtra(PlaceholderActivity.EXTRA_PLACEHOLDER_NUMBER, index + 1)
                )
            }
        }
    }

    private fun setupMicButton() {
        binding.micButton.setOnClickListener {
            when {
                isUploading -> Unit
                isRecording -> stopRecordingAndTranscribe()
                else -> ensureAudioPermissionAndStart()
            }
        }
    }

    private fun ensureAudioPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
            return
        }
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val outputFile = File.createTempFile("mishell-recording-", ".m4a", cacheDir)
        try {
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            recordingFile = outputFile
            isRecording = true
            binding.micButton.isSelected = true
            binding.micStatus.text = getString(R.string.mic_listening)
            binding.terminalOutput.text = getString(R.string.terminal_recording)
        } catch (error: Exception) {
            outputFile.delete()
            releaseRecorder()
            showTerminalError(getString(R.string.mic_start_failed, error.message ?: "unknown error"))
        }
    }

    private fun stopRecordingAndTranscribe() {
        val file = recordingFile ?: run {
            resetIdleMicState()
            return
        }

        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            file.delete()
            releaseRecorder()
            recordingFile = null
            showTerminalError(getString(R.string.mic_recording_too_short))
            resetIdleMicState()
            return
        }

        releaseRecorder()
        resetIdleMicState()
        uploadRecording(file)
    }

    private fun uploadRecording(file: File) {
        isUploading = true
        binding.micButton.isEnabled = false
        binding.micStatus.text = getString(R.string.mic_transcribing)
        binding.terminalOutput.text = getString(R.string.terminal_transcribing)

        Thread {
            var transcript = ""
            var errorMessage: String? = null
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "audio",
                        file.name,
                        file.asRequestBody("audio/mp4".toMediaType())
                    )
                    .addFormDataPart("language", "en")
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val request = Request.Builder()
                    .url(STT_URL)
                    .addHeader("x-api-key", STT_API_KEY)
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${body.take(300)}")
                    }
                    transcript = extractTranscript(body)
                }
            } catch (error: Exception) {
                errorMessage = error.message ?: "transcription failed"
            } finally {
                file.delete()
                recordingFile = null
            }

            runOnUiThread {
                isUploading = false
                binding.micButton.isEnabled = true
                binding.micStatus.text = getString(R.string.mic_idle)

                binding.terminalOutput.text = if (errorMessage != null) {
                    getString(R.string.terminal_error, errorMessage)
                } else {
                    transcript.ifBlank { getString(R.string.terminal_empty_transcript) }
                }
            }
        }.start()
    }

    private fun extractTranscript(responseBody: String): String {
        val body = responseBody.trim()
        if (body.isBlank()) {
            return ""
        }

        return try {
            val json = JSONObject(body)
            val directText = json.optString("text")
            if (directText.isNotBlank()) {
                directText
            } else {
                val transcript = json.optString("transcript")
                if (transcript.isNotBlank()) {
                    transcript
                } else {
                    val data = json.optJSONObject("data")
                    val nested = data?.optString("text").orEmpty()
                    if (nested.isNotBlank()) nested else body
                }
            }
        } catch (_: Exception) {
            body
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun resetIdleMicState() {
        isRecording = false
        binding.micButton.isSelected = false
        binding.micStatus.text = getString(R.string.mic_idle)
    }

    private fun showTerminalError(message: String) {
        binding.terminalOutput.text = getString(R.string.terminal_error, message)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        if (isRecording) {
            runCatching { mediaRecorder?.stop() }
        }
        releaseRecorder()
        recordingFile?.delete()
        recordingFile = null
        super.onDestroy()
    }
}
