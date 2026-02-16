package ai.mishell.app.network

import okio.BufferedSource

sealed interface SseEvent {
    data class Delta(val text: String) : SseEvent
    data object Done : SseEvent
    data class Error(val message: String) : SseEvent
}

class SseFrameParser {
    fun nextEvent(source: BufferedSource): SseEvent? {
        while (true) {
            var eventName: String? = null
            val dataLines = mutableListOf<String>()

            while (true) {
                val line = source.readUtf8Line() ?: return buildEvent(eventName, dataLines)
                if (line.isBlank()) {
                    break
                }
                if (line.startsWith(":")) {
                    continue
                }

                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) {
                    continue
                }

                val field = line.substring(0, separatorIndex)
                val value = line.substring(separatorIndex + 1).trimStart()
                when (field) {
                    "event" -> eventName = value
                    "data" -> dataLines += value
                }
            }

            val event = buildEvent(eventName, dataLines)
            if (event != null) {
                return event
            }
        }
    }

    private fun buildEvent(eventName: String?, dataLines: List<String>): SseEvent? {
        if (eventName == null && dataLines.isEmpty()) {
            return null
        }

        val payload = dataLines.joinToString("\n")
        return when (eventName?.lowercase()) {
            "delta" -> {
                val text = parsePayloadField(payload, "text")
                if (text.isNotEmpty()) SseEvent.Delta(text) else null
            }
            "done" -> SseEvent.Done
            "error" -> {
                val message = parsePayloadField(payload, "message")
                    .ifEmpty { payload.ifEmpty { "stream error" } }
                SseEvent.Error(message)
            }
            else -> null
        }
    }

    private fun parsePayloadField(payload: String, field: String): String {
        if (payload.isBlank()) {
            return ""
        }
        val pattern = Regex(""""${Regex.escape(field)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val rawValue = pattern.find(payload)?.groupValues?.getOrNull(1) ?: return ""
        return decodeJsonString(rawValue)
    }

    private fun decodeJsonString(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                val escaped = value[index + 1]
                when (escaped) {
                    '\\' -> output.append('\\')
                    '"' -> output.append('"')
                    '/' -> output.append('/')
                    'b' -> output.append('\b')
                    'f' -> output.append('\u000C')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    else -> output.append(escaped)
                }
                index += 2
                continue
            }

            output.append(current)
            index += 1
        }
        return output.toString()
    }
}
