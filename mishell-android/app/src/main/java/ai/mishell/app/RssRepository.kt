package ai.mishell.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

object RssRepository {
    private data class DbConnectionSpec(
        val jdbcUrl: String,
        val user: String? = null,
        val password: String? = null
    )

    data class SummaryItem(
        val articleId: String?,
        val sequence: Int,
        val summaryText: String,
        val articleTitle: String?,
        val sourceName: String?,
        val publishedAt: Instant?
    )

    data class ArticleListItem(
        val id: String,
        val title: String,
        val sourceName: String?,
        val publishedAt: Instant?,
        val excerpt: String?,
        val link: String?
    )

    data class ArticleDetail(
        val id: String,
        val title: String,
        val sourceName: String?,
        val publishedAt: Instant?,
        val content: String?,
        val excerpt: String?,
        val link: String?
    )

    data class MeetingListItem(
        val eventId: String,
        val title: String,
        val organizerName: String?,
        val organizerEmail: String?,
        val attendeeNames: List<String>,
        val startsAtUtc: Instant?,
        val endsAtUtc: Instant?,
        val room: String?,
        val locationDisplayName: String?,
        val descriptionPreview: String?,
        val webLink: String?
    )

    suspend fun fetchSummaries(context: Context, limit: Int = 200): List<SummaryItem> =
        withContext(Dispatchers.IO) {
            queryDb(context) { connection ->
                connection.prepareStatement(
                    """
                    select
                        s.article_id,
                        s.sequence,
                        s.summary_text,
                        a.title,
                        a.source_name,
                        a.published_at
                    from public.article_summaries s
                    left join public.articles a on a.id = s.article_id
                    where s.summary_text is not null and length(trim(s.summary_text)) > 0
                    order by coalesce(a.published_at, s.created_at) desc, s.sequence asc
                    limit ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    SummaryItem(
                                        articleId = rs.getStringOrNull("article_id"),
                                        sequence = rs.getInt("sequence"),
                                        summaryText = rs.getStringOrNull("summary_text").orEmpty(),
                                        articleTitle = rs.getStringOrNull("title"),
                                        sourceName = rs.getStringOrNull("source_name"),
                                        publishedAt = rs.getInstantOrNull("published_at")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    suspend fun fetchArticles(context: Context, limit: Int = 250): List<ArticleListItem> =
        withContext(Dispatchers.IO) {
            queryDb(context) { connection ->
                connection.prepareStatement(
                    """
                    select
                        id,
                        title,
                        source_name,
                        published_at,
                        excerpt,
                        link
                    from public.articles
                    order by coalesce(published_at, created_at) desc
                    limit ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val id = rs.getStringOrNull("id") ?: continue
                                val title = rs.getStringOrNull("title").orEmpty().ifBlank { "(untitled)" }
                                add(
                                    ArticleListItem(
                                        id = id,
                                        title = title,
                                        sourceName = rs.getStringOrNull("source_name"),
                                        publishedAt = rs.getInstantOrNull("published_at"),
                                        excerpt = rs.getStringOrNull("excerpt"),
                                        link = rs.getStringOrNull("link")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    suspend fun fetchArticleById(context: Context, articleId: String): ArticleDetail? =
        withContext(Dispatchers.IO) {
            queryDb(context) { connection ->
                connection.prepareStatement(
                    """
                    select
                        id,
                        title,
                        source_name,
                        published_at,
                        content,
                        excerpt,
                        link
                    from public.articles
                    where id = ?
                    limit 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, articleId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        ArticleDetail(
                            id = rs.getStringOrNull("id") ?: return@use null,
                            title = rs.getStringOrNull("title").orEmpty().ifBlank { "(untitled)" },
                            sourceName = rs.getStringOrNull("source_name"),
                            publishedAt = rs.getInstantOrNull("published_at"),
                            content = rs.getStringOrNull("content"),
                            excerpt = rs.getStringOrNull("excerpt"),
                            link = rs.getStringOrNull("link")
                        )
                    }
                }
            }
        }

    suspend fun fetchUpcomingMeetings(context: Context): List<MeetingListItem> =
        withContext(Dispatchers.IO) {
            queryDb(context) { connection ->
                connection.prepareStatement(
                    """
                    select
                        event_id,
                        title,
                        sender_name,
                        sender_email,
                        attendee_names,
                        starts_at_utc,
                        ends_at_utc,
                        room,
                        location ->> 'displayName' as location_display_name,
                        description_preview,
                        web_link
                    from public.o365_calendar_events
                    where starts_at_utc >= now()
                      and coalesce(is_cancelled, false) = false
                    order by starts_at_utc asc
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val eventId = rs.getStringOrNull("event_id") ?: continue
                                val title = rs.getStringOrNull("title")
                                    .orEmpty()
                                    .ifBlank { "(untitled meeting)" }
                                add(
                                    MeetingListItem(
                                        eventId = eventId,
                                        title = title,
                                        organizerName = rs.getStringOrNull("sender_name"),
                                        organizerEmail = rs.getStringOrNull("sender_email"),
                                        attendeeNames = rs.getStringListOrEmpty("attendee_names"),
                                        startsAtUtc = rs.getInstantOrNull("starts_at_utc"),
                                        endsAtUtc = rs.getInstantOrNull("ends_at_utc"),
                                        room = rs.getStringOrNull("room"),
                                        locationDisplayName = rs.getStringOrNull("location_display_name"),
                                        descriptionPreview = rs.getStringOrNull("description_preview"),
                                        webLink = rs.getStringOrNull("web_link")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun <T> queryDb(context: Context, block: (java.sql.Connection) -> T): T {
        val raw = AppSettings.getNeonConnectionString(context).trim()
        check(raw.isNotEmpty()) { "NEON_STRING is missing" }
        val spec = parseConnectionSpec(raw)
        Class.forName("org.postgresql.Driver")
        val props = Properties().apply {
            // Avoid PGJDBC's Android-incompatible heap-introspection path
            // in PGPropertyMaxResultBufferParser (java.lang.management.*).
            setProperty("maxResultBuffer", "0")
            if (!spec.user.isNullOrBlank()) {
                this["user"] = spec.user
            }
            if (!spec.password.isNullOrBlank()) {
                this["password"] = spec.password
            }
        }
        val connection = DriverManager.getConnection(spec.jdbcUrl, props)
        connection.use {
            return block(connection)
        }
    }

    private fun parseConnectionSpec(raw: String): DbConnectionSpec {
        if (raw.startsWith("jdbc:postgresql://", ignoreCase = true)) {
            return DbConnectionSpec(jdbcUrl = raw)
        }

        if (raw.startsWith("postgresql://", ignoreCase = true) ||
            raw.startsWith("postgres://", ignoreCase = true)
        ) {
            val normalized = if (raw.startsWith("postgres://", ignoreCase = true)) {
                "postgresql://${raw.substringAfter("://")}"
            } else {
                raw
            }
            val uri = URI(normalized)
            val host = uri.host ?: throw IllegalArgumentException("Database host missing in NEON_STRING")
            val port = if (uri.port > 0) uri.port else 5432
            val database = uri.path?.trimStart('/').orEmpty()
                .ifBlank { throw IllegalArgumentException("Database name missing in NEON_STRING") }
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }
            val jdbcUrl = buildString {
                append("jdbc:postgresql://")
                append(host)
                append(":")
                append(port)
                append("/")
                append(database)
                if (query != null) {
                    append("?")
                    append(query)
                }
            }
            val (user, password) = parseUserInfo(uri.rawUserInfo)
            return DbConnectionSpec(jdbcUrl = jdbcUrl, user = user, password = password)
        }

        return DbConnectionSpec(jdbcUrl = raw)
    }

    private fun parseUserInfo(rawUserInfo: String?): Pair<String?, String?> {
        if (rawUserInfo.isNullOrBlank()) return null to null
        val separator = rawUserInfo.indexOf(':')
        return if (separator >= 0) {
            val user = rawUserInfo.substring(0, separator).decodeUriComponent()
            val password = rawUserInfo.substring(separator + 1).decodeUriComponent()
            user to password
        } else {
            rawUserInfo.decodeUriComponent() to null
        }
    }

    private fun String.decodeUriComponent(): String {
        return URLDecoder.decode(this, StandardCharsets.UTF_8)
    }

    private fun ResultSet.getStringOrNull(column: String): String? {
        val value = getString(column)
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun ResultSet.getInstantOrNull(column: String): Instant? {
        val timestamp = getTimestamp(column) ?: return null
        return timestamp.toInstant()
    }

    private fun ResultSet.getStringListOrEmpty(column: String): List<String> {
        val sqlArray = getArray(column) ?: return emptyList()
        return try {
            val arrayValue = sqlArray.array
            when (arrayValue) {
                is Array<*> -> {
                    arrayValue.mapNotNull { value ->
                        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                is Collection<*> -> {
                    arrayValue.mapNotNull { value ->
                        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                else -> emptyList()
            }
        } finally {
            sqlArray.free()
        }
    }
}
