package com.yourname.pdftoolkit.domain.imposition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies Cut & Stack booklet ordering for 2x2 N-Up grids (#144).
 * Reference tables from the issue (1-based page numbers):
 * LTR front [1,3,5,7] back [4,2,8,6]; RTL front [3,1,7,5] back [2,4,6,8].
 */
class ImpositionEngineCutStackTest {

    private fun nUpConfig(mode: NUpLayoutMode) = ImpositionConfig(
        mode = ImpositionToolMode.N_UP,
        gridRows = 2,
        gridCols = 2,
        nUpLayoutMode = mode
    )

    @Test
    fun cutStackLtr_ordersEightPagesPerIssueTable() {
        val order = ImpositionEngine.buildCutStackOrder(8, NUpLayoutMode.CUT_STACK_LTR)
        // 0-based expectation of the issue's 1-based tables.
        assertEquals(listOf(0, 2, 4, 6, 3, 1, 7, 5), order)
    }

    @Test
    fun cutStackRtl_ordersEightPagesPerIssueTable() {
        val order = ImpositionEngine.buildCutStackOrder(8, NUpLayoutMode.CUT_STACK_RTL)
        assertEquals(listOf(2, 0, 6, 4, 1, 3, 5, 7), order)
    }

    @Test
    fun cutStack_padsShortGroupWithBlanks() {
        val order = ImpositionEngine.buildCutStackOrder(5, NUpLayoutMode.CUT_STACK_LTR)
        assertEquals(8, order.size)
        // Pages 0..4 placed per pattern slots [0,2,4,6,3,...]; slots 5,6,7 missing.
        assertEquals(listOf(0, 2, 4, -1, 3, 1, -1, -1), order)
    }

    @Test
    fun standardMode_keepsSequentialOrder() {
        val order = ImpositionEngine.buildCutStackOrder(8, NUpLayoutMode.STANDARD)
        assertEquals((0 until 8).toList(), order)
    }

    @Test
    fun nUpLayout_producesTwoSheetsForEightCutStackPages() {
        val sheets = ImpositionEngine.calculateLayout(
            pageCount = 8,
            sourceWidthPt = 400f,
            sourceHeightPt = 600f,
            config = nUpConfig(NUpLayoutMode.CUT_STACK_LTR)
        )
        assertEquals(2, sheets.size)
        val front = sheets[0].placements.map { it.sourcePageIndex }
        val back = sheets[1].placements.map { it.sourcePageIndex }
        assertEquals(listOf(0, 2, 4, 6), front)
        assertEquals(listOf(3, 1, 7, 5), back)
    }

    @Test
    fun nUpLayout_non2x2GridFallsBackToStandard() {
        val sheets = ImpositionEngine.calculateLayout(
            pageCount = 6,
            sourceWidthPt = 400f,
            sourceHeightPt = 600f,
            config = nUpConfig(NUpLayoutMode.CUT_STACK_LTR).copy(gridCols = 3, gridRows = 1)
        )
        assertEquals(listOf(0, 1, 2), sheets[0].placements.map { it.sourcePageIndex })
        assertEquals(listOf(3, 4, 5), sheets[1].placements.map { it.sourcePageIndex })
    }
}
