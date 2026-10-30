package io.github.sagernet.libghostty

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttySnapshotTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun GhosttyTerminalSession.feed(text: String) =
        feedOutput(text.toByteArray(Charsets.UTF_8))

    @Test
    fun restoreResumesSavedTerminal() {
        val session = GhosttyTerminalSession(
            context,
            GhosttyTerminalSession.Options(columns = 40, rows = 8),
        )
        try {
            session.feed("\u001b]0;snapshot-title\u0007\u001b]7;file:///tmp/pwd\u001b\\")
            repeat(40) { session.feed("line $it\r\n") }
            session.feed("\u001b[5;3")

            val snapshot = requireNotNull(session.saveSnapshot())
            val restored = requireNotNull(GhosttyTerminalSession.restore(context, snapshot))
            try {
                assertEquals("snapshot-title", restored.title.value)
                assertEquals("/tmp/pwd", restored.workingDirectory.value)
                assertEquals(session.transcriptText(), restored.transcriptText())

                session.feed("Hmarker")
                restored.feed("Hmarker")
                assertEquals(session.screenText(), restored.screenText())
                assertEquals(session.transcriptText(), restored.transcriptText())

                val again = requireNotNull(
                    GhosttyTerminalSession.restore(context, requireNotNull(restored.saveSnapshot())),
                )
                try {
                    assertEquals(restored.transcriptText(), again.transcriptText())
                } finally {
                    again.close()
                }
            } finally {
                restored.close()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun restoreRejectsInvalidSnapshot() {
        assertNull(GhosttyTerminalSession.restore(context, ByteArray(0)))
        assertNull(GhosttyTerminalSession.restore(context, "not a snapshot".toByteArray()))

        val session = GhosttyTerminalSession(context)
        val snapshot = try {
            session.feed("hello\r\n")
            requireNotNull(session.saveSnapshot())
        } finally {
            session.close()
        }
        assertNull(GhosttyTerminalSession.restore(context, snapshot.copyOf(snapshot.size - 1)))
        val corrupted = snapshot.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0xFF).toByte()
        assertNull(GhosttyTerminalSession.restore(context, corrupted))
    }
}
