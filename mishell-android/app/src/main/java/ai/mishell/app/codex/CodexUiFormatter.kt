package ai.mishell.app.codex

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CodexUiFormatter {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d • HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    fun formatThreadTimestamp(timestampMillis: Long): String {
        if (timestampMillis <= 0L) {
            return "Unknown"
        }
        val now = Instant.now()
        val instant = Instant.ofEpochMilli(timestampMillis)
        val duration = Duration.between(instant, now)
        return when {
            duration.toMinutes() < 1 -> "Just now"
            duration.toHours() < 1 -> "${duration.toMinutes()}m ago"
            duration.toDays() < 1 -> "${duration.toHours()}h ago"
            duration.toDays() < 7 -> "${duration.toDays()}d ago"
            else -> dateTimeFormatter.format(instant)
        }
    }

    fun connectionLabel(state: CodexConnectionState): String {
        return when (state) {
            CodexConnectionState.Disconnected -> "Disconnected"
            CodexConnectionState.Connecting -> "Connecting"
            is CodexConnectionState.Connected -> "Connected"
            is CodexConnectionState.Reconnecting -> "Reconnecting"
            is CodexConnectionState.Failed -> "Error"
        }
    }
}
