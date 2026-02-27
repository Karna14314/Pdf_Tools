package com.yourname.pdftoolkit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ThemeManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences("theme_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun getThemeModeImmediate_returnsDefaultSystem() {
        val theme = ThemeManager.getThemeModeImmediate(context)
        assertEquals(ThemeMode.SYSTEM, theme)
    }

    @Test
    fun setThemeMode_savesAndReturnsCorrectValue() = runBlocking {
        ThemeManager.setThemeMode(context, ThemeMode.DARK)

        val immediateTheme = ThemeManager.getThemeModeImmediate(context)
        assertEquals(ThemeMode.DARK, immediateTheme)

        val flowTheme = ThemeManager.getThemeMode(context).first()
        assertEquals(ThemeMode.DARK, flowTheme)
    }

    @Test
    fun getThemeMode_flowUpdatesOnLink() = runBlocking {
        // Initial state
        var flowValue = ThemeManager.getThemeMode(context).first()
        assertEquals(ThemeMode.SYSTEM, flowValue)

        // Change theme
        ThemeManager.setThemeMode(context, ThemeMode.LIGHT)

        // Verify flow emits new value (we re-collect or test stream if we had a collector)
        // For simplicity in this unit test, checking next value
        flowValue = ThemeManager.getThemeMode(context).first()
        assertEquals(ThemeMode.LIGHT, flowValue)
    }
}
