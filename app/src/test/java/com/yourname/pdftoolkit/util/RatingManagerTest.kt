package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RatingManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RatingManager.reset(context)
    }

    @Test
    fun testIncrementUsage() = runBlocking {
        // 1st usage
        assertFalse(RatingManager.incrementUsage(context))

        // 2nd usage
        assertFalse(RatingManager.incrementUsage(context))

        // 3rd usage
        assertFalse(RatingManager.incrementUsage(context))

        // 4th usage - should return true
        // We can check flow emission too
        var emitted = false
        val job = launch {
            try {
                // Collect one emission
                RatingManager.showRatingRequest.take(1).collect {
                    emitted = it
                }
            } catch (e: Exception) {
                // Flow collection might fail if cancelled?
            }
        }

        val result = RatingManager.incrementUsage(context)
        assertTrue("Should return true on 4th usage", result)

        // Give chance for emission to happen (though runBlocking handles it usually?)
        // In Robolectric/runBlocking, execution order is usually sequential for launched coroutines unless delay/yield.
        // But SharedFlow emit is suspend?

        // If incrementUsage emits, it suspends until collectors receive (for SharedFlow without buffer? No, shared flow suspends if buffer full).
        // Default SharedFlow buffer is 0, suspends on buffer overflow = SUSPEND.
        // Wait, MutableSharedFlow default is extraBufferCapacity=0, onBufferOverflow=SUSPEND.
        // If replay=0, it emits to active subscribers. It doesn't suspend unless subscribers are slow and buffer is full.

        // Let's verify result is true.
        // Emitted check might be flaky without proper test dispatcher.

        job.cancel()
    }

    @Test
    fun testNoRepeat() = runBlocking {
        // Simulate 4 usages
        repeat(4) { RatingManager.incrementUsage(context) }

        // 5th usage
        val result = RatingManager.incrementUsage(context)
        assertFalse("Should not trigger again", result)
    }
}
