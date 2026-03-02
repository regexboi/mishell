package ai.mishell.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ai.mishell.app.databinding.ActivityPlaceholderBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class PlaceholderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaceholderBinding
    private var autoScrollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceholderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()
        binding.backButton.setOnClickListener { finish() }

        val number = intent.getIntExtra(EXTRA_PLACEHOLDER_NUMBER, 1)
        when (number) {
            1 -> setupSummariesScreen()
            2 -> setupArticleListScreen()
            else -> setupFallbackScreen(number)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    override fun onDestroy() {
        autoScrollJob?.cancel()
        super.onDestroy()
    }

    private fun setupSummariesScreen() {
        binding.titleText.text = getString(R.string.summaries_title)
        binding.subtitleText.visibility = View.VISIBLE
        binding.subtitleText.text = getString(R.string.summaries_subtitle)
        binding.placeholderText.visibility = View.GONE
        binding.contentList.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.summaries_status_loading)

        lifecycleScope.launch {
            runCatching {
                RssRepository.fetchSummaries(this@PlaceholderActivity)
            }.onSuccess { summaries ->
                binding.loadingIndicator.visibility = View.GONE
                if (summaries.isEmpty()) {
                    binding.statusText.text = getString(R.string.summaries_status_empty)
                    binding.contentList.visibility = View.GONE
                    binding.placeholderText.visibility = View.VISIBLE
                    binding.placeholderText.text = getString(R.string.summaries_status_empty)
                    return@onSuccess
                }

                binding.statusText.text =
                    getString(R.string.summaries_status_loaded, summaries.size)
                val adapter = SummaryAdapter(this@PlaceholderActivity, summaries)
                binding.contentList.adapter = adapter
                startAutoScroll()
            }.onFailure { error ->
                binding.loadingIndicator.visibility = View.GONE
                binding.statusText.text =
                    getString(R.string.summaries_status_error, error.message ?: "unknown error")
                binding.contentList.visibility = View.GONE
                binding.placeholderText.visibility = View.VISIBLE
                binding.placeholderText.text = getString(
                    R.string.summaries_status_error,
                    error.message ?: "unknown error"
                )
            }
        }
    }

    private fun setupArticleListScreen() {
        binding.titleText.text = getString(R.string.articles_title)
        binding.subtitleText.text = ""
        binding.subtitleText.visibility = View.GONE
        binding.placeholderText.visibility = View.GONE
        binding.contentList.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.articles_status_loading)

        lifecycleScope.launch {
            runCatching {
                RssRepository.fetchArticles(this@PlaceholderActivity)
            }.onSuccess { articles ->
                binding.loadingIndicator.visibility = View.GONE
                if (articles.isEmpty()) {
                    binding.statusText.text = getString(R.string.articles_status_empty)
                    binding.contentList.visibility = View.GONE
                    binding.placeholderText.visibility = View.VISIBLE
                    binding.placeholderText.text = getString(R.string.articles_status_empty)
                    return@onSuccess
                }

                binding.statusText.visibility = View.GONE
                binding.contentList.adapter = ArticleAdapter(this@PlaceholderActivity, articles)
                binding.contentList.setOnItemClickListener { _, _, position, _ ->
                    val article = articles.getOrNull(position) ?: return@setOnItemClickListener
                    startActivity(
                        Intent(this@PlaceholderActivity, ArticleReaderActivity::class.java)
                            .putExtra(ArticleReaderActivity.EXTRA_ARTICLE_ID, article.id)
                    )
                }
            }.onFailure { error ->
                binding.loadingIndicator.visibility = View.GONE
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text =
                    getString(R.string.articles_status_error, error.message ?: "unknown error")
                binding.contentList.visibility = View.GONE
                binding.placeholderText.visibility = View.VISIBLE
                binding.placeholderText.text = getString(
                    R.string.articles_status_error,
                    error.message ?: "unknown error"
                )
            }
        }
    }

    private fun setupFallbackScreen(number: Int) {
        binding.titleText.text = getString(R.string.placeholder_label, number)
        binding.subtitleText.text = ""
        binding.subtitleText.visibility = View.GONE
        binding.loadingIndicator.visibility = View.GONE
        binding.statusText.visibility = View.GONE
        binding.statusText.text = ""
        binding.contentList.visibility = View.GONE
        binding.placeholderText.visibility = View.VISIBLE
        binding.placeholderText.text = getString(R.string.placeholder_label, number)
    }

    private fun startAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1200)
                if (binding.contentList.adapter == null || binding.contentList.count == 0) {
                    continue
                }
                val lastVisible = binding.contentList.lastVisiblePosition
                if (lastVisible >= binding.contentList.count - 1) {
                    binding.contentList.smoothScrollToPosition(0)
                    delay(400)
                } else {
                    binding.contentList.smoothScrollBy(96, 900)
                }
            }
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private class SummaryAdapter(
        activity: PlaceholderActivity,
        items: List<RssRepository.SummaryItem>
    ) : ArrayAdapter<RssRepository.SummaryItem>(activity, 0, items) {
        private val inflater = LayoutInflater.from(activity)
        private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_summary, parent, false)
            val item = getItem(position)
            val summaryText = view.findViewById<TextView>(R.id.summary_text)
            val summaryMeta = view.findViewById<TextView>(R.id.summary_meta)
            summaryText.text = item?.summaryText.orEmpty()

            val source = item?.sourceName?.takeIf { it.isNotBlank() } ?: "Unknown source"
            val published = item?.publishedAt?.let { formatter.format(it) } ?: "Unknown date"
            val title = item?.articleTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
            summaryMeta.text = "$source • $published\n$title"
            return view
        }
    }

    private class ArticleAdapter(
        activity: PlaceholderActivity,
        items: List<RssRepository.ArticleListItem>
    ) : ArrayAdapter<RssRepository.ArticleListItem>(activity, 0, items) {
        private val inflater = LayoutInflater.from(activity)
        private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_article, parent, false)
            val item = getItem(position)

            val titleText = view.findViewById<TextView>(R.id.article_title)
            val metaText = view.findViewById<TextView>(R.id.article_meta)
            val excerptText = view.findViewById<TextView>(R.id.article_excerpt)

            titleText.text = item?.title.orEmpty()
            val source = item?.sourceName?.takeIf { it.isNotBlank() } ?: "Unknown source"
            val published = item?.publishedAt?.let { formatter.format(it) } ?: "Unknown date"
            metaText.text = "$source • $published"

            val excerpt = item?.excerpt?.takeIf { it.isNotBlank() }
            excerptText.text = excerpt ?: context.getString(R.string.articles_no_excerpt)
            return view
        }
    }
    companion object {
        const val EXTRA_PLACEHOLDER_NUMBER = "extra_placeholder_number"
    }
}
