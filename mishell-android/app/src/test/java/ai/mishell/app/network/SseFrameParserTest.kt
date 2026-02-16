package ai.mishell.app.network

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseFrameParserTest {
    private val parser = SseFrameParser()

    @Test
    fun `parses delta and done events`() {
        val source = Buffer().writeUtf8(
            "event: delta\n" +
                "data: {\"text\":\"Hello\"}\n\n" +
                "event: done\n" +
                "data: {}\n\n"
        )

        assertEquals(SseEvent.Delta("Hello"), parser.nextEvent(source))
        assertEquals(SseEvent.Done, parser.nextEvent(source))
        assertNull(parser.nextEvent(source))
    }

    @Test
    fun `parses error event message`() {
        val source = Buffer().writeUtf8(
            "event: error\n" +
                "data: {\"message\":\"backend boom\"}\n\n"
        )

        assertEquals(SseEvent.Error("backend boom"), parser.nextEvent(source))
        assertNull(parser.nextEvent(source))
    }

    @Test
    fun `ignores unknown events and continues`() {
        val source = Buffer().writeUtf8(
            "event: ping\n" +
                "data: {}\n\n" +
                "event: delta\n" +
                "data: {\"text\":\"ok\"}\n\n"
        )

        assertEquals(SseEvent.Delta("ok"), parser.nextEvent(source))
    }
}
