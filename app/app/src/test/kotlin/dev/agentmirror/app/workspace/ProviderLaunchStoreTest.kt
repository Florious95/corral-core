package dev.agentmirror.app.workspace

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderLaunchStoreTest {

    @Test
    fun persistGrokLocalRoundTrip() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("provider_launch", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SharedPreferencesProviderLaunchStore(ctx)
        val next = store.load().map {
            if (it.providerId == "grok") it.copy(command = "grok-local") else it
        }
        store.save(next)
        val reloaded = SharedPreferencesProviderLaunchStore(ctx).load()
        val grok = reloaded.single { it.providerId == "grok" }
        assertEquals("grok-local", grok.command)
        assertEquals("--always-approve", grok.bypassFlag)
        assertEquals(6, reloaded.size)
        assertTrue(reloaded.all { it.providerId in ProviderIds })
        val pi = reloaded.single { it.providerId == "pi" }
        assertEquals("", pi.bypassFlag)
    }
}
