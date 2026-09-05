package com.yourname.pdftoolkit.domain.imposition

import kotlin.math.ceil
import kotlin.math.min

/**
 * Core imposition calculation engine.
 * Completely agnostic of PDF rendering frameworks. Given source document dimensions and page count,
 * it generates a deterministic set of virtual sheet layouts and page placements.
 */
object ImpositionEngine {

    fun calculateLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        if (pageCount <= 0) return emptyList()

        return when (config.mode) {
            ImpositionToolMode.N_UP -> calculateNUpLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            ImpositionToolMode.BOOKLET -> calculateBookletLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            ImpositionToolMode.CARDS -> calculateCardsLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            ImpositionToolMode.CROP_RESIZE -> calculateCropResizeLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            ImpositionToolMode.BLEED_GENERATOR,
            ImpositionToolMode.REGISTRATION_MARKS -> calculateBleedAndMarksLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            ImpositionToolMode.ZINE -> calculateZineLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
        }
    }

    // -------------------------------------------------------------------------
    // 1. N-UP LAYOUT ENGINE
    // -------------------------------------------------------------------------

    private fun calculateNUpLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        val sheetWidth = config.targetPaperSize.width(config.isLandscape)
        val sheetHeight = config.targetPaperSize.height(config.isLandscape)

        val marginTopPt = PaperSize.mmToPoints(config.marginTopMm)
        val marginBottomPt = PaperSize.mmToPoints(config.marginBottomMm)
        val marginLeftPt = PaperSize.mmToPoints(config.marginLeftMm)
        val marginRightPt = PaperSize.mmToPoints(config.marginRightMm)
        val gutterXPt = PaperSize.mmToPoints(config.gutterXMm)
        val gutterYPt = PaperSize.mmToPoints(config.gutterYMm)
        val bleedPt = PaperSize.mmToPoints(config.bleedMm)

        val gridCols = maxOf(1, config.gridCols)
        val gridRows = maxOf(1, config.gridRows)
        val pagesPerSheet = gridCols * gridRows

        val availableWidth = sheetWidth - marginLeftPt - marginRightPt - (gutterXPt * (gridCols - 1))
        val availableHeight = sheetHeight - marginTopPt - marginBottomPt - (gutterYPt * (gridRows - 1))

        val cellWidth = maxOf(1f, availableWidth / gridCols)
        val cellHeight = maxOf(1f, availableHeight / gridRows)

        val totalSheets = ceil(pageCount.toDouble() / pagesPerSheet).toInt()
        val sheetLayouts = mutableListOf<SheetLayout>()

        var currentPageIndex = 0

        for (sheetIdx in 0 until totalSheets) {
            val placements = mutableListOf<PagePlacement>()

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    if (currentPageIndex >= pageCount) break

                    val cellX = marginLeftPt + col * (cellWidth + gutterXPt)
                    val cellY = marginTopPt + row * (cellHeight + gutterYPt)

                    val transform = calculatePlacementTransform(
                        sourceWidth = sourceWidthPt,
                        sourceHeight = sourceHeightPt,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        fitMode = config.fitMode
                    )

                    val pageX = cellX + (cellWidth - transform.renderWidth) / 2f
                    val pageY = cellY + (cellHeight - transform.renderHeight) / 2f

                    placements.add(
                        PagePlacement(
                            sourcePageIndex = currentPageIndex,
                            xPt = pageX,
                            yPt = pageY,
                            widthPt = transform.renderWidth,
                            heightPt = transform.renderHeight,
                            scaleX = transform.scaleX,
                            scaleY = transform.scaleY
                        )
                    )

                    currentPageIndex++
                }
            }

            sheetLayouts.add(
                SheetLayout(
                    sheetIndex = sheetIdx,
                    widthPt = sheetWidth,
                    heightPt = sheetHeight,
                    marginTopPt = marginTopPt,
                    marginBottomPt = marginBottomPt,
                    marginLeftPt = marginLeftPt,
                    marginRightPt = marginRightPt,
                    gutterXPt = gutterXPt,
                    gutterYPt = gutterYPt,
                    placements = placements,
                    bleedPt = bleedPt,
                    showCropMarks = config.showCropMarks,
                    showRegistrationTargets = config.showRegistrationTargets,
                    showSafeZone = config.showSafeZones
                )
            )
        }

        return sheetLayouts
    }

    // -------------------------------------------------------------------------
    // 2. BOOKLET (SADDLE STITCH) ENGINE
    // -------------------------------------------------------------------------

    private fun calculateBookletLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        val isHorizontalBinding = config.bindingDirection == BindingDirection.LEFT_WESTERN ||
                config.bindingDirection == BindingDirection.RIGHT_RTL

        // Sheet orientation: Landscape if horizontal binding (2 pages side by side)
        val isLandscapeSheet = if (isHorizontalBinding) true else config.isLandscape
        val sheetWidth = config.targetPaperSize.width(isLandscapeSheet)
        val sheetHeight = config.targetPaperSize.height(isLandscapeSheet)

        val marginTopPt = PaperSize.mmToPoints(config.marginTopMm)
        val marginBottomPt = PaperSize.mmToPoints(config.marginBottomMm)
        val marginLeftPt = PaperSize.mmToPoints(config.marginLeftMm)
        val marginRightPt = PaperSize.mmToPoints(config.marginRightMm)
        val gutterPt = PaperSize.mmToPoints(if (isHorizontalBinding) config.gutterXMm else config.gutterYMm)
        val bleedPt = PaperSize.mmToPoints(config.bleedMm)

        // Calculate cell sizes
        val (cellWidth, cellHeight) = if (isHorizontalBinding) {
            val availW = sheetWidth - marginLeftPt - marginRightPt - gutterPt
            val availH = sheetHeight - marginTopPt - marginBottomPt
            Pair(maxOf(1f, availW / 2f), maxOf(1f, availH))
        } else {
            val availW = sheetWidth - marginLeftPt - marginRightPt
            val availH = sheetHeight - marginTopPt - marginBottomPt - gutterPt
            Pair(maxOf(1f, availW), maxOf(1f, availH / 2f))
        }

        // Signature calculations (0 = single signature with all pages)
        val rawSigSize = if (config.signatureSize > 0) config.signatureSize else pageCount
        val sigSize = maxOf(4, (rawSigSize + 3) / 4 * 4) // Ensure signature size is multiple of 4

        val totalPaddedPages = ceil(pageCount.toDouble() / 4.0).toInt() * 4
        val numSignatures = ceil(pageCount.toDouble() / sigSize.toDouble()).toInt()

        val sheetLayouts = mutableListOf<SheetLayout>()
        var globalSheetIdx = 0

        for (sigIdx in 0 until numSignatures) {
            val sigStartPage = sigIdx * sigSize
            val sigEndPage = min(sigStartPage + sigSize, totalPaddedPages)
            val sigPageCount = sigEndPage - sigStartPage
            val sigSheets = sigPageCount / 4

            for (sheetInSig in 0 until sigSheets) {
                // Outer front / back calculations
                val p1Index = sigStartPage + (sheetInSig * 2)
                val p2Index = sigStartPage + (sheetInSig * 2) + 1
                val p3Index = sigEndPage - 1 - (sheetInSig * 2) - 1
                val p4Index = sigEndPage - 1 - (sheetInSig * 2)

                // Adjust page order based on binding direction
                // Front sheet (Outer)
                val (frontLeft, frontRight) = when (config.bindingDirection) {
                    BindingDirection.LEFT_WESTERN -> Pair(p4Index, p1Index)
                    BindingDirection.RIGHT_RTL -> Pair(p1Index, p4Index)
                    BindingDirection.TOP_CALENDAR -> Pair(p4Index, p1Index)
                    BindingDirection.BOTTOM_FLIPBOOK -> Pair(p1Index, p4Index)
                }

                // Back sheet (Inner)
                val (backLeft, backRight) = when (config.bindingDirection) {
                    BindingDirection.LEFT_WESTERN -> Pair(p2Index, p3Index)
                    BindingDirection.RIGHT_RTL -> Pair(p3Index, p2Index)
                    BindingDirection.TOP_CALENDAR -> Pair(p2Index, p3Index)
                    BindingDirection.BOTTOM_FLIPBOOK -> Pair(p3Index, p2Index)
                }

                // Build Front Sheet
                sheetLayouts.add(
                    buildBookletSheet(
                        sheetIdx = globalSheetIdx++,
                        sheetWidth = sheetWidth,
                        sheetHeight = sheetHeight,
                        leftPageIdx = if (frontLeft < pageCount) frontLeft else -1,
                        rightPageIdx = if (frontRight < pageCount) frontRight else -1,
                        sourceWidthPt = sourceWidthPt,
                        sourceHeightPt = sourceHeightPt,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        isHorizontal = isHorizontalBinding,
                        marginTopPt = marginTopPt,
                        marginBottomPt = marginBottomPt,
                        marginLeftPt = marginLeftPt,
                        marginRightPt = marginRightPt,
                        gutterPt = gutterPt,
                        bleedPt = bleedPt,
                        config = config
                    )
                )

                // Build Back Sheet
                sheetLayouts.add(
                    buildBookletSheet(
                        sheetIdx = globalSheetIdx++,
                        sheetWidth = sheetWidth,
                        sheetHeight = sheetHeight,
                        leftPageIdx = if (backLeft < pageCount) backLeft else -1,
                        rightPageIdx = if (backRight < pageCount) backRight else -1,
                        sourceWidthPt = sourceWidthPt,
                        sourceHeightPt = sourceHeightPt,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        isHorizontal = isHorizontalBinding,
                        marginTopPt = marginTopPt,
                        marginBottomPt = marginBottomPt,
                        marginLeftPt = marginLeftPt,
                        marginRightPt = marginRightPt,
                        gutterPt = gutterPt,
                        bleedPt = bleedPt,
                        config = config
                    )
                )
            }
        }

        return sheetLayouts
    }

    private fun buildBookletSheet(
        sheetIdx: Int,
        sheetWidth: Float,
        sheetHeight: Float,
        leftPageIdx: Int,
        rightPageIdx: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        cellWidth: Float,
        cellHeight: Float,
        isHorizontal: Boolean,
        marginTopPt: Float,
        marginBottomPt: Float,
        marginLeftPt: Float,
        marginRightPt: Float,
        gutterPt: Float,
        bleedPt: Float,
        config: ImpositionConfig
    ): SheetLayout {
        val placements = mutableListOf<PagePlacement>()

        val (firstX, firstY) = Pair(marginLeftPt, marginTopPt)
        val (secondX, secondY) = if (isHorizontal) {
            Pair(marginLeftPt + cellWidth + gutterPt, marginTopPt)
        } else {
            Pair(marginLeftPt, marginTopPt + cellHeight + gutterPt)
        }

        // Left / Top placement
        if (leftPageIdx >= -1) {
            val transform = calculatePlacementTransform(
                sourceWidth = sourceWidthPt,
                sourceHeight = sourceHeightPt,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                fitMode = config.fitMode
            )
            val px = firstX + (cellWidth - transform.renderWidth) / 2f
            val py = firstY + (cellHeight - transform.renderHeight) / 2f

            placements.add(
                PagePlacement(
                    sourcePageIndex = leftPageIdx,
                    xPt = px,
                    yPt = py,
                    widthPt = transform.renderWidth,
                    heightPt = transform.renderHeight,
                    scaleX = transform.scaleX,
                    scaleY = transform.scaleY
                )
            )
        }

        // Right / Bottom placement
        if (rightPageIdx >= -1) {
            val transform = calculatePlacementTransform(
                sourceWidth = sourceWidthPt,
                sourceHeight = sourceHeightPt,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                fitMode = config.fitMode
            )
            val px = secondX + (cellWidth - transform.renderWidth) / 2f
            val py = secondY + (cellHeight - transform.renderHeight) / 2f

            placements.add(
                PagePlacement(
                    sourcePageIndex = rightPageIdx,
                    xPt = px,
                    yPt = py,
                    widthPt = transform.renderWidth,
                    heightPt = transform.renderHeight,
                    scaleX = transform.scaleX,
                    scaleY = transform.scaleY
                )
            )
        }

        return SheetLayout(
            sheetIndex = sheetIdx,
            widthPt = sheetWidth,
            heightPt = sheetHeight,
            marginTopPt = marginTopPt,
            marginBottomPt = marginBottomPt,
            marginLeftPt = marginLeftPt,
            marginRightPt = marginRightPt,
            gutterXPt = if (isHorizontal) gutterPt else 0f,
            gutterYPt = if (!isHorizontal) gutterPt else 0f,
            placements = placements,
            bleedPt = bleedPt,
            showCropMarks = config.showCropMarks,
            showRegistrationTargets = config.showRegistrationTargets,
            showSafeZone = config.showSafeZones
        )
    }

    // -------------------------------------------------------------------------
    // 3. CARDS & FLASHCARDS ENGINE
    // -------------------------------------------------------------------------

    private fun calculateCardsLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        val sheetWidth = config.targetPaperSize.width(config.isLandscape)
        val sheetHeight = config.targetPaperSize.height(config.isLandscape)

        val marginTopPt = PaperSize.mmToPoints(config.marginTopMm)
        val marginBottomPt = PaperSize.mmToPoints(config.marginBottomMm)
        val marginLeftPt = PaperSize.mmToPoints(config.marginLeftMm)
        val marginRightPt = PaperSize.mmToPoints(config.marginRightMm)
        val gutterXPt = PaperSize.mmToPoints(config.gutterXMm)
        val gutterYPt = PaperSize.mmToPoints(config.gutterYMm)
        val bleedPt = PaperSize.mmToPoints(config.bleedMm)

        val gridCols = maxOf(1, config.gridCols)
        val gridRows = maxOf(1, config.gridRows)
        val cardsPerSheet = gridCols * gridRows

        val availW = sheetWidth - marginLeftPt - marginRightPt - (gutterXPt * (gridCols - 1))
        val availH = sheetHeight - marginTopPt - marginBottomPt - (gutterYPt * (gridRows - 1))

        val cellWidth = maxOf(1f, availW / gridCols)
        val cellHeight = maxOf(1f, availH / gridRows)

        val sheetLayouts = mutableListOf<SheetLayout>()

        when (config.cardMode) {
            CardMode.REPEAT -> {
                for (pageIdx in 0 until pageCount) {
                    val placements = mutableListOf<PagePlacement>()

                    for (row in 0 until gridRows) {
                        for (col in 0 until gridCols) {
                            val cellX = marginLeftPt + col * (cellWidth + gutterXPt)
                            val cellY = marginTopPt + row * (cellHeight + gutterYPt)

                            val transform = calculatePlacementTransform(
                                sourceWidthPt, sourceHeightPt, cellWidth, cellHeight, config.fitMode
                            )

                            placements.add(
                                PagePlacement(
                                    sourcePageIndex = pageIdx,
                                    xPt = cellX + (cellWidth - transform.renderWidth) / 2f,
                                    yPt = cellY + (cellHeight - transform.renderHeight) / 2f,
                                    widthPt = transform.renderWidth,
                                    heightPt = transform.renderHeight,
                                    scaleX = transform.scaleX,
                                    scaleY = transform.scaleY
                                )
                            )
                        }
                    }

                    sheetLayouts.add(
                        SheetLayout(
                            sheetIndex = pageIdx,
                            widthPt = sheetWidth,
                            heightPt = sheetHeight,
                            marginTopPt = marginTopPt,
                            marginBottomPt = marginBottomPt,
                            marginLeftPt = marginLeftPt,
                            marginRightPt = marginRightPt,
                            gutterXPt = gutterXPt,
                            gutterYPt = gutterYPt,
                            placements = placements,
                            bleedPt = bleedPt,
                            showCropMarks = config.showCropMarks,
                            showRegistrationTargets = config.showRegistrationTargets,
                            showSafeZone = config.showSafeZones
                        )
                    )
                }
            }

            CardMode.SEQUENTIAL -> {
                return calculateNUpLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            }

            CardMode.DUPLEX -> {
                var currentPage = 0
                val totalPairs = ceil(pageCount.toDouble() / (2.0 * cardsPerSheet)).toInt()

                for (pairIdx in 0 until totalPairs) {
                    // Front sheet
                    val frontPlacements = mutableListOf<PagePlacement>()
                    val frontStartPage = currentPage

                    for (row in 0 until gridRows) {
                        for (col in 0 until gridCols) {
                            val pageIdx = frontStartPage + (row * gridCols + col) * 2
                            if (pageIdx < pageCount) {
                                val cellX = marginLeftPt + col * (cellWidth + gutterXPt)
                                val cellY = marginTopPt + row * (cellHeight + gutterYPt)
                                val transform = calculatePlacementTransform(
                                    sourceWidthPt, sourceHeightPt, cellWidth, cellHeight, config.fitMode
                                )

                                frontPlacements.add(
                                    PagePlacement(
                                        sourcePageIndex = pageIdx,
                                        xPt = cellX + (cellWidth - transform.renderWidth) / 2f,
                                        yPt = cellY + (cellHeight - transform.renderHeight) / 2f,
                                        widthPt = transform.renderWidth,
                                        heightPt = transform.renderHeight,
                                        scaleX = transform.scaleX,
                                        scaleY = transform.scaleY
                                    )
                                )
                            }
                        }
                    }

                    sheetLayouts.add(
                        SheetLayout(
                            sheetIndex = pairIdx * 2,
                            widthPt = sheetWidth,
                            heightPt = sheetHeight,
                            marginTopPt = marginTopPt,
                            marginBottomPt = marginBottomPt,
                            marginLeftPt = marginLeftPt,
                            marginRightPt = marginRightPt,
                            gutterXPt = gutterXPt,
                            gutterYPt = gutterYPt,
                            placements = frontPlacements,
                            bleedPt = bleedPt,
                            showCropMarks = config.showCropMarks,
                            showRegistrationTargets = config.showRegistrationTargets,
                            showSafeZone = config.showSafeZones
                        )
                    )

                    // Back sheet (flipped column alignment)
                    val backPlacements = mutableListOf<PagePlacement>()
                    for (row in 0 until gridRows) {
                        for (col in 0 until gridCols) {
                            val mirroredCol = gridCols - 1 - col
                            val backPageIdx = frontStartPage + (row * gridCols + col) * 2 + 1

                            if (backPageIdx < pageCount) {
                                val cellX = marginLeftPt + mirroredCol * (cellWidth + gutterXPt)
                                val cellY = marginTopPt + row * (cellHeight + gutterYPt)
                                val transform = calculatePlacementTransform(
                                    sourceWidthPt, sourceHeightPt, cellWidth, cellHeight, config.fitMode
                                )

                                backPlacements.add(
                                    PagePlacement(
                                        sourcePageIndex = backPageIdx,
                                        xPt = cellX + (cellWidth - transform.renderWidth) / 2f,
                                        yPt = cellY + (cellHeight - transform.renderHeight) / 2f,
                                        widthPt = transform.renderWidth,
                                        heightPt = transform.renderHeight,
                                        scaleX = transform.scaleX,
                                        scaleY = transform.scaleY
                                    )
                                )
                            }
                        }
                    }

                    sheetLayouts.add(
                        SheetLayout(
                            sheetIndex = pairIdx * 2 + 1,
                            widthPt = sheetWidth,
                            heightPt = sheetHeight,
                            marginTopPt = marginTopPt,
                            marginBottomPt = marginBottomPt,
                            marginLeftPt = marginLeftPt,
                            marginRightPt = marginRightPt,
                            gutterXPt = gutterXPt,
                            gutterYPt = gutterYPt,
                            placements = backPlacements,
                            bleedPt = bleedPt,
                            showCropMarks = config.showCropMarks,
                            showRegistrationTargets = config.showRegistrationTargets,
                            showSafeZone = config.showSafeZones
                        )
                    )

                    currentPage += cardsPerSheet * 2
                }
            }
        }

        return sheetLayouts
    }

    // -------------------------------------------------------------------------
    // 4. CROP & RESIZE ENGINE
    // -------------------------------------------------------------------------

    private fun calculateCropResizeLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        val sheetWidth = config.targetPaperSize.width(config.isLandscape)
        val sheetHeight = config.targetPaperSize.height(config.isLandscape)

        val marginTopPt = PaperSize.mmToPoints(config.marginTopMm)
        val marginBottomPt = PaperSize.mmToPoints(config.marginBottomMm)
        val marginLeftPt = PaperSize.mmToPoints(config.marginLeftMm)
        val marginRightPt = PaperSize.mmToPoints(config.marginRightMm)
        val bleedPt = PaperSize.mmToPoints(config.bleedMm)

        val availW = sheetWidth - marginLeftPt - marginRightPt
        val availH = sheetHeight - marginTopPt - marginBottomPt

        val crop = config.cropBox
        val effectiveSourceWidth = sourceWidthPt * (1f - crop.leftPct - crop.rightPct)
        val effectiveSourceHeight = sourceHeightPt * (1f - crop.topPct - crop.bottomPct)

        val transform = calculatePlacementTransform(
            sourceWidth = effectiveSourceWidth,
            sourceHeight = effectiveSourceHeight,
            cellWidth = availW,
            cellHeight = availH,
            fitMode = config.fitMode
        )

        val px = marginLeftPt + (availW - transform.renderWidth) / 2f
        val py = marginTopPt + (availH - transform.renderHeight) / 2f

        val sheetLayouts = mutableListOf<SheetLayout>()

        for (pageIdx in 0 until pageCount) {
            sheetLayouts.add(
                SheetLayout(
                    sheetIndex = pageIdx,
                    widthPt = sheetWidth,
                    heightPt = sheetHeight,
                    marginTopPt = marginTopPt,
                    marginBottomPt = marginBottomPt,
                    marginLeftPt = marginLeftPt,
                    marginRightPt = marginRightPt,
                    placements = listOf(
                        PagePlacement(
                            sourcePageIndex = pageIdx,
                            xPt = px,
                            yPt = py,
                            widthPt = transform.renderWidth,
                            heightPt = transform.renderHeight,
                            scaleX = transform.scaleX,
                            scaleY = transform.scaleY,
                            cropBox = crop
                        )
                    ),
                    bleedPt = bleedPt,
                    showCropMarks = config.showCropMarks,
                    showRegistrationTargets = config.showRegistrationTargets,
                    showSafeZone = config.showSafeZones
                )
            )
        }

        return sheetLayouts
    }

    // -------------------------------------------------------------------------
    // 5. BLEED & REGISTRATION MARKS ENGINE
    // -------------------------------------------------------------------------

    private fun calculateBleedAndMarksLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        val bleedPt = PaperSize.mmToPoints(config.bleedMm)
        val markMarginPt = PaperSize.mmToPoints(15f)

        val sheetWidth = sourceWidthPt + (bleedPt + markMarginPt) * 2f
        val sheetHeight = sourceHeightPt + (bleedPt + markMarginPt) * 2f

        val px = bleedPt + markMarginPt
        val py = bleedPt + markMarginPt

        val sheetLayouts = mutableListOf<SheetLayout>()

        for (pageIdx in 0 until pageCount) {
            sheetLayouts.add(
                SheetLayout(
                    sheetIndex = pageIdx,
                    widthPt = sheetWidth,
                    heightPt = sheetHeight,
                    marginTopPt = py,
                    marginBottomPt = py,
                    marginLeftPt = px,
                    marginRightPt = px,
                    placements = listOf(
                        PagePlacement(
                            sourcePageIndex = pageIdx,
                            xPt = px,
                            yPt = py,
                            widthPt = sourceWidthPt,
                            heightPt = sourceHeightPt,
                            scaleX = 1f,
                            scaleY = 1f
                        )
                    ),
                    bleedPt = bleedPt,
                    showCropMarks = config.showCropMarks,
                    showRegistrationTargets = config.showRegistrationTargets,
                    showSafeZone = config.showSafeZones
                )
            )
        }

        return sheetLayouts
    }

    // -------------------------------------------------------------------------
    // 6. ZINE GENERATOR ENGINE
    // -------------------------------------------------------------------------

    private fun calculateZineLayout(
        pageCount: Int,
        sourceWidthPt: Float,
        sourceHeightPt: Float,
        config: ImpositionConfig
    ): List<SheetLayout> {
        return when (config.zineType) {
            ZineType.ONE_SHEET_8PAGE -> {
                val sheetWidth = config.targetPaperSize.width(isLandscape = true)
                val sheetHeight = config.targetPaperSize.height(isLandscape = true)

                val marginLeftPt = PaperSize.mmToPoints(5f)
                val marginRightPt = PaperSize.mmToPoints(5f)
                val marginTopPt = PaperSize.mmToPoints(5f)
                val marginBottomPt = PaperSize.mmToPoints(5f)

                val availW = sheetWidth - marginLeftPt - marginRightPt
                val availH = sheetHeight - marginTopPt - marginBottomPt

                val cellWidth = availW / 4f
                val cellHeight = availH / 2f

                val pageOrderTop = listOf(7, 6, 5, 4)
                val pageOrderBottom = listOf(0, 1, 2, 3)

                val totalSheets = ceil(pageCount.toDouble() / 8.0).toInt()
                val sheetLayouts = mutableListOf<SheetLayout>()

                for (sheetIdx in 0 until totalSheets) {
                    val sheetOffset = sheetIdx * 8
                    val placements = mutableListOf<PagePlacement>()

                    // Top Row
                    for (col in 0 until 4) {
                        val sourceIdx = sheetOffset + pageOrderTop[col]
                        val px = marginLeftPt + col * cellWidth
                        val py = marginTopPt

                        val transform = calculatePlacementTransform(sourceWidthPt, sourceHeightPt, cellWidth, cellHeight, FitMode.FIT)

                        placements.add(
                            PagePlacement(
                                sourcePageIndex = if (sourceIdx < pageCount) sourceIdx else -1,
                                xPt = px + (cellWidth - transform.renderWidth) / 2f,
                                yPt = py + (cellHeight - transform.renderHeight) / 2f,
                                widthPt = transform.renderWidth,
                                heightPt = transform.renderHeight,
                                rotationDegrees = 180f,
                                scaleX = transform.scaleX,
                                scaleY = transform.scaleY
                            )
                        )
                    }

                    // Bottom Row
                    for (col in 0 until 4) {
                        val sourceIdx = sheetOffset + pageOrderBottom[col]
                        val px = marginLeftPt + col * cellWidth
                        val py = marginTopPt + cellHeight

                        val transform = calculatePlacementTransform(sourceWidthPt, sourceHeightPt, cellWidth, cellHeight, FitMode.FIT)

                        placements.add(
                            PagePlacement(
                                sourcePageIndex = if (sourceIdx < pageCount) sourceIdx else -1,
                                xPt = px + (cellWidth - transform.renderWidth) / 2f,
                                yPt = py + (cellHeight - transform.renderHeight) / 2f,
                                widthPt = transform.renderWidth,
                                heightPt = transform.renderHeight,
                                rotationDegrees = 0f,
                                scaleX = transform.scaleX,
                                scaleY = transform.scaleY
                            )
                        )
                    }

                    sheetLayouts.add(
                        SheetLayout(
                            sheetIndex = sheetIdx,
                            widthPt = sheetWidth,
                            heightPt = sheetHeight,
                            marginTopPt = marginTopPt,
                            marginBottomPt = marginBottomPt,
                            marginLeftPt = marginLeftPt,
                            marginRightPt = marginRightPt,
                            placements = placements,
                            bleedPt = PaperSize.mmToPoints(config.bleedMm),
                            showCropMarks = true,
                            showRegistrationTargets = false,
                            showSafeZone = true
                        )
                    )
                }

                sheetLayouts
            }

            ZineType.CUT_STACK_FOLD -> {
                calculateBookletLayout(pageCount, sourceWidthPt, sourceHeightPt, config)
            }
        }
    }

    // -------------------------------------------------------------------------
    // HELPER MATRIX & FIT TRANSFORM CALCULATOR
    // -------------------------------------------------------------------------

    data class PlacementTransform(
        val renderWidth: Float,
        val renderHeight: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    private fun calculatePlacementTransform(
        sourceWidth: Float,
        sourceHeight: Float,
        cellWidth: Float,
        cellHeight: Float,
        fitMode: FitMode
    ): PlacementTransform {
        if (sourceWidth <= 0f || sourceHeight <= 0f) {
            return PlacementTransform(cellWidth, cellHeight, 1f, 1f)
        }

        val scaleXFit = cellWidth / sourceWidth
        val scaleYFit = cellHeight / sourceHeight

        return when (fitMode) {
            FitMode.FIT -> {
                val scale = minOf(scaleXFit, scaleYFit)
                PlacementTransform(
                    renderWidth = sourceWidth * scale,
                    renderHeight = sourceHeight * scale,
                    scaleX = scale,
                    scaleY = scale
                )
            }

            FitMode.FILL -> {
                val scale = maxOf(scaleXFit, scaleYFit)
                PlacementTransform(
                    renderWidth = sourceWidth * scale,
                    renderHeight = sourceHeight * scale,
                    scaleX = scale,
                    scaleY = scale
                )
            }

            FitMode.STRETCH -> {
                PlacementTransform(
                    renderWidth = cellWidth,
                    renderHeight = cellHeight,
                    scaleX = scaleXFit,
                    scaleY = scaleYFit
                )
            }

            FitMode.CENTER -> {
                PlacementTransform(
                    renderWidth = sourceWidth,
                    renderHeight = sourceHeight,
                    scaleX = 1f,
                    scaleY = 1f
                )
            }
        }
    }
}
