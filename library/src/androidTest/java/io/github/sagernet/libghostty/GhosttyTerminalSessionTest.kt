package io.github.sagernet.libghostty

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyTerminalSessionTest {

    @Test
    fun clipboardReadWithoutApprovalAnswersEmptyClipboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = GhosttyTerminalSession(context)
        val sent = ByteArrayOutputStream()
        session.transport = object : GhosttyTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                sent.write(data)
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {}

            override fun close() {}
        }
        try {
            session.feedOutput("\u001b]52;c;?\u001b\\".toByteArray(Charsets.UTF_8))
            assertEquals("\u001b]52;c;\u001b\\", sent.toString(Charsets.UTF_8.name()))
        } finally {
            session.close()
        }
    }
}
