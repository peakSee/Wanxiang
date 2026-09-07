package top.wanxiang.app.runtime.browser.screenshot

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenshotRecorderTest {

    @Test
    fun `capture after shutdown does not initialize screenshot thread`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = ScreenshotRecorder(context)
        recorder.shutdown()

        val result = recorder.capture(WebView(context), tabId = "closed")

        assertNull(result)
        assertFalse(recorder.isIoThreadInitializedForTest())
    }

    @Test
    fun `shutdown while capture is waiting for main thread does not start screenshot thread`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = ScreenshotRecorder(context)
        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            recorder.capture(WebView(context), tabId = "closing")
        }
        assertFalse("capture should be waiting for its queued main-thread draw", capture.isCompleted)

        recorder.shutdown()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(capture.await())
        assertFalse(recorder.isIoThreadInitializedForTest())
    }
}
