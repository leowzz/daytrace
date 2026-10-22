package com.daytrace.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TimingSettingsDeviceTest {
    private fun isolated(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "timing-test-${UUID.randomUUID()}"
        val root = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context) } finally { base.deleteSharedPreferences(name); root.deleteRecursively() }
    }

    @Test fun timingsSurviveStoreRecreationAndCannotChangeDuringCapture() = isolated { context ->
        val store = RecordingStore(context)
        assertEquals(VadConfig(), store.settings.value.vadConfig)
        val settings = AudioSettings(vadConfig = VadConfig(100, 200, 80, 1000))
        store.configure(settings)
        val reopened = RecordingStore(context)
        assertEquals(settings, reopened.settings.value)
        val snapshot = JSONObject(File(context.filesDir, "status.json").readText()).getJSONObject("vadTiming")
        assertEquals(1000, snapshot.getInt("maximum_segment_ms"))
        reopened.update({ it.copy(phase = Phase.RECORDING) })
        assertTrue(runCatching { reopened.configure(AudioSettings()) }.isFailure)
        assertEquals(settings, reopened.settings.value)
        reopened.update({ it.copy(phase = Phase.PAUSED) })
        reopened.configure(AudioSettings())
        assertEquals(VadConfig(), RecordingStore(context).settings.value.vadConfig)
    }

    @Test fun invalidPersistedTimingsFallBackWithoutPreventingStartup() = isolated { context ->
        context.getSharedPreferences("recording", Context.MODE_PRIVATE).edit()
            .putInt("silenceMs", 0).putInt("preRollMs", Int.MAX_VALUE).commit()
        assertEquals(VadConfig(), RecordingStore(context).settings.value.vadConfig)
    }
}
