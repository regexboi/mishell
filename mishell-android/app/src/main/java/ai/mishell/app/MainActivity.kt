package ai.mishell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintSet
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ai.mishell.app.databinding.ActivityMainBinding
import ai.mishell.app.network.ClawdiaGatewayClient
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
import com.google.android.material.button.MaterialButton
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {
    companion object {
        private const val LOG_TAG = "MishellVoice"
        private const val STT_MAX_ATTEMPTS = 3
        private const val SQUIRT_MIN_WPM = 180
        private const val SQUIRT_MAX_WPM = 1100
        private const val SQUIRT_WPM_STEP = 20
        private const val SQUIRT_DEFAULT_WPM = 420
        private const val SQUIRT_RAMP_WORD_COUNT = 18
        private const val SQUIRT_RAMP_FLOOR_MULTIPLIER = 0.45f
        private const val SQUIRT_BASE_TEXT_SIZE_SP = 40f
        private const val MAX_STREAM_DETAIL_LINES = 120
        private const val MAX_TERMINAL_STREAM_BLOCKS = 160
        private const val TERMINAL_SWIPE_MIN_DISTANCE_DP = 72f
        private const val TERMINAL_SWIPE_MIN_VELOCITY_DP = 240f
    }

    private lateinit var binding: ActivityMainBinding
    private var isRecording = false
    private var isBusy = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val httpClient = OkHttpClient()
    private val llmStreamClient = LlmStreamClient(httpClient)
    private val clawdiaGatewayClient by lazy { ClawdiaGatewayClient(applicationContext, httpClient) }
    private val sessionId = UUID.randomUUID().toString()
    private val clawdiaSessionKey = "main"
    private var activeWorkJob: Job? = null
    private var activeSttCall: Call? = null
    private var activeLlmCall: Call? = null
    private var activeClawdiaStream: ClawdiaGatewayClient.CancelableStream? = null
    private var rssTickerJob: Job? = null
    private var homeClockJob: Job? = null
    private var rssTickerTitles: List<String> = emptyList()
    private var rssTickerOffset = 0
    private var isTerminalFullscreen = false
    @Volatile
    private var isSquirtMode = false
    @Volatile
    private var isAssistantStreamComplete = true
    private var squirtTargetWpm = SQUIRT_DEFAULT_WPM
    private var squirtPlaybackJob: Job? = null
    private var latestTranscript = ""
    private var squirtWordTextSizeSp = SQUIRT_BASE_TEXT_SIZE_SP
    private var squirtPlaybackIndex = 0
    private var squirtLastShownIndex = -1
    private var isScrubbingSquirt = false
    private lateinit var terminalTapDetector: GestureDetector
    private val defaultConstraints = ConstraintSet()
    private val fullscreenConstraints = ConstraintSet()
    private val wisprConstraints = ConstraintSet()
    private val wisprFullscreenConstraints = ConstraintSet()
    private var isWisprTextMode = false
    private var wisprDraftText = ""
    private var wisprComposeDialog: AlertDialog? = null
    private val assistantTextLock = Any()
    private val assistantTextBuffer = StringBuilder(1024)
    private val squirtWordsLock = Any()
    private val squirtWords = ArrayList<String>(256)
    private val squirtCarry = StringBuilder(128)
    private val tmpViewLocation = IntArray(2)
    private val streamDetailsLock = Any()
    private val streamDetailLines = ArrayDeque<String>()
    private val terminalStreamLock = Any()
    private val terminalStreamBlocks = ArrayDeque<TerminalStreamBlock>()
    private val homeClockFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

    private enum class TerminalStreamBlockType {
        ASSISTANT,
        DETAIL
    }

    private data class TerminalStreamBlock(
        val type: TerminalStreamBlockType,
        val text: StringBuilder
    )

    private data class SquirtPlaybackFrame(
        val index: Int,
        val word: String
    )

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
        setupWisprInputBar()
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
        setWisprTextMode(AppSettings.isWisprTextModeEnabled(this), animate = false)
        startRssTicker()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    override fun onResume() {
        super.onResume()
        setWisprTextMode(AppSettings.isWisprTextModeEnabled(this), animate = false)
    }

    override fun onStart() {
        super.onStart()
        startHomeClock()
    }

    override fun onStop() {
        homeClockJob?.cancel()
        homeClockJob = null
        super.onStop()
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
                if (index == 3) {
                    startActivity(Intent(this, ConfigActivity::class.java))
                } else {
                    startActivity(
                        Intent(this, PlaceholderActivity::class.java)
                            .putExtra(PlaceholderActivity.EXTRA_PLACEHOLDER_NUMBER, index + 1)
                    )
                }
            }
        }
    }

    private fun startHomeClock() {
        if (homeClockJob?.isActive == true) {
            return
        }
        updateHomeClockLabel()
        homeClockJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val delayToNextSecond = 1_000L - (System.currentTimeMillis() % 1_000L)
                delay(delayToNextSecond)
                if (!isActive) {
                    break
                }
                updateHomeClockLabel()
            }
        }
    }

    private fun updateHomeClockLabel() {
        binding.tile1Label.text = LocalTime.now()
            .format(homeClockFormatter)
            .lowercase(Locale.US)
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

    private fun setupWisprInputBar() {
        binding.wisprInput.showSoftInputOnFocus = false
        binding.wisprInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                suppressSystemKeyboard()
            }
        }
        binding.wisprInput.setOnClickListener {
            suppressSystemKeyboard()
        }
        binding.wisprSendButton.setOnClickListener {
            submitWisprPrompt()
        }
    }

    private fun setupSquirtControls() {
        binding.squirtSpeedDown.setOnClickListener {
            adjustSquirtSpeed(-SQUIRT_WPM_STEP)
        }
        binding.squirtSpeedUp.setOnClickListener {
            adjustSquirtSpeed(SQUIRT_WPM_STEP)
        }
        binding.squirtBack5.setOnClickListener {
            moveSquirtPlaybackBack(5)
        }
        binding.squirtBack15.setOnClickListener {
            moveSquirtPlaybackBack(15)
        }
        binding.squirtScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) {
                    return
                }
                isScrubbingSquirt = true
                val totalWords = getSquirtWordCount()
                val clamped = progress.coerceIn(0, totalWords)
                binding.squirtProgressLabel.text =
                    getString(R.string.squirt_progress_format, clamped, totalWords)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isScrubbingSquirt = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val totalWords = getSquirtWordCount()
                val targetIndex = seekBar.progress.coerceIn(0, totalWords)
                isScrubbingSquirt = false
                seekSquirtPlayback(targetIndex, renderPreview = true)
            }
        })
        applySquirtWordTextSize(SQUIRT_BASE_TEXT_SIZE_SP)
        updateSquirtSpeedLabel()
        updateSquirtProgressUi()
    }

    private fun setupTerminalFullscreenToggle() {
        defaultConstraints.clone(binding.root)
        fullscreenConstraints.clone(binding.root)
        wisprConstraints.clone(binding.root)
        wisprFullscreenConstraints.clone(binding.root)

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

        wisprConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )
        wisprConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.END, 0)
        wisprConstraints.clear(binding.bottomBanner.id, ConstraintSet.START)
        wisprConstraints.clear(binding.bottomBanner.id, ConstraintSet.END)
        wisprConstraints.connect(
            binding.bottomBanner.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )
        wisprConstraints.connect(
            binding.bottomBanner.id,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )
        wisprConstraints.setMargin(binding.bottomBanner.id, ConstraintSet.START, 0)
        wisprConstraints.setMargin(binding.bottomBanner.id, ConstraintSet.END, 0)

        wisprFullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )
        wisprFullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )
        wisprFullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )
        wisprFullscreenConstraints.connect(
            binding.diagnosticPanel.id,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )
        wisprFullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.START, 0)
        wisprFullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.END, 0)
        wisprFullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.TOP, 0)
        wisprFullscreenConstraints.setMargin(binding.diagnosticPanel.id, ConstraintSet.BOTTOM, 0)

        terminalTapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isPointInsideViewRaw(binding.diagnosticHeader, e.rawX, e.rawY)) {
                        toggleTerminalFullscreen()
                        return true
                    }
                    if (
                        isWisprTextMode &&
                        !isSquirtMode &&
                        shouldHandleTerminalSingleTap(e.rawX, e.rawY)
                    ) {
                        showWisprComposeDialog()
                        return true
                    }
                    return false
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    if (!shouldHandleTerminalSwipe(start, e2)) {
                        return false
                    }

                    val deltaX = e2.rawX - start.rawX
                    val deltaY = e2.rawY - start.rawY
                    val density = resources.displayMetrics.density
                    val minDistancePx = TERMINAL_SWIPE_MIN_DISTANCE_DP * density
                    val minVelocityPx = TERMINAL_SWIPE_MIN_VELOCITY_DP * density
                    if (
                        abs(deltaX) < minDistancePx ||
                        abs(velocityX) < minVelocityPx ||
                        abs(deltaX) <= abs(deltaY)
                    ) {
                        return false
                    }

                    return when {
                        deltaX < 0f && !isTerminalFullscreen -> {
                            setTerminalFullscreen(true)
                            true
                        }
                        deltaX > 0f && isTerminalFullscreen -> {
                            setTerminalFullscreen(false)
                            true
                        }
                        else -> false
                    }
                }
            }
        )
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

    private fun shouldHandleTerminalSwipe(startEvent: MotionEvent, endEvent: MotionEvent): Boolean {
        return shouldHandleTerminalSingleTap(startEvent.rawX, startEvent.rawY) &&
            shouldHandleTerminalSingleTap(endEvent.rawX, endEvent.rawY)
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

    private fun setTerminalFullscreen(fullscreen: Boolean, animate: Boolean = true, force: Boolean = false) {
        if (!force && isTerminalFullscreen == fullscreen) {
            return
        }
        isTerminalFullscreen = fullscreen
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.root,
                AutoTransition().apply {
                    duration = 320L
                    interpolator = DecelerateInterpolator(1.6f)
                }
            )
        }

        when {
            fullscreen && isWisprTextMode -> wisprFullscreenConstraints.applyTo(binding.root)
            fullscreen -> fullscreenConstraints.applyTo(binding.root)
            isWisprTextMode -> wisprConstraints.applyTo(binding.root)
            else -> defaultConstraints.applyTo(binding.root)
        }

        if (fullscreen) {
            binding.iconGrid.visibility = View.GONE
            binding.bottomBanner.visibility = View.GONE
            binding.wisprInputBar.visibility = View.GONE
            binding.rightStack.visibility = View.GONE
        } else {
            binding.iconGrid.visibility = View.VISIBLE
            binding.bottomBanner.visibility = View.VISIBLE
            binding.wisprInputBar.visibility = View.GONE
            binding.rightStack.visibility = if (isWisprTextMode) View.GONE else View.VISIBLE
        }
        scrollTerminalToBottom()
    }

    private fun setWisprTextMode(enabled: Boolean, animate: Boolean) {
        if (isWisprTextMode == enabled) {
            return
        }
        isWisprTextMode = enabled
        if (!enabled) {
            binding.wisprInput.clearFocus()
            wisprComposeDialog?.dismiss()
        } else {
            suppressSystemKeyboard()
        }
        setTerminalFullscreen(isTerminalFullscreen, animate = animate, force = true)
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
            rebuildSquirtWordsFromAssistantText()
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

    private fun moveSquirtPlaybackBack(wordCount: Int) {
        val targetIndex = synchronized(squirtWordsLock) {
            (squirtPlaybackIndex - wordCount).coerceAtLeast(0)
        }
        seekSquirtPlayback(targetIndex, renderPreview = true)
    }

    private fun seekSquirtPlayback(targetIndex: Int, renderPreview: Boolean) {
        val previewWord = synchronized(squirtWordsLock) {
            val totalWords = squirtWords.size
            val clamped = targetIndex.coerceIn(0, totalWords)
            squirtPlaybackIndex = clamped
            squirtLastShownIndex = clamped - 1
            if (renderPreview && clamped < totalWords) squirtWords[clamped] else null
        }

        if (isSquirtMode) {
            if (previewWord != null) {
                renderSquirtWord(previewWord)
            } else {
                renderSquirtPlaceholder()
            }
            ensureSquirtPlaybackLoop()
        }
        updateSquirtProgressUi()
    }

    private fun getSquirtWordCount(): Int {
        synchronized(squirtWordsLock) {
            return squirtWords.size
        }
    }

    private fun postSquirtProgressUiRefresh() {
        runOnUiThread {
            if (::binding.isInitialized) {
                updateSquirtProgressUi()
            }
        }
    }

    private fun updateSquirtProgressUi() {
        val (position, totalWords) = synchronized(squirtWordsLock) {
            val total = squirtWords.size
            squirtPlaybackIndex = squirtPlaybackIndex.coerceIn(0, total)
            squirtLastShownIndex = squirtLastShownIndex.coerceIn(-1, total - 1)
            (squirtLastShownIndex + 1).coerceIn(0, total) to total
        }
        binding.squirtScrubber.max = max(1, totalWords)
        if (!isScrubbingSquirt) {
            binding.squirtScrubber.progress = position
            binding.squirtProgressLabel.text =
                getString(R.string.squirt_progress_format, position, totalWords)
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

    private fun submitWisprPrompt() {
        if (!isWisprTextMode || isBusy) {
            return
        }
        val transcript = binding.wisprInput.text?.toString()?.trim().orEmpty()
        if (transcript.isBlank()) {
            return
        }
        binding.wisprInput.text?.clear()
        suppressSystemKeyboard()
        submitTextPrompt(transcript)
    }

    private fun showWisprComposeDialog() {
        if (!isWisprTextMode || isBusy || isFinishing || isDestroyed) {
            return
        }
        if (wisprComposeDialog?.isShowing == true) {
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_wispr_compose, null)
        val input = dialogView.findViewById<AppCompatEditText>(R.id.wispr_compose_input)
        val sendButton = dialogView.findViewById<MaterialButton>(R.id.wispr_compose_send)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.wispr_compose_cancel)

        input.setText(wisprDraftText)
        input.setSelection(input.text?.length ?: 0)

        var didSend = false
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        wisprComposeDialog = dialog

        sendButton.setOnClickListener {
            if (isBusy) {
                return@setOnClickListener
            }
            val transcript = input.text?.toString()?.trim().orEmpty()
            if (transcript.isBlank()) {
                return@setOnClickListener
            }
            didSend = true
            wisprDraftText = ""
            dialog.dismiss()
            submitTextPrompt(transcript)
        }
        cancelButton.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
            input.requestFocus()
            input.post {
                getInputManager()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnDismissListener {
            if (!didSend) {
                wisprDraftText = input.text?.toString().orEmpty()
            }
            getInputManager()?.hideSoftInputFromWindow(input.windowToken, 0)
            wisprComposeDialog = null
        }
        dialog.show()
    }

    private fun submitTextPrompt(transcript: String) {
        cancelActiveWork()
        resetAssistantStreamState()
        latestTranscript = transcript
        isBusy = true
        setMicButtonEnabled(false)
        setWisprInputEnabled(false)
        showGeneratingState(transcript)

        activeWorkJob = lifecycleScope.launch {
            var errorMessage: String? = null
            try {
                withContext(Dispatchers.IO) {
                    streamAssistantResponse(transcript)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Text input pipeline failed", error)
                errorMessage = toDisplayableErrorMessage(error)
            } finally {
                isAssistantStreamComplete = true
                flushSquirtCarryWord()
                activeLlmCall = null
                activeClawdiaStream = null
                finalizeActiveRequest(transcript, errorMessage)
            }
        }
    }

    private fun uploadRecording(file: File) {
        cancelActiveWork()
        resetAssistantStreamState()
        isBusy = true
        setMicButtonEnabled(false)
        setWisprInputEnabled(false)
        binding.micStatus.text = getString(R.string.mic_transcribing)
        binding.terminalOutput.text = getString(R.string.terminal_transcribing)

        activeWorkJob = lifecycleScope.launch {
            var transcript = ""
            var errorMessage: String? = null
            try {
                transcript = withContext(Dispatchers.IO) {
                    transcribeRecordingWithRetry(file)
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
                    showGeneratingState(transcript)
                }

                withContext(Dispatchers.IO) {
                    streamAssistantResponse(transcript)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Voice input pipeline failed", error)
                errorMessage = toDisplayableErrorMessage(error)
            } finally {
                isAssistantStreamComplete = true
                flushSquirtCarryWord()
                file.delete()
                recordingFile = null
                activeSttCall = null
                activeLlmCall = null
                activeClawdiaStream = null

                finalizeActiveRequest(transcript, errorMessage)
            }
        }
    }

    private fun showGeneratingState(transcript: String) {
        binding.micStatus.text = getString(R.string.mic_generating)
        renderTranscriptAndAssistant(transcript, "")
        renderSquirtPlaceholder()
        ensureSquirtPlaybackLoop()
        scrollTerminalToBottom()
    }

    private suspend fun streamAssistantResponse(transcript: String) {
        when (AppSettings.getBackendMode(this)) {
            AppSettings.BackendMode.MISHELL -> streamMishellAssistantResponse(transcript)
            AppSettings.BackendMode.CLAWDIA -> streamClawdiaAssistantResponse(transcript)
        }
    }

    private suspend fun streamMishellAssistantResponse(transcript: String) {
        llmStreamClient.streamText(
            url = BuildConfig.LLM_STREAM_URL,
            apiKey = BuildConfig.STT_API_KEY,
            text = transcript,
            sessionId = sessionId,
            onCallLifecycle = { call -> activeLlmCall = call },
            onEvent = { event ->
                when (event) {
                    is SseEvent.Delta -> handleAssistantDelta(transcript, event.text)
                    SseEvent.Done -> handleStreamDone()
                    is SseEvent.Error -> throw IOException(event.message)
                }
            }
        )
    }

    private suspend fun streamClawdiaAssistantResponse(transcript: String) {
        val config = AppSettings.getClawdiaConnectionConfig(this)
            ?: throw IOException("Clawdia config is incomplete. Open Config and save/test setup.")
        if (config.token.isBlank() && config.password.isBlank()) {
            throw IOException("Clawdia auth missing. Provide token or password in Config.")
        }

        clawdiaGatewayClient.streamText(
            config = config,
            text = transcript,
            sessionKey = clawdiaSessionKey,
            thinkingLevel = "high",
            onCallLifecycle = { handle -> activeClawdiaStream = handle },
            onEvent = { event ->
                Log.d(LOG_TAG, "Clawdia event=$event")
                when (event) {
                    is ClawdiaGatewayClient.StreamEvent.Status -> {
                        appendStreamDetailAndRender(transcript, "🔌 ${event.message}")
                    }
                    is ClawdiaGatewayClient.StreamEvent.AssistantDelta -> {
                        handleAssistantDelta(transcript, event.text)
                    }
                    is ClawdiaGatewayClient.StreamEvent.Tool -> {
                        val icon = when (event.phase.lowercase()) {
                            "start" -> "🛠"
                            "update" -> "🧩"
                            "result" -> if (event.isError == true) "❌" else "✅"
                            else -> "🔧"
                        }
                        val callId = event.toolCallId?.let { " #$it" }.orEmpty()
                        val summary = event.summary?.takeIf { it.isNotBlank() }?.let { "\n│ $it" }.orEmpty()
                        appendStreamDetailAndRender(
                            transcript,
                            "$icon TOOL/${event.phase.uppercase()} ${event.name}$callId$summary"
                        )
                    }
                    is ClawdiaGatewayClient.StreamEvent.Reasoning -> {
                        val chunk = event.delta.ifBlank { event.text }.trim()
                        if (chunk.isNotBlank()) {
                            appendStreamDetailAndRender(
                                transcript,
                                "🧠 REASONING ${chunk.replace('\n', ' ')}"
                            )
                        }
                    }
                    is ClawdiaGatewayClient.StreamEvent.Lifecycle -> {
                        val detail = event.detail?.takeIf { it.isNotBlank() }?.let { " :: $it" }.orEmpty()
                        appendStreamDetailAndRender(
                            transcript,
                            "📡 LIFECYCLE/${event.phase.uppercase()}$detail"
                        )
                    }
                    ClawdiaGatewayClient.StreamEvent.Done -> handleStreamDone()
                }
            }
        )
    }

    private fun handleAssistantDelta(transcript: String, delta: String) {
        if (delta.isBlank()) {
            return
        }
        appendTerminalAssistantDelta(delta)
        if (isSquirtMode) {
            appendAssistantChunk(delta)
            appendSquirtWords(delta)
            if (squirtPlaybackJob?.isActive != true) {
                runOnUiThread {
                    ensureSquirtPlaybackLoop()
                }
            }
        } else {
            val fullAssistantText = appendAssistantChunkAndSnapshot(delta)
            runOnUiThread {
                renderTranscriptAndAssistant(transcript, fullAssistantText)
            }
        }
    }

    private fun handleStreamDone() {
        isAssistantStreamComplete = true
        flushSquirtCarryWord()
        if (isSquirtMode) {
            runOnUiThread {
                ensureSquirtPlaybackLoop()
            }
        }
    }

    private suspend fun finalizeActiveRequest(transcript: String, errorMessage: String?) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            isBusy = false
            setMicButtonEnabled(true)
            setWisprInputEnabled(true)
            binding.micStatus.text = getString(R.string.mic_idle)

            if (errorMessage != null) {
                showTerminalError(errorMessage)
            } else if (transcript.isNotBlank() && getAssistantTextSnapshot().isBlank()) {
                renderTranscriptAndAssistant(transcript, "")
            } else {
                ensureSquirtPlaybackLoop()
            }
        }
        activeWorkJob = null
    }

    private fun transcribeRecording(file: File): String {
        if (BuildConfig.STT_URL.isBlank()) {
            throw IOException("STT URL is not configured.")
        }

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
            .url(BuildConfig.STT_URL)
            .addHeader("x-api-key", BuildConfig.STT_API_KEY)
            .post(requestBody)
            .build()

        val call = httpClient.newCall(request)
        activeSttCall = call
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(LOG_TAG, "STT request failed. code=${response.code}, body=${body.take(800)}")
                throw IOException("HTTP ${response.code}: ${body.take(300)}")
            }
            return extractTranscript(body)
        }
    }

    private fun transcribeRecordingWithRetry(file: File): String {
        var lastError: IOException? = null
        repeat(STT_MAX_ATTEMPTS) { attemptIndex ->
            try {
                return transcribeRecording(file)
            } catch (error: IOException) {
                lastError = error
                val attemptNumber = attemptIndex + 1
                val shouldRetry = shouldRetrySttRequest(error) && attemptNumber < STT_MAX_ATTEMPTS
                if (!shouldRetry) {
                    throw error
                }
                val backoffMs = 350L * attemptNumber
                Log.w(
                    LOG_TAG,
                    "STT attempt $attemptNumber failed, retrying in ${backoffMs}ms: ${error.message}"
                )
                Thread.sleep(backoffMs)
            }
        }
        throw lastError ?: IOException("STT request failed")
    }

    private fun shouldRetrySttRequest(error: IOException): Boolean {
        val message = (error.message ?: "").lowercase()
        val statusCode = parseHttpStatusCode(error.message)
        return statusCode in setOf(408, 425, 429, 500, 502, 503, 504, 530) ||
            message.contains("error code: 1033") ||
            message.contains("timeout") ||
            message.contains("connection reset") ||
            message.contains("unexpected end of stream")
    }

    private fun parseHttpStatusCode(message: String?): Int? {
        val value = Regex("""\bHTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
            .find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
        return value?.toIntOrNull()
    }

    private fun toDisplayableErrorMessage(error: Exception): String {
        val raw = error.message?.trim().orEmpty()
        val rawLower = raw.lowercase()
        val statusCode = parseHttpStatusCode(raw)
        return when {
            statusCode == 530 || rawLower.contains("error code: 1033") ->
                getString(R.string.err_backend_unavailable)
            statusCode == 401 || statusCode == 403 ->
                getString(R.string.err_backend_auth)
            statusCode != null && statusCode >= 500 ->
                getString(R.string.err_backend_http_5xx, statusCode)
            raw.isNotBlank() -> raw
            else -> getString(R.string.err_request_failed)
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

    private fun setWisprInputEnabled(enabled: Boolean) {
        binding.wisprInput.isEnabled = enabled
        binding.wisprSendButton.isEnabled = enabled
    }

    private fun setMicButtonSelected(selected: Boolean) {
        binding.micButton.isSelected = selected
    }

    private fun suppressSystemKeyboard() {
        getInputManager()?.hideSoftInputFromWindow(binding.wisprInput.windowToken, 0)
    }

    private fun getInputManager(): InputMethodManager? {
        return getSystemService(InputMethodManager::class.java)
    }

    private fun resetAssistantStreamState() {
        synchronized(assistantTextLock) {
            assistantTextBuffer.setLength(0)
        }
        synchronized(streamDetailsLock) {
            streamDetailLines.clear()
        }
        synchronized(terminalStreamLock) {
            terminalStreamBlocks.clear()
        }
        synchronized(squirtWordsLock) {
            squirtWords.clear()
            squirtCarry.setLength(0)
            squirtPlaybackIndex = 0
            squirtLastShownIndex = -1
        }
        isAssistantStreamComplete = false
        squirtPlaybackJob?.cancel()
        squirtPlaybackJob = null
        postSquirtProgressUiRefresh()
        runOnUiThread {
            if (::binding.isInitialized) {
                renderSquirtPlaceholder()
            }
        }
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

    private fun rebuildSquirtWordsFromAssistantText() {
        synchronized(squirtWordsLock) {
            squirtWords.clear()
            squirtCarry.setLength(0)
            squirtPlaybackIndex = 0
            squirtLastShownIndex = -1
        }
        appendSquirtWords(getAssistantTextSnapshot())
        if (isAssistantStreamComplete) {
            flushSquirtCarryWord()
        }
        renderSquirtPlaceholder()
        updateSquirtProgressUi()
    }

    private fun appendSquirtWords(textChunk: String) {
        if (textChunk.isEmpty()) {
            return
        }
        synchronized(squirtWordsLock) {
            squirtCarry.append(textChunk)
            var tokenStart = 0
            var cursor = 0
            while (cursor < squirtCarry.length) {
                if (squirtCarry[cursor].isWhitespace()) {
                    if (tokenStart < cursor) {
                        squirtWords.add(squirtCarry.substring(tokenStart, cursor))
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
        postSquirtProgressUiRefresh()
    }

    private fun flushSquirtCarryWord() {
        var didAddWord = false
        synchronized(squirtWordsLock) {
            if (squirtCarry.isNotEmpty()) {
                squirtWords.add(squirtCarry.toString())
                squirtCarry.setLength(0)
                didAddWord = true
            }
        }
        if (didAddWord) {
            postSquirtProgressUiRefresh()
        }
    }

    private fun ensureSquirtPlaybackLoop() {
        if (!isSquirtMode || squirtPlaybackJob?.isActive == true) {
            return
        }
        squirtPlaybackJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            while (isActive && isSquirtMode) {
                val frame = pollNextSquirtWord()
                if (frame == null) {
                    if (isAssistantStreamComplete) {
                        break
                    }
                    delay(8L)
                    continue
                }
                renderSquirtWord(frame.word)
                updateSquirtProgressUi()
                delay(computeSquirtDelayMs(frame.word, frame.index + 1))
            }
            updateSquirtProgressUi()
            squirtPlaybackJob = null
        }
    }

    private fun pollNextSquirtWord(): SquirtPlaybackFrame? {
        synchronized(squirtWordsLock) {
            if (squirtPlaybackIndex >= squirtWords.size) {
                return null
            }
            val index = squirtPlaybackIndex
            squirtPlaybackIndex += 1
            squirtLastShownIndex = index
            return SquirtPlaybackFrame(index, squirtWords[index])
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
        val details = buildStreamDetailsSnapshot()
        binding.terminalOutput.text = buildString {
            append(getString(R.string.terminal_error, message))
            if (details.isNotBlank()) {
                append("\n\n──────── STREAM DETAILS ────────\n")
                append(details)
            }
        }
        scrollTerminalToBottom()
    }

    private fun renderTranscriptAndAssistant(transcript: String, assistantText: String) {
        val streamTimeline = buildTerminalStreamSnapshot(activeAssistantLabel())
        binding.terminalOutput.text = buildString {
            append("USR://")
            append(transcript)
            if (streamTimeline.isNotBlank()) {
                append("\n\n")
                append(streamTimeline)
            } else {
                append("\n\n")
                append(activeAssistantLabel())
                append("://")
                append(assistantText)
            }
        }
        scrollTerminalToBottom()
    }

    private fun activeAssistantLabel(): String {
        return when (AppSettings.getBackendMode(this)) {
            AppSettings.BackendMode.MISHELL -> "MISHELL"
            AppSettings.BackendMode.CLAWDIA -> "CLAWDIA"
        }
    }

    private fun appendStreamDetailAndRender(transcript: String, line: String) {
        appendStreamDetail(line)
        if (!isSquirtMode) {
            val assistantText = getAssistantTextSnapshot()
            runOnUiThread {
                renderTranscriptAndAssistant(transcript, assistantText)
            }
        }
    }

    private fun appendStreamDetail(line: String) {
        val cleaned = line.trim()
        if (cleaned.isBlank()) {
            return
        }
        appendTerminalDetail(cleaned)
        synchronized(streamDetailsLock) {
            streamDetailLines.addLast(cleaned)
            while (streamDetailLines.size > MAX_STREAM_DETAIL_LINES) {
                streamDetailLines.removeFirst()
            }
        }
    }

    private fun appendTerminalAssistantDelta(delta: String) {
        synchronized(terminalStreamLock) {
            val lastBlock = terminalStreamBlocks.peekLast()
            if (lastBlock?.type == TerminalStreamBlockType.ASSISTANT) {
                lastBlock.text.append(delta)
            } else {
                terminalStreamBlocks.addLast(
                    TerminalStreamBlock(TerminalStreamBlockType.ASSISTANT, StringBuilder(delta))
                )
            }
            trimTerminalStreamBlocksLocked()
        }
    }

    private fun appendTerminalDetail(detail: String) {
        synchronized(terminalStreamLock) {
            val lastBlock = terminalStreamBlocks.peekLast()
            if (lastBlock?.type == TerminalStreamBlockType.DETAIL) {
                lastBlock.text.append('\n').append(detail)
            } else {
                terminalStreamBlocks.addLast(
                    TerminalStreamBlock(TerminalStreamBlockType.DETAIL, StringBuilder(detail))
                )
            }
            trimTerminalStreamBlocksLocked()
        }
    }

    private fun trimTerminalStreamBlocksLocked() {
        while (terminalStreamBlocks.size > MAX_TERMINAL_STREAM_BLOCKS) {
            terminalStreamBlocks.removeFirst()
        }
    }

    private fun buildTerminalStreamSnapshot(assistantLabel: String): String {
        synchronized(terminalStreamLock) {
            if (terminalStreamBlocks.isEmpty()) {
                return ""
            }

            val output = StringBuilder()
            var detailsHeaderPrinted = false
            terminalStreamBlocks.forEach { block ->
                if (output.isNotEmpty()) {
                    output.append("\n\n")
                }
                when (block.type) {
                    TerminalStreamBlockType.ASSISTANT -> {
                        output.append(assistantLabel)
                        output.append("://")
                        output.append(block.text)
                    }

                    TerminalStreamBlockType.DETAIL -> {
                        if (!detailsHeaderPrinted) {
                            output.append("──────── STREAM DETAILS ────────\n")
                            detailsHeaderPrinted = true
                        }
                        output.append(block.text)
                    }
                }
            }
            return output.toString()
        }
    }

    private fun buildStreamDetailsSnapshot(): String {
        synchronized(streamDetailsLock) {
            return streamDetailLines.joinToString(separator = "\n")
        }
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
        activeClawdiaStream?.cancel()
        activeWorkJob = null
        activeSttCall = null
        activeLlmCall = null
        activeClawdiaStream = null
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startRssTicker() {
        if (rssTickerJob?.isActive == true) {
            return
        }
        rssTickerJob = lifecycleScope.launch {
            while (isActive) {
                val titles = runCatching {
                    RssRepository.fetchArticles(this@MainActivity, limit = 120)
                        .mapNotNull { it.title.takeIf(String::isNotBlank) }
                        .distinct()
                }.getOrNull().orEmpty()

                if (titles.isNotEmpty()) {
                    rssTickerTitles = titles
                    if (rssTickerOffset >= rssTickerTitles.size) {
                        rssTickerOffset = 0
                    }
                    renderRssTickerWindow()
                } else if (rssTickerTitles.isEmpty()) {
                    binding.bottomBanner.text = getString(R.string.banner_text)
                }

                repeat(8) {
                    delay(8_000L)
                    if (!isActive) return@launch
                    if (rssTickerTitles.isNotEmpty()) {
                        rssTickerOffset = (rssTickerOffset + 1) % rssTickerTitles.size
                        renderRssTickerWindow()
                    }
                }
            }
        }
    }

    private fun renderRssTickerWindow() {
        if (rssTickerTitles.isEmpty()) {
            return
        }
        val itemCount = min(14, rssTickerTitles.size)
        val windowItems = buildList(itemCount) {
            for (index in 0 until itemCount) {
                val position = (rssTickerOffset + index) % rssTickerTitles.size
                add(rssTickerTitles[position])
            }
        }
        binding.bottomBanner.text = buildString {
            append(getString(R.string.banner_rss_prefix))
            append(windowItems.joinToString(separator = " // "))
            append(" //")
        }
        binding.bottomBanner.isSelected = true
    }

    override fun onDestroy() {
        wisprComposeDialog?.dismiss()
        wisprComposeDialog = null
        homeClockJob?.cancel()
        homeClockJob = null
        rssTickerJob?.cancel()
        rssTickerJob = null
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
