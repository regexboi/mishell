package ai.mishell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STT_URL = "https://mishell.mishcaslab.com/api/speech/transcribe"
        private const val SQUIRT_MIN_WPM = 180
        private const val SQUIRT_MAX_WPM = 1100
        private const val SQUIRT_WPM_STEP = 20
        private const val SQUIRT_DEFAULT_WPM = 420
        private const val SQUIRT_RAMP_WORD_COUNT = 18
        private const val SQUIRT_RAMP_FLOOR_MULTIPLIER = 0.45f
        private const val SQUIRT_BASE_TEXT_SIZE_SP = 40f
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
    @Volatile
    private var isSquirtMode = false
    @Volatile
    private var isAssistantStreamComplete = true
    private var squirtTargetWpm = SQUIRT_DEFAULT_WPM
    private var squirtPlaybackJob: Job? = null
    private var latestTranscript = ""
    private var squirtWordTextSizeSp = SQUIRT_BASE_TEXT_SIZE_SP
    private lateinit var terminalTapDetector: GestureDetector
    private val defaultConstraints = ConstraintSet()
    private val fullscreenConstraints = ConstraintSet()
    private val assistantTextLock = Any()
    private val assistantTextBuffer = StringBuilder(1024)
    private val squirtQueueLock = Any()
    private val squirtQueue = ArrayDeque<String>()
    private val squirtCarry = StringBuilder(128)
    private val tmpViewLocation = IntArray(2)

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
        setupSquirtControls()
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

        renderSquirtPlaceholder()
        binding.bottomBanner.isSelected = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::binding.isInitialized) {
            if (handleSquirtTwoFingerToggle(event)) {
                return true
            }
            if (isPointInsideViewRaw(binding.diagnosticPanel, event.rawX, event.rawY)) {
                terminalTapDetector.onTouchEvent(event)
            }
        }
        return super.dispatchTouchEvent(event)
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

    private fun setupSquirtControls() {
        binding.squirtSpeedDown.setOnClickListener {
            adjustSquirtSpeed(-SQUIRT_WPM_STEP)
        }
        binding.squirtSpeedUp.setOnClickListener {
            adjustSquirtSpeed(SQUIRT_WPM_STEP)
        }
        applySquirtWordTextSize(SQUIRT_BASE_TEXT_SIZE_SP)
        updateSquirtSpeedLabel()
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

        terminalTapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (shouldHandleTerminalSingleTap(e.rawX, e.rawY)) {
                        toggleTerminalFullscreen()
                        return true
                    }
                    return false
                }
            }
        )
        binding.diagnosticHeader.setOnClickListener { toggleTerminalFullscreen() }
    }

    private fun toggleTerminalFullscreen() {
        setTerminalFullscreen(!isTerminalFullscreen)
    }

    private fun handleSquirtTwoFingerToggle(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount < 2) {
            return false
        }

        val rawOffsetX = event.rawX - event.x
        val rawOffsetY = event.rawY - event.y
        val firstInside = isPointInsideViewRaw(
            binding.diagnosticPanel,
            event.getX(0) + rawOffsetX,
            event.getY(0) + rawOffsetY
        )
        val secondInside = isPointInsideViewRaw(
            binding.diagnosticPanel,
            event.getX(1) + rawOffsetX,
            event.getY(1) + rawOffsetY
        )
        if (!firstInside || !secondInside) {
            return false
        }

        toggleSquirtMode()
        return true
    }

    private fun shouldHandleTerminalSingleTap(rawX: Float, rawY: Float): Boolean {
        return if (isSquirtMode) {
            isPointInsideViewRaw(binding.squirtWordRow, rawX, rawY)
        } else {
            isPointInsideViewRaw(binding.terminalScroll, rawX, rawY)
        }
    }

    private fun isPointInsideViewRaw(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) {
            return false
        }
        view.getLocationOnScreen(tmpViewLocation)
        val left = tmpViewLocation[0].toFloat()
        val top = tmpViewLocation[1].toFloat()
        val right = left + view.width
        val bottom = top + view.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun toggleSquirtMode() {
        setSquirtMode(!isSquirtMode)
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

    private fun setSquirtMode(enabled: Boolean) {
        if (isSquirtMode == enabled) {
            return
        }
        isSquirtMode = enabled
        TransitionManager.beginDelayedTransition(
            binding.diagnosticPanel,
            AutoTransition().apply {
                duration = 220L
                interpolator = DecelerateInterpolator(1.4f)
            }
        )
        binding.diagnosticHeader.text = if (enabled) {
            getString(R.string.diagnostic_title_squirt)
        } else {
            getString(R.string.diagnostic_title)
        }
        binding.terminalScroll.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.squirtContainer.visibility = if (enabled) View.VISIBLE else View.GONE

        if (enabled) {
            rebuildSquirtQueueFromAssistantText()
            ensureSquirtPlaybackLoop()
        } else {
            squirtPlaybackJob?.cancel()
            squirtPlaybackJob = null
            val transcript = latestTranscript
            if (transcript.isNotBlank()) {
                renderTranscriptAndAssistant(transcript, getAssistantTextSnapshot())
            }
        }
    }

    private fun adjustSquirtSpeed(delta: Int) {
        squirtTargetWpm = (squirtTargetWpm + delta).coerceIn(SQUIRT_MIN_WPM, SQUIRT_MAX_WPM)
        updateSquirtSpeedLabel()
    }

    private fun updateSquirtSpeedLabel() {
        binding.squirtSpeedLabel.text = getString(R.string.squirt_speed_label, squirtTargetWpm)
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
        resetAssistantStreamState()
        isBusy = true
        setMicButtonEnabled(false)
        binding.micStatus.text = getString(R.string.mic_transcribing)
        binding.terminalOutput.text = getString(R.string.terminal_transcribing)

        activeWorkJob = lifecycleScope.launch {
            var transcript = ""
            var errorMessage: String? = null
            try {
                transcript = withContext(Dispatchers.IO) {
                    transcribeRecording(file)
                }
                latestTranscript = transcript

                if (transcript.isBlank()) {
                    isAssistantStreamComplete = true
                    withContext(Dispatchers.Main.immediate) {
                        binding.terminalOutput.text = getString(R.string.terminal_empty_transcript)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main.immediate) {
                    binding.micStatus.text = getString(R.string.mic_generating)
                    binding.terminalOutput.text = getString(R.string.terminal_generating, transcript)
                    renderSquirtPlaceholder()
                    ensureSquirtPlaybackLoop()
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
                                if (isSquirtMode) {
                                    appendAssistantChunk(event.text)
                                    enqueueSquirtWords(event.text)
                                    if (squirtPlaybackJob?.isActive != true) {
                                        runOnUiThread {
                                            ensureSquirtPlaybackLoop()
                                        }
                                    }
                                } else {
                                    val fullAssistantText = appendAssistantChunkAndSnapshot(event.text)
                                    runOnUiThread {
                                        renderTranscriptAndAssistant(transcript, fullAssistantText)
                                    }
                                }
                            }
                            SseEvent.Done -> {
                                isAssistantStreamComplete = true
                                flushSquirtCarryWord()
                                if (isSquirtMode) {
                                    runOnUiThread {
                                        ensureSquirtPlaybackLoop()
                                    }
                                }
                            }
                            is SseEvent.Error -> throw IOException(event.message)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "request failed"
            } finally {
                isAssistantStreamComplete = true
                flushSquirtCarryWord()
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
                    } else if (transcript.isNotBlank() && getAssistantTextSnapshot().isBlank()) {
                        renderTranscriptAndAssistant(transcript, "")
                    } else {
                        ensureSquirtPlaybackLoop()
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

    private fun resetAssistantStreamState() {
        synchronized(assistantTextLock) {
            assistantTextBuffer.setLength(0)
        }
        synchronized(squirtQueueLock) {
            squirtQueue.clear()
            squirtCarry.setLength(0)
        }
        isAssistantStreamComplete = false
        squirtPlaybackJob?.cancel()
        squirtPlaybackJob = null
    }

    private fun appendAssistantChunk(chunk: String) {
        synchronized(assistantTextLock) {
            assistantTextBuffer.append(chunk)
        }
    }

    private fun appendAssistantChunkAndSnapshot(chunk: String): String {
        synchronized(assistantTextLock) {
            assistantTextBuffer.append(chunk)
            return assistantTextBuffer.toString()
        }
    }

    private fun getAssistantTextSnapshot(): String {
        synchronized(assistantTextLock) {
            return assistantTextBuffer.toString()
        }
    }

    private fun rebuildSquirtQueueFromAssistantText() {
        synchronized(squirtQueueLock) {
            squirtQueue.clear()
            squirtCarry.setLength(0)
        }
        enqueueSquirtWords(getAssistantTextSnapshot())
        if (isAssistantStreamComplete) {
            flushSquirtCarryWord()
        }
        renderSquirtPlaceholder()
    }

    private fun enqueueSquirtWords(textChunk: String) {
        if (textChunk.isEmpty()) {
            return
        }
        synchronized(squirtQueueLock) {
            squirtCarry.append(textChunk)
            var tokenStart = 0
            var cursor = 0
            while (cursor < squirtCarry.length) {
                if (squirtCarry[cursor].isWhitespace()) {
                    if (tokenStart < cursor) {
                        squirtQueue.addLast(squirtCarry.substring(tokenStart, cursor))
                    }
                    while (cursor < squirtCarry.length && squirtCarry[cursor].isWhitespace()) {
                        cursor += 1
                    }
                    tokenStart = cursor
                } else {
                    cursor += 1
                }
            }
            if (tokenStart > 0) {
                val remaining = squirtCarry.substring(tokenStart)
                squirtCarry.setLength(0)
                squirtCarry.append(remaining)
            }
        }
    }

    private fun flushSquirtCarryWord() {
        synchronized(squirtQueueLock) {
            if (squirtCarry.isNotEmpty()) {
                squirtQueue.addLast(squirtCarry.toString())
                squirtCarry.setLength(0)
            }
        }
    }

    private fun ensureSquirtPlaybackLoop() {
        if (!isSquirtMode || squirtPlaybackJob?.isActive == true) {
            return
        }
        squirtPlaybackJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            var shownWords = 0
            while (isActive && isSquirtMode) {
                val nextWord = pollNextSquirtWord()
                if (nextWord == null) {
                    if (isAssistantStreamComplete) {
                        break
                    }
                    delay(8L)
                    continue
                }

                shownWords += 1
                renderSquirtWord(nextWord)
                delay(computeSquirtDelayMs(nextWord, shownWords))
            }
            squirtPlaybackJob = null
        }
    }

    private fun pollNextSquirtWord(): String? {
        synchronized(squirtQueueLock) {
            return if (squirtQueue.isEmpty()) null else squirtQueue.removeFirst()
        }
    }

    private fun computeSquirtDelayMs(word: String, shownWords: Int): Long {
        val rampProgress = min(1f, shownWords / SQUIRT_RAMP_WORD_COUNT.toFloat())
        val effectiveWpm = squirtTargetWpm * (
            SQUIRT_RAMP_FLOOR_MULTIPLIER +
                ((1f - SQUIRT_RAMP_FLOOR_MULTIPLIER) * rampProgress)
            )
        val punctuationMultiplier = when {
            word.endsWith(".") || word.endsWith("!") || word.endsWith("?") -> 1.65f
            word.endsWith(",") || word.endsWith(";") || word.endsWith(":") -> 1.35f
            else -> 1f
        }
        return ((60_000f / effectiveWpm) * punctuationMultiplier)
            .roundToLong()
            .coerceAtLeast(10L)
    }

    private fun renderSquirtWord(rawWord: String) {
        val word = rawWord.trim()
        if (word.isEmpty()) {
            return
        }
        applySquirtWordTextSize(computeSquirtWordSize(word.length))
        val anchorIndex = computeAnchorIndex(word)
        binding.squirtLeft.text = word.substring(0, anchorIndex)
        binding.squirtAnchor.text = word.substring(anchorIndex, anchorIndex + 1)
        binding.squirtRight.text = word.substring(anchorIndex + 1)
    }

    private fun renderSquirtPlaceholder() {
        binding.squirtLeft.text = ""
        binding.squirtAnchor.text = "•"
        binding.squirtRight.text = ""
    }

    private fun computeAnchorIndex(word: String): Int {
        val length = word.length
        if (length <= 1) return 0
        val baseOrp = when {
            length <= 5 -> 1
            length <= 9 -> 2
            length <= 13 -> 3
            else -> 4
        }
        // Avoid excessive right-side clipping on long tokens while keeping anchor fixed on center line.
        val maxRightChars = (length / 2) + 2
        val minAnchorIndex = (length - 1 - maxRightChars).coerceAtLeast(0)
        return baseOrp.coerceIn(minAnchorIndex, length - 1)
    }

    private fun computeSquirtWordSize(length: Int): Float {
        return when {
            length <= 12 -> SQUIRT_BASE_TEXT_SIZE_SP
            length <= 16 -> 36f
            length <= 20 -> 32f
            length <= 26 -> 28f
            else -> 24f
        }
    }

    private fun applySquirtWordTextSize(sizeSp: Float) {
        if (squirtWordTextSizeSp == sizeSp) {
            return
        }
        squirtWordTextSizeSp = sizeSp
        binding.squirtLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.squirtAnchor.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.squirtRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
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
