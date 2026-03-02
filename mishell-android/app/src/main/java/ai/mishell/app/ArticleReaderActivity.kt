package ai.mishell.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import ai.mishell.app.databinding.ActivityArticleReaderBinding
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class ArticleReaderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityArticleReaderBinding
    private var externalLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        binding.backButton.setOnClickListener { finish() }
        binding.openLinkButton.setOnClickListener { openExternalLink() }

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
                binding.articleContent.text = body ?: getString(R.string.article_status_no_content)

                externalLink = article.link?.takeIf { it.isNotBlank() }
                binding.openLinkButton.visibility =
                    if (externalLink != null) View.VISIBLE else View.GONE
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
    }
}
