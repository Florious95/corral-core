package dev.agentmirror.app.conversation

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionExportTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun markdownDownloadUsesBearerAndScopedIdentity() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/markdown; charset=utf-8").setBody("# Native history\n"))
            val (file, mime) = downloadSessionExport(context, server.url("/").toString(), "own-fixture-token", "host:s 1", "session-id")
            try {
                assertEquals("# Native history\n", file.readText())
                assertEquals("text/markdown", mime)
                assertTrue(file.canonicalPath.startsWith(File(context.cacheDir, "session-exports").canonicalPath + File.separator))
                val request = server.takeRequest()
                assertEquals("Bearer own-fixture-token", request.getHeader("Authorization"))
                assertEquals("/artifacts/session-id/export?ref=host%3As%201", request.path)
                assertFalse(request.path!!.contains("own-fixture-token"))
            } finally { file.delete() }
        }
    }

    @Test fun redirectNeverForwardsBearerOrDownloadsAnotherOrigin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/untrusted"))
            val failure = runCatching { downloadSessionExport(context, server.url("/").toString(), "own-fixture-token", "ref", "sid") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("HTTP 302"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unknownFormatCannotEnterShareCache() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("not a native artifact"))
            assertNotNull(runCatching { downloadSessionExport(context, server.url("/").toString(), "own-fixture-token", "ref", "sid") }.exceptionOrNull())
        }
    }

    @Test fun systemShareUsesScopedContentUriAndTemporaryReadGrant() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val directory = File(activity.cacheDir, "session-exports").apply { mkdirs() }
        val file = File.createTempFile("fixture-", ".md", directory).apply { writeText("# Native history") }
        try {
            shareSessionExport(activity, file, "text/markdown")
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/markdown", send.type)
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertTrue(uri.path!!.startsWith("/session_exports/"))
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { file.delete(); activity.finish() }
    }
}
