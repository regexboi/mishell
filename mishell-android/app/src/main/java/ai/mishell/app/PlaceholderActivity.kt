package ai.mishell.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
            3 -> setupUpcomingMeetingsScreen()
            else -> setupFallbackScreen(number)
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
        autoScrollJob?.cancel()
        clearDisplayPowerModeTimer()
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

    private fun setupUpcomingMeetingsScreen() {
        binding.titleText.text = getString(R.string.meetings_title)
        binding.subtitleText.visibility = View.VISIBLE
        binding.subtitleText.text = getString(R.string.meetings_subtitle)
        binding.placeholderText.visibility = View.GONE
        binding.contentList.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.meetings_status_loading)

        lifecycleScope.launch {
            runCatching {
                RssRepository.fetchUpcomingMeetings(this@PlaceholderActivity)
            }.onSuccess { meetings ->
                binding.loadingIndicator.visibility = View.GONE
                if (meetings.isEmpty()) {
                    binding.statusText.text = getString(R.string.meetings_status_empty)
                    binding.contentList.visibility = View.GONE
                    binding.placeholderText.visibility = View.VISIBLE
                    binding.placeholderText.text = getString(R.string.meetings_status_empty)
                    return@onSuccess
                }

                binding.statusText.text =
                    getString(R.string.meetings_status_loaded, meetings.size)
                val adapter = MeetingAdapter(this@PlaceholderActivity, meetings)
                binding.contentList.adapter = adapter
                binding.contentList.setOnItemClickListener { _, _, position, _ ->
                    adapter.toggleExpanded(position)
                }
            }.onFailure { error ->
                binding.loadingIndicator.visibility = View.GONE
                binding.statusText.text =
                    getString(R.string.meetings_status_error, error.message ?: "unknown error")
                binding.contentList.visibility = View.GONE
                binding.placeholderText.visibility = View.VISIBLE
                binding.placeholderText.text = getString(
                    R.string.meetings_status_error,
                    error.message ?: "unknown error"
                )
            }
        }
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
        applyAlwaysOnUltraDimMode()
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

    private class MeetingAdapter(
        activity: PlaceholderActivity,
        items: List<RssRepository.MeetingListItem>
    ) : BaseAdapter() {
        private sealed interface CalendarRow {
            data class DayHeader(
                val date: LocalDate?,
                val meetingCount: Int,
                val freeMinutes: Long,
                val conflictCount: Int
            ) : CalendarRow

            data class FreeSlot(
                val start: Instant,
                val end: Instant,
                val minutes: Long
            ) : CalendarRow

            data class Meeting(
                val meeting: RssRepository.MeetingListItem,
                val hasConflict: Boolean,
                val overlapMinutes: Long
            ) : CalendarRow
        }

        private val host = activity
        private val inflater = LayoutInflater.from(activity)
        private val zoneId = ZoneId.systemDefault()
        private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
            .withLocale(Locale.getDefault())
            .withZone(zoneId)
        private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
            .withLocale(Locale.getDefault())
            .withZone(zoneId)
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
            .withLocale(Locale.getDefault())
            .withZone(zoneId)
        private val rows = buildRows(items)
        private val expandedEventIds = linkedSetOf<String>()

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long {
            val row = rows[position]
            return when (row) {
                is CalendarRow.DayHeader -> ("day-${row.date}").hashCode().toLong()
                is CalendarRow.FreeSlot -> ("free-${row.start}-${row.end}").hashCode().toLong()
                is CalendarRow.Meeting -> row.meeting.eventId.hashCode().toLong()
            }
        }

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is CalendarRow.DayHeader -> 0
                is CalendarRow.FreeSlot -> 1
                is CalendarRow.Meeting -> 2
            }
        }

        fun toggleExpanded(position: Int) {
            val row = rows.getOrNull(position) as? CalendarRow.Meeting ?: return
            val eventId = row.meeting.eventId
            if (!expandedEventIds.add(eventId)) {
                expandedEventIds.remove(eventId)
            }
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is CalendarRow.DayHeader -> renderDayHeader(row, convertView, parent)
                is CalendarRow.FreeSlot -> renderFreeSlot(row, convertView, parent)
                is CalendarRow.Meeting -> renderMeeting(row, convertView, parent)
            }
        }

        private fun renderDayHeader(
            row: CalendarRow.DayHeader,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val view = convertView ?: inflater.inflate(R.layout.item_meeting_day_header, parent, false)
            val dateText = view.findViewById<TextView>(R.id.meeting_day_text)
            val summaryText = view.findViewById<TextView>(R.id.meeting_day_summary)
            dateText.text = row.date?.let { dayHeaderFormatter.format(it.atStartOfDay(zoneId)) }
                ?: host.getString(R.string.meetings_unscheduled_day)

            val summary = host.getString(
                R.string.meetings_day_summary_format,
                row.meetingCount,
                formatDuration(row.freeMinutes),
                row.conflictCount
            )
            summaryText.text = summary
            return view
        }

        private fun renderFreeSlot(
            row: CalendarRow.FreeSlot,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val view = convertView ?: inflater.inflate(R.layout.item_meeting_free_slot, parent, false)
            val titleText = view.findViewById<TextView>(R.id.free_slot_title)
            val rangeText = view.findViewById<TextView>(R.id.free_slot_range)
            titleText.text = host.getString(
                R.string.meetings_free_slot_title_format,
                formatDuration(row.minutes)
            )
            rangeText.text = host.getString(
                R.string.meetings_free_slot_range_format,
                timeFormatter.format(row.start),
                timeFormatter.format(row.end)
            )
            return view
        }

        private fun renderMeeting(
            row: CalendarRow.Meeting,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val view = convertView ?: inflater.inflate(R.layout.item_meeting, parent, false)
            val item = row.meeting

            val timeText = view.findViewById<TextView>(R.id.meeting_time_range)
            val durationText = view.findViewById<TextView>(R.id.meeting_duration)
            val titleText = view.findViewById<TextView>(R.id.meeting_title)
            val metaText = view.findViewById<TextView>(R.id.meeting_meta)
            val conflictText = view.findViewById<TextView>(R.id.meeting_conflict_chip)
            val detailsText = view.findViewById<TextView>(R.id.meeting_details)
            val expandHintText = view.findViewById<TextView>(R.id.meeting_expand_hint)

            timeText.text = formatCompactTimeRange(item)
            durationText.text = formatDurationForMeeting(item)
            titleText.text = item.title

            val organizer = item.organizerName?.takeIf { it.isNotBlank() }
                ?: item.organizerEmail?.takeIf { it.isNotBlank() }
                ?: host.getString(R.string.meetings_unknown_organizer)
            val location = item.room?.takeIf { it.isNotBlank() }
                ?: item.locationDisplayName?.takeIf { it.isNotBlank() }
                ?: host.getString(R.string.meetings_no_location)
            metaText.text = "$organizer • $location"

            if (row.hasConflict) {
                conflictText.visibility = View.VISIBLE
                conflictText.text = host.getString(
                    R.string.meetings_conflict_overlap_format,
                    formatDuration(row.overlapMinutes)
                )
            } else {
                conflictText.visibility = View.GONE
                conflictText.text = ""
            }

            val isExpanded = expandedEventIds.contains(item.eventId)
            detailsText.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandHintText.text = host.getString(
                if (isExpanded) {
                    R.string.meetings_collapse_hint
                } else {
                    R.string.meetings_expand_hint
                }
            )
            detailsText.text = formatDetails(item)

            return view
        }

        private fun formatCompactTimeRange(item: RssRepository.MeetingListItem): String {
            val start = item.startsAtUtc ?: return host.getString(R.string.meetings_unknown_time)
            val end = normalizedEnd(item)
            if (end == null || !end.isAfter(start)) {
                return timeFormatter.format(start)
            }
            val sameDay = start.atZone(zoneId).toLocalDate() == end.atZone(zoneId).toLocalDate()
            return if (sameDay) {
                "${timeFormatter.format(start)} - ${timeFormatter.format(end)}"
            } else {
                "${dateFormatter.format(start)} ${timeFormatter.format(start)} -> ${
                    dateFormatter.format(end)
                } ${timeFormatter.format(end)}"
            }
        }

        private fun formatDurationForMeeting(item: RssRepository.MeetingListItem): String {
            val start = item.startsAtUtc ?: return host.getString(R.string.meetings_duration_unknown)
            val end = normalizedEnd(item) ?: return host.getString(R.string.meetings_duration_unknown)
            val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(0L)
            return if (minutes == 0L) {
                host.getString(R.string.meetings_duration_short)
            } else {
                formatDuration(minutes)
            }
        }

        private fun normalizedEnd(item: RssRepository.MeetingListItem): Instant? {
            val start = item.startsAtUtc ?: return item.endsAtUtc
            val end = item.endsAtUtc ?: return start
            return if (end.isBefore(start)) start else end
        }

        private fun formatDetails(item: RssRepository.MeetingListItem): String {
            val attendees = item.attendeeNames
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: host.getString(R.string.meetings_no_attendees)
            val roomOrLocation = item.room
                ?: item.locationDisplayName
                ?: host.getString(R.string.meetings_no_location)
            val preview = item.descriptionPreview
                ?.takeIf { it.isNotBlank() }
                ?: host.getString(R.string.meetings_no_preview)
            val webLink = item.webLink
                ?.takeIf { it.isNotBlank() }
                ?: host.getString(R.string.meetings_no_link)
            return buildString {
                append(host.getString(R.string.meetings_details_attendees))
                append(": ")
                append(attendees)
                append('\n')
                append(host.getString(R.string.meetings_details_location))
                append(": ")
                append(roomOrLocation)
                append('\n')
                append(host.getString(R.string.meetings_details_preview))
                append(": ")
                append(preview)
                append('\n')
                append(host.getString(R.string.meetings_details_link))
                append(": ")
                append(webLink)
            }
        }

        private fun formatDuration(minutes: Long): String {
            val clamped = minutes.coerceAtLeast(0L)
            if (clamped < 60) return "${clamped}m"
            val hours = clamped / 60
            val remainder = clamped % 60
            return if (remainder == 0L) {
                "${hours}h"
            } else {
                "${hours}h ${remainder}m"
            }
        }

        private fun buildRows(items: List<RssRepository.MeetingListItem>): List<CalendarRow> {
            val sorted = items.sortedWith(
                compareBy<RssRepository.MeetingListItem> {
                    it.startsAtUtc?.toEpochMilli() ?: Long.MAX_VALUE
                }.thenBy { it.endsAtUtc?.toEpochMilli() ?: Long.MAX_VALUE }
            )

            val byDay = sorted.groupBy { item ->
                item.startsAtUtc?.atZone(zoneId)?.toLocalDate()
            }

            val rows = mutableListOf<CalendarRow>()
            val orderedDays = byDay.keys.filterNotNull().sorted()
            for (day in orderedDays) {
                val dayMeetings = byDay[day].orEmpty()
                appendDayRows(rows, day, dayMeetings)
            }

            val unscheduled = byDay[null].orEmpty()
            if (unscheduled.isNotEmpty()) {
                appendDayRows(rows, null, unscheduled)
            }

            return rows
        }

        private fun appendDayRows(
            rows: MutableList<CalendarRow>,
            day: LocalDate?,
            dayMeetings: List<RssRepository.MeetingListItem>
        ) {
            val dayRows = mutableListOf<CalendarRow>()
            var freeMinutes = 0L
            var conflictCount = 0
            var previousEnd: Instant? = null

            for (meeting in dayMeetings) {
                val start = meeting.startsAtUtc
                val end = normalizedEnd(meeting)
                var hasConflict = false
                var overlapMinutes = 0L

                if (start != null && previousEnd != null) {
                    if (start.isAfter(previousEnd)) {
                        val gapMinutes = Duration.between(previousEnd, start).toMinutes()
                            .coerceAtLeast(0L)
                        if (gapMinutes > 0) {
                            dayRows.add(
                                CalendarRow.FreeSlot(
                                    start = previousEnd,
                                    end = start,
                                    minutes = gapMinutes
                                )
                            )
                            freeMinutes += gapMinutes
                        }
                    } else if (start.isBefore(previousEnd)) {
                        hasConflict = true
                        val overlapEnd = if (end == null || previousEnd.isBefore(end)) {
                            previousEnd
                        } else {
                            end
                        }
                        overlapMinutes = Duration.between(start, overlapEnd)
                            .toMinutes()
                            .coerceAtLeast(1L)
                        conflictCount += 1
                    }
                }

                dayRows.add(
                    CalendarRow.Meeting(
                        meeting = meeting,
                        hasConflict = hasConflict,
                        overlapMinutes = overlapMinutes
                    )
                )

                if (end != null) {
                    previousEnd = if (previousEnd == null || end.isAfter(previousEnd)) {
                        end
                    } else {
                        previousEnd
                    }
                }
            }

            rows.add(
                CalendarRow.DayHeader(
                    date = day,
                    meetingCount = dayMeetings.size,
                    freeMinutes = freeMinutes,
                    conflictCount = conflictCount
                )
            )
            rows.addAll(dayRows)
        }
    }

    companion object {
        const val EXTRA_PLACEHOLDER_NUMBER = "extra_placeholder_number"
    }
}
