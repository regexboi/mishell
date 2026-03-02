package ai.mishell.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ai.mishell.app.databinding.ActivityArticleReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

class ArticleReaderActivity : AppCompatActivity() {
    private var isSquirtMode = false
    private var squirtTargetWpm = SQUIRT_DEFAULT_WPM
    private var squirtWordTextSizeSp = SQUIRT_BASE_TEXT_SIZE_SP
    private var squirtPlaybackJob: Job? = null
    private var articleBodyText = ""
    private val squirtQueueLock = Any()
    private val squirtQueue = ArrayDeque<String>()
    private val squirtCarry = StringBuilder(256)
    private val tmpViewLocation = IntArray(2)
    private lateinit var binding: ActivityArticleReaderBinding
    private var externalLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        binding.backButton.setOnClickListener { finish() }
        binding.openLinkButton.setOnClickListener { openExternalLink() }
        binding.squirtSpeedDown.setOnClickListener { adjustSquirtSpeed(-SQUIRT_WPM_STEP) }
        binding.squirtSpeedUp.setOnClickListener { adjustSquirtSpeed(SQUIRT_WPM_STEP) }
        applySquirtWordTextSize(SQUIRT_BASE_TEXT_SIZE_SP)
        updateSquirtSpeedLabel()
        renderSquirtPlaceholder()

        val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID)?.trim().orEmpty()
        if (articleId.isEmpty()) {
            binding.articleContent.text = getString(R.string.article_status_missing_id)
            return
        }

        loadArticle(articleId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleSquirtTwoFingerToggle(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        squirtPlaybackJob?.cancel()
        squirtPlaybackJob = null
        super.onDestroy()
    }

    private fun loadArticle(articleId: String) {
        binding.articleContent.text = getString(R.string.article_status_loading)
        binding.openLinkButton.visibility = View.GONE

        lifecycleScope.launch {
            runCatching {
                RssRepository.fetchArticleById(this@ArticleReaderActivity, articleId)
            }.onSuccess { article ->
                if (article == null) {
                    binding.articleContent.text = getString(R.string.article_status_not_found)
                    return@onSuccess
                }

                binding.articleTitle.text = article.title
                binding.articleMeta.text = formatMeta(article.sourceName, article.publishedAt)

                val body = article.content?.takeIf { it.isNotBlank() }
                    ?: article.excerpt?.takeIf { it.isNotBlank() }
                val resolvedBody = body ?: getString(R.string.article_status_no_content)
                articleBodyText = resolvedBody
                binding.articleContent.text = resolvedBody

                externalLink = article.link?.takeIf { it.isNotBlank() }
                binding.openLinkButton.visibility =
                    if (externalLink != null) View.VISIBLE else View.GONE

                if (isSquirtMode) {
                    rebuildSquirtQueueFromArticle()
                    ensureSquirtPlaybackLoop()
                }
            }.onFailure { error ->
                Log.e(LOG_TAG, "Failed to load article", error)
                binding.articleContent.text =
                    getString(R.string.article_status_error, error.message ?: "unknown error")
            }
        }
    }

    private fun openExternalLink() {
        val link = externalLink ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }.onFailure { error ->
            Log.e(LOG_TAG, "Failed to open external link", error)
        }
    }

    private fun handleSquirtTwoFingerToggle(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount < 2) {
            return false
        }

        val rawOffsetX = event.rawX - event.x
        val rawOffsetY = event.rawY - event.y
        val targetView = if (isSquirtMode) binding.squirtWordRow else binding.contentScroll

        val firstInside = isPointInsideViewRaw(
            targetView,
            event.getX(0) + rawOffsetX,
            event.getY(0) + rawOffsetY
        )
        val secondInside = isPointInsideViewRaw(
            targetView,
            event.getX(1) + rawOffsetX,
            event.getY(1) + rawOffsetY
        )
        if (!firstInside || !secondInside) {
            return false
        }

        setSquirtMode(!isSquirtMode)
        return true
    }

    private fun setSquirtMode(enabled: Boolean) {
        if (isSquirtMode == enabled) {
            return
        }
        isSquirtMode = enabled
        binding.contentScroll.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.squirtContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            rebuildSquirtQueueFromArticle()
            ensureSquirtPlaybackLoop()
        } else {
            squirtPlaybackJob?.cancel()
            squirtPlaybackJob = null
        }
    }

    private fun adjustSquirtSpeed(delta: Int) {
        squirtTargetWpm = (squirtTargetWpm + delta).coerceIn(SQUIRT_MIN_WPM, SQUIRT_MAX_WPM)
        updateSquirtSpeedLabel()
    }

    private fun updateSquirtSpeedLabel() {
        binding.squirtSpeedLabel.text = getString(R.string.squirt_speed_label, squirtTargetWpm)
    }

    private fun rebuildSquirtQueueFromArticle() {
        synchronized(squirtQueueLock) {
            squirtQueue.clear()
            squirtCarry.setLength(0)
        }
        enqueueSquirtWords(articleBodyText)
        flushSquirtCarryWord()
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
                    break
                }
                shownWords += 1
                renderSquirtWord(nextWord)
                delay(computeSquirtDelayMs(nextWord, shownWords))
            }
            if (isSquirtMode) {
                renderSquirtPlaceholder()
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

    private fun formatMeta(sourceName: String?, publishedAt: java.time.Instant?): String {
        val source = sourceName?.takeIf { it.isNotBlank() } ?: getString(R.string.article_unknown_source)
        val time = publishedAt?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(it)
        } ?: getString(R.string.article_unknown_date)
        return getString(R.string.article_meta_format, source, time)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val LOG_TAG = "ArticleReaderActivity"
        const val EXTRA_ARTICLE_ID = "extra_article_id"
        private const val SQUIRT_MIN_WPM = 180
        private const val SQUIRT_MAX_WPM = 1100
        private const val SQUIRT_WPM_STEP = 20
        private const val SQUIRT_DEFAULT_WPM = 420
        private const val SQUIRT_RAMP_WORD_COUNT = 18
        private const val SQUIRT_RAMP_FLOOR_MULTIPLIER = 0.45f
        private const val SQUIRT_BASE_TEXT_SIZE_SP = 40f
    }
}
