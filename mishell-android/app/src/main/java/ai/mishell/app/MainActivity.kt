package ai.mishell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintSet
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ai.mishell.app.databinding.ActivityMainBinding
import ai.mishell.app.network.LlmStreamClient
import ai.mishell.app.network.SseEvent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STT_URL = "https://mishell.mishcaslab.com/api/speech/transcribe"
    }

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var isBusy = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val httpClient = OkHttpClient()
    private val llmStreamClient = LlmStreamClient(httpClient)
    private val sessionId = UUID.randomUUID().toString()
    private var activeWorkJob: Job? = null
    private var activeSttCall: Call? = null
    private var activeLlmCall: Call? = null
    private var isTerminalFullscreen = false
    private val defaultConstraints = ConstraintSet()
    private val fullscreenConstraints = ConstraintSet()

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
        setupTerminalFullscreenToggle()
        setupMicButton()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTerminalFullscreen) {
                    setTerminalFullscreen(false)
                    return
                }

                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

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
        val onMicClick = View.OnClickListener {
            when {
                isBusy -> Unit
                isRecording -> stopRecordingAndTranscribe()
                else -> ensureAudioPermissionAndStart()
            }
        }
        binding.micButton.setOnClickListener(onMicClick)
    }

    private fun setupTerminalFullscreenToggle() {
        defaultConstraints.clone(binding.root)
        fullscreenConstraints.clone(binding.root)
        fullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )
        fullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.END,
            binding.guideCenterEnd.id,
            ConstraintSet.START
        )
        fullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )
        fullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )
        fullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.START, 0)
        fullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.END, 12)
        fullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.TOP, 0)
        fullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.BOTTOM, 0)

        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    toggleTerminalFullscreen()
                    return true
                }
            }
        )
        binding.terminalScroll.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            false
        }
        binding.diagnosticHeader.setOnClickListener { toggleTerminalFullscreen() }
    }

    private fun toggleTerminalFullscreen() {
        setTerminalFullscreen(!isTerminalFullscreen)
    }

    private fun setTerminalFullscreen(fullscreen: Boolean) {
        if (isTerminalFullscreen == fullscreen) {
            return
        }
        isTerminalFullscreen = fullscreen
        TransitionManager.beginDelayedTransition(
            binding.root,
            AutoTransition().apply {
                duration = 320L
                interpolator = DecelerateInterpolator(1.6f)
            }
        )

        if (fullscreen) {
            fullscreenConstraints.applyTo(binding.root)
            binding.iconGrid.visibility = View.GONE
            binding.bottomBanner.visibility = View.GONE
        } else {
            defaultConstraints.applyTo(binding.root)
            binding.iconGrid.visibility = View.VISIBLE
            binding.bottomBanner.visibility = View.VISIBLE
        }

        scrollTerminalToBottom()
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
            setMicButtonSelected(true)
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
        cancelActiveWork()
        isBusy = true
        setMicButtonEnabled(false)
        binding.micStatus.text = getString(R.string.mic_transcribing)
        binding.terminalOutput.text = getString(R.string.terminal_transcribing)

        activeWorkJob = lifecycleScope.launch {
            var transcript = ""
            var assistantText = ""
            var errorMessage: String? = null
            try {
                transcript = withContext(Dispatchers.IO) {
                    transcribeRecording(file)
                }

                if (transcript.isBlank()) {
                    withContext(Dispatchers.Main.immediate) {
                        binding.terminalOutput.text = getString(R.string.terminal_empty_transcript)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main.immediate) {
                    binding.micStatus.text = getString(R.string.mic_generating)
                    binding.terminalOutput.text = getString(R.string.terminal_generating, transcript)
                    scrollTerminalToBottom()
                }

                withContext(Dispatchers.IO) {
                    llmStreamClient.streamText(
                        url = BuildConfig.LLM_STREAM_URL,
                        apiKey = BuildConfig.STT_API_KEY,
                        text = transcript,
                        sessionId = sessionId,
                        onCallLifecycle = { call -> activeLlmCall = call },
                    ) { event ->
                        when (event) {
                            is SseEvent.Delta -> {
                                assistantText += event.text
                                runOnUiThread {
                                    renderTranscriptAndAssistant(transcript, assistantText)
                                }
                            }
                            SseEvent.Done -> Unit
                            is SseEvent.Error -> throw IOException(event.message)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "request failed"
            } finally {
                file.delete()
                recordingFile = null
                activeSttCall = null
                activeLlmCall = null

                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    isBusy = false
                    setMicButtonEnabled(true)
                    binding.micStatus.text = getString(R.string.mic_idle)

                    if (errorMessage != null) {
                        showTerminalError(errorMessage ?: "request failed")
                    } else if (transcript.isNotBlank() && assistantText.isBlank()) {
                        renderTranscriptAndAssistant(transcript, "")
                    }
                }

                activeWorkJob = null
            }
        }
    }

    private fun transcribeRecording(file: File): String {
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
            .addHeader("x-api-key", BuildConfig.STT_API_KEY)
            .post(requestBody)
            .build()

        val call = httpClient.newCall(request)
        activeSttCall = call
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(300)}")
            }
            return extractTranscript(body)
        }
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
        setMicButtonSelected(false)
        binding.micStatus.text = getString(R.string.mic_idle)
    }

    private fun setMicButtonEnabled(enabled: Boolean) {
        binding.micButton.isEnabled = enabled
    }

    private fun setMicButtonSelected(selected: Boolean) {
        binding.micButton.isSelected = selected
    }

    private fun showTerminalError(message: String) {
        binding.terminalOutput.text = getString(R.string.terminal_error, message)
        scrollTerminalToBottom()
    }

    private fun renderTranscriptAndAssistant(transcript: String, assistantText: String) {
        binding.terminalOutput.text = buildString {
            append("USR://")
            append(transcript)
            append("\n\nMISHELL://")
            append(assistantText)
        }
        scrollTerminalToBottom()
    }

    private fun scrollTerminalToBottom() {
        binding.terminalScroll.post {
            binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun cancelActiveWork() {
        activeWorkJob?.cancel()
        activeSttCall?.cancel()
        activeLlmCall?.cancel()
        activeWorkJob = null
        activeSttCall = null
        activeLlmCall = null
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
        cancelActiveWork()
        if (isRecording) {
            runCatching { mediaRecorder?.stop() }
        }
        releaseRecorder()
        recordingFile?.delete()
        recordingFile = null
        super.onDestroy()
    }
}
