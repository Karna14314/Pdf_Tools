package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ThemeManagerBenchmarkTest {

    @Test
    fun benchmarkGetThemeMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Warmup (optional, but good for stability)
        // ThemeManager.getThemeMode(context).first()

        val time = measureTimeMillis {
            val themeMode = ThemeManager.getThemeMode(context).first()
            println("Retrieved theme: $themeMode")
        }

        println("BENCHMARK: ThemeManager.getThemeMode().first() took $time ms")
    }
}
