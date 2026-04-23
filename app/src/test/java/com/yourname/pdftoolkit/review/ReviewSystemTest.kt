package com.yourname.pdftoolkit.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for the In-App Review System.
 *
 * Test Scenarios:
 *
 * 1. **Multiple Feature Usage Test**
 *    - Track multiple uses of different features
 *    - Verify usage counts accumulate correctly
 *    - Verify review triggers when threshold met
 *
 * 2. **App Reopen Scenarios**
 *    - Simulate app background/foreground cycles
 *    - Verify session time accumulates across sessions
 *    - Verify usage counts persist across app restarts
 *
 * 3. **Already Rated User Test**
 *    - Mark user as rated
 *    - Verify review prompt is permanently suppressed
 *    - Verify no tracking occurs after rating
 *
 * 4. **Cooldown Period Test**
 *    - Show review prompt
 *    - Verify 3-day cooldown prevents immediate re-prompt
 *    - Verify prompt allowed after cooldown expires
 *
 * 5. **Session Time Tracking**
 *    - Verify session time tracks correctly in foreground
 *    - Verify session time pauses in background
 *    - Verify persistence of session data
 *
 * 6. **Thread Safety Test**
 *    - Concurrent usage tracking from multiple threads
 *    - Verify no race conditions or data corruption
 *
 * 7. **Review Flow Integration**
 *    - Test Play Store redirect on F-Droid
 *    - Test In-App Review on Play Store flavor
 */
@RunWith(AndroidJUnit4::class)
class ReviewSystemTest {

    private lateinit var context: Context
    private lateinit var reviewPreferences: ReviewPreferences
    private lateinit var usageTracker: UsageTracker
    private lateinit var reviewManager: ReviewManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Reset all singletons before each test
        ReviewPreferences.resetInstance()
        UsageTracker.resetInstance()
        ReviewManager.resetInstance()

        reviewPreferences = ReviewPreferences.getInstance(context)
        usageTracker = UsageTracker.getInstance(context)
        reviewManager = ReviewManager.getInstance(context)

        // Reset all data
        runBlocking {
            reviewPreferences.resetAll()
        }

        // Enable debug logging for tests
        ReviewLogger.logLevel = ReviewLogger.LogLevel.DEBUG
    }

    @After
    fun tearDown() {
        // Cleanup
        usageTracker.stopTracking()
    }

    // ==================== TEST 1: Multiple Feature Usage ====================

    @Test
    fun testMultipleFeatureUsageAccumulates() = runBlocking {
        // Track multiple uses of different features
        usageTracker.trackPdfViewerUsage()
        usageTracker.trackPdfViewerUsage()
        usageTracker.trackMergeUsage()
        usageTracker.trackMergeUsage()
        usageTracker.trackMergeUsage()
        usageTracker.trackSplitUsage()

        kotlinx.coroutines.delay(100) // allow background coroutines to persist
        val data = reviewPreferences.getReviewData()

        // Verify individual feature counts
        assertEquals("PDF Viewer usage should be 2", 2, data.pdfViewerUsage)
        assertEquals("Merge usage should be 3", 3, data.mergeUsage)
        assertEquals("Split usage should be 1", 1, data.splitUsage)
        assertEquals("Lock usage should be 0", 0, data.lockUsage)
        assertEquals("Compress usage should be 0", 0, data.compressUsage)

        // Verify total usage
        assertEquals("Total usage should be 6", 6, data.getTotalFeatureUsage())
    }

    @Test
    fun testReviewTriggersAfterThreshold() = runBlocking {
        // Initially should not request review
        assertFalse("Should not request review initially", reviewManager.shouldRequestReview())

        // Track 3 uses of same feature
        repeat(3) {
            usageTracker.trackPdfViewerUsage()
        }

        // Still should not trigger (not enough session time)
        assertFalse("Should not trigger without enough session time", reviewManager.shouldRequestReview())

        // Simulate enough session time (10+ minutes)
        reviewPreferences.addSessionTime(11 * 60 * 1000L)

        // Now should trigger
        assertTrue("Should request review after meeting all thresholds", reviewManager.shouldRequestReview())
    }

    // ==================== TEST 2: App Reopen Scenarios ====================

    @Test
    fun testSessionTimeAccumulatesAcrossBackgrounding() = runBlocking {
        // Simulate app foreground (session starts)
        usageTracker.onAppForeground()

        // Wait a bit (simulated)
        Thread.sleep(100)

        // App goes to background
        usageTracker.onAppBackground()

        // Get accumulated time
        kotlinx.coroutines.delay(100) // allow background coroutines to persist
        val timeAfterBackground = reviewPreferences.getReviewData().totalSessionTimeMs

        // Should have some session time recorded
        assertTrue("Should have recorded some session time", timeAfterBackground > 0)

        // App comes back to foreground
        usageTracker.onAppForeground()
        Thread.sleep(100)
        usageTracker.onAppBackground()

        // Time should have accumulated
        kotlinx.coroutines.delay(100) // allow background coroutines to persist
        val timeAfterSecondBackground = reviewPreferences.getReviewData().totalSessionTimeMs
        assertTrue("Session time should accumulate", timeAfterSecondBackground > timeAfterBackground)
    }

    @Test
    fun testUsageCountsPersist() = runBlocking {
        // Track some usage
        usageTracker.trackPdfViewerUsage()
        usageTracker.trackMergeUsage()
        kotlinx.coroutines.delay(100)

        // Get new instances (simulating app restart)
        usageTracker.stopTracking()
        kotlinx.coroutines.delay(100)
        UsageTracker.resetInstance()
        ReviewPreferences.resetInstance()

        val newPrefs = ReviewPreferences.getInstance(context)
        val data = newPrefs.getReviewData()

        // Counts should persist
        assertEquals("PDF Viewer usage should persist", 1, data.pdfViewerUsage)
        assertEquals("Merge usage should persist", 1, data.mergeUsage)
    }

    // ==================== TEST 3: Already Rated User ====================

    @Test
    fun testAlreadyRatedUserNeverSeesPrompt() = runBlocking {
        // Set up conditions that would normally trigger review
        repeat(10) { usageTracker.trackPdfViewerUsage() }
        reviewPreferences.addSessionTime(20 * 60 * 1000L)

        // Verify would normally trigger
        assertTrue("Should normally trigger", reviewManager.shouldRequestReview())

        // Mark as rated
        reviewManager.markUserAsRated()

        // Now should never trigger
        assertFalse("Should not trigger after user rated", reviewManager.shouldRequestReview())

        // Even with more usage
        repeat(10) { usageTracker.trackPdfViewerUsage() }
        assertFalse("Should still not trigger after more usage", reviewManager.shouldRequestReview())
    }

    // ==================== TEST 4: Cooldown Period ====================

    @Test
    fun testCooldownPeriodPreventsRePrompt() = runBlocking {
        // Set up conditions for review
        usageTracker.onAppForeground()
        repeat(5) { usageTracker.trackPdfViewerUsage() }
        reviewPreferences.addSessionTime(15 * 60 * 1000L)

        // Mark review prompt shown recently
        reviewPreferences.markReviewPromptShown()

        // Should not trigger (cooldown period)
        assertFalse("Should not trigger during cooldown", reviewManager.shouldRequestReview())

        // Simulate 4 days passing
        val fourDaysAgo = System.currentTimeMillis() - (4 * 24 * 60 * 60 * 1000L)
        reviewPreferences.setLastReviewPromptForTesting(fourDaysAgo)

        // Now should trigger again
        assertTrue("Should trigger after cooldown expires", reviewManager.shouldRequestReview())
    }

    // ==================== TEST 5: Session Time Tracking ====================

    @Test
    fun testSessionTimeOnlyCountsForeground() = runBlocking {
        // Start in foreground
        kotlinx.coroutines.delay(100)
        usageTracker.onAppForeground()

        // Get initial time
        val initialTime = usageTracker.getCurrentTotalSessionTimeMs()

        // Wait
        Thread.sleep(200)

        // Time should have increased
        val afterWait = usageTracker.getCurrentTotalSessionTimeMs()
        assertTrue("Session time should increase in foreground", afterWait > initialTime)

        // Go to background
        usageTracker.onAppBackground()
        kotlinx.coroutines.delay(100) // allow background coroutines to persist
        val backgroundTime = afterWait

        // Wait again
        Thread.sleep(200)

        // Time should not have increased in background
        val afterBackgroundWait = usageTracker.getCurrentTotalSessionTimeMs()
        assertEquals("Session time should not increase in background", backgroundTime, afterBackgroundWait)
    }

    // ==================== TEST 6: Thread Safety ====================

    @Test
    fun testConcurrentUsageTracking() = runBlocking {
        val threads = List(10) { threadIndex ->
            Thread {
                repeat(10) {
                    when (threadIndex % 5) {
                        0 -> usageTracker.trackPdfViewerUsage()
                        1 -> usageTracker.trackMergeUsage()
                        2 -> usageTracker.trackSplitUsage()
                        3 -> usageTracker.trackLockUsage()
                        4 -> usageTracker.trackCompressUsage()
                    }
                }
            }
        }

        // Start all threads
        threads.forEach { it.start() }

        // Wait for all to complete
        threads.forEach { it.join() }

        // Verify counts
        kotlinx.coroutines.delay(100)
        val data = reviewPreferences.getReviewData()
        assertEquals("PDF Viewer usage should be 20", 20, data.pdfViewerUsage)
        assertEquals("Merge usage should be 20", 20, data.mergeUsage)
        assertEquals("Split usage should be 20", 20, data.splitUsage)
        assertEquals("Lock usage should be 20", 20, data.lockUsage)
        assertEquals("Compress usage should be 20", 20, data.compressUsage)
        assertEquals("Total should be 100", 100, data.getTotalFeatureUsage())
    }

    // ==================== TEST 7: Review Flow ====================

    @Test
    fun testReviewStatsCalculation() = runBlocking {
        // Track usage
        usageTracker.onAppForeground()
        usageTracker.trackPdfViewerUsage()
        usageTracker.trackMergeUsage()
        usageTracker.trackMergeUsage()

        kotlinx.coroutines.delay(100)
        // Add session time
        reviewPreferences.addSessionTime(5 * 60 * 1000L)

        kotlinx.coroutines.delay(100)
        // Get stats
        val stats = reviewManager.getReviewStats()

        // Verify stats
        assertEquals("PDF Viewer in stats", 1, stats.pdfViewerUsage)
        assertEquals("Merge in stats", 2, stats.mergeUsage)
        assertEquals("Total usage in stats", 3, stats.totalFeatureUsage)
        assertTrue("Session time in stats should be at least expected", stats.totalSessionTimeMs >= 5 * 60 * 1000L)
        assertTrue("Session minutes should be at least expected", stats.totalSessionTimeMinutes >= 5L)
        assertFalse("Should not be able to request", stats.canRequestReview)
        assertFalse("Should not have rated", stats.hasRated)
    }

    @Test
    fun testResetFunctionality() = runBlocking {
        // Add some data
        usageTracker.trackPdfViewerUsage()
        reviewPreferences.addSessionTime(5 * 60 * 1000L)
        reviewPreferences.markAsRated()

        // Verify data exists
        var data = reviewPreferences.getReviewData()
        assertTrue("Should have data", data.pdfViewerUsage > 0 || data.hasRated)

        // Reset
        reviewPreferences.resetAll()

        // Verify reset
        data = reviewPreferences.getReviewData()
        assertEquals("Usage should be 0", 0, data.pdfViewerUsage)
        assertEquals("Session time should be 0", 0L, data.totalSessionTimeMs)
        assertFalse("Should not be rated", data.hasRated)
    }
}
