package com.yourname.pdftoolkit.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FillFormsScreenTest {

    @Test
    fun testInitialState() {
        val viewModel = FillFormsViewModel()
        val state = viewModel.state.value
        assertEquals(false, state.isAnalyzing)
        assertEquals(false, state.hasForm)
    }

    @Test
    fun testUpdateFieldValue() {
        val viewModel = FillFormsViewModel()
        viewModel.updateFieldValue("test_field", "test_value")
        val state = viewModel.state.value
        assertEquals("test_value", state.fieldValues["test_field"])
    }
}
