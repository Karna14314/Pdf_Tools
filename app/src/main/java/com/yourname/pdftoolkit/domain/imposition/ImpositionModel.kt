package com.yourname.pdftoolkit.domain.imposition

/**
 * Standard paper sizes used for virtual sheet layout calculation.
 * Dimensions are stored in PDF points (1/72 inch).
 */
enum class PaperPreset(val displayName: String, val widthPt: Float, val heightPt: Float) {
    A3("A3 (297 x 420 mm)", 841.89f, 1190.55f),
    A4("A4 (210 x 297 mm)", 595.28f, 841.89f),
    A5("A5 (148 x 210 mm)", 420.94f, 595.28f),
    LETTER("US Letter (8.5 x 11 in)", 612.00f, 792.00f),
    LEGAL("US Legal (8.5 x 14 in)", 612.00f, 1008.00f),
    TABLOID("Tabloid (11 x 17 in)", 792.00f, 1224.00f),
    CUSTOM("Custom", 595.28f, 841.89f)
}

data class PaperSize(
    val preset: PaperPreset = PaperPreset.A4,
    val customWidthPt: Float = 595.28f,
    val customHeightPt: Float = 841.89f
) {
    fun width(isLandscape: Boolean): Float {
        val baseWidth = if (preset == PaperPreset.CUSTOM) customWidthPt else preset.widthPt
        val baseHeight = if (preset == PaperPreset.CUSTOM) customHeightPt else preset.heightPt
        return if (isLandscape) maxOf(baseWidth, baseHeight) else minOf(baseWidth, baseHeight)
    }

    fun height(isLandscape: Boolean): Float {
        val baseWidth = if (preset == PaperPreset.CUSTOM) customWidthPt else preset.widthPt
        val baseHeight = if (preset == PaperPreset.CUSTOM) customHeightPt else preset.heightPt
        return if (isLandscape) minOf(baseWidth, baseHeight) else maxOf(baseWidth, baseHeight)
    }

    companion object {
        fun mmToPoints(mm: Float): Float = mm * 72f / 25.4f
        fun pointsToMm(pt: Float): Float = pt * 25.4f / 72f
    }
}

enum class FitMode(val displayName: String) {
    FIT("Fit (Maintain Aspect Ratio)"),
    FILL("Fill (Crop to Fit)"),
    STRETCH("Stretch (Ignore Aspect Ratio)"),
    CENTER("Center (Original Size)")
}

enum class BindingDirection(val displayName: String, val description: String) {
    LEFT_WESTERN("Left Binding", "Standard Western books & magazines"),
    RIGHT_RTL("Right Binding", "Arabic, Hebrew, Persian (RTL) books"),
    TOP_CALENDAR("Top Binding", "Calendars, flip pads, report covers"),
    BOTTOM_FLIPBOOK("Bottom Binding", "Flip books & bottom-hinged documents")
}

enum class CardMode(val displayName: String) {
    REPEAT("Repeat Single Design"),
    SEQUENTIAL("Sequential Pages"),
    DUPLEX("Duplex (Front & Back Alignment)")
}

enum class BleedMethod(val displayName: String) {
    EXTEND_EDGE("Extend Edge Pixels"),
    MIRROR_EDGE("Mirror Edge Boundaries"),
    WHITE_BORDER("White Border Padding")
}

enum class ImpositionToolMode(val displayName: String) {
    N_UP("N-Up Layout"),
    BOOKLET("Booklet (Saddle Stitch)"),
    CARDS("Cards & Flashcards"),
    CROP_RESIZE("Crop & Resize"),
    BLEED_GENERATOR("Bleed Generator"),
    REGISTRATION_MARKS("Registration & Marks"),
    ZINE("Zine Generator")
}

enum class ZineType(val displayName: String, val description: String) {
    ONE_SHEET_8PAGE("8-Page Mini-Zine (1 Sheet)", "Folded 8-page layout from 1 page sheet with center slit"),
    CUT_STACK_FOLD("Cut-Stack-Fold Zine", "Multi-sheet stacked and folded zine layout")
}

/**
 * Bounds defining crop margins normalized from 0.0 to 1.0.
 */
data class NormalizedCropBox(
    val leftPct: Float = 0f,
    val topPct: Float = 0f,
    val rightPct: Float = 0f,
    val bottomPct: Float = 0f
)

/**
 * Representation of a single source page placed on a virtual target sheet.
 */
data class PagePlacement(
    val sourcePageIndex: Int, // -1 represents an automatically inserted blank page
    val xPt: Float,
    val yPt: Float,
    val widthPt: Float,
    val heightPt: Float,
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val cropBox: NormalizedCropBox? = null,
    val isFlippedHorizontally: Boolean = false,
    val isFlippedVertically: Boolean = false
)

/**
 * Representation of a virtual sheet containing multiple page placements.
 */
data class SheetLayout(
    val sheetIndex: Int,
    val widthPt: Float,
    val heightPt: Float,
    val marginTopPt: Float = 0f,
    val marginBottomPt: Float = 0f,
    val marginLeftPt: Float = 0f,
    val marginRightPt: Float = 0f,
    val gutterXPt: Float = 0f,
    val gutterYPt: Float = 0f,
    val placements: List<PagePlacement> = emptyList(),
    val bleedPt: Float = 0f,
    val showCropMarks: Boolean = false,
    val showRegistrationTargets: Boolean = false,
    val showSafeZone: Boolean = false
)

/**
 * Comprehensive configuration settings for all imposition operations.
 */
data class ImpositionConfig(
    val mode: ImpositionToolMode = ImpositionToolMode.N_UP,
    val targetPaperSize: PaperSize = PaperSize(PaperPreset.A4),
    val isLandscape: Boolean = false,
    val fitMode: FitMode = FitMode.FIT,

    // N-Up & Grid settings
    val gridRows: Int = 2,
    val gridCols: Int = 2,
    val marginTopMm: Float = 10f,
    val marginBottomMm: Float = 10f,
    val marginLeftMm: Float = 10f,
    val marginRightMm: Float = 10f,
    val gutterXMm: Float = 5f,
    val gutterYMm: Float = 5f,

    // Booklet settings
    val bindingDirection: BindingDirection = BindingDirection.LEFT_WESTERN,
    val signatureSize: Int = 0, // 0 = single signature (all pages in 1 booklet)
    val autoBlankPages: Boolean = true,

    // Card & Flashcard settings
    val cardMode: CardMode = CardMode.SEQUENTIAL,
    val cardRepeatCount: Int = 1,

    // Crop & Resize settings
    val cropBox: NormalizedCropBox = NormalizedCropBox(),

    // Bleeds & Marks settings
    val bleedMm: Float = 3f,
    val bleedMethod: BleedMethod = BleedMethod.WHITE_BORDER,
    val showCropMarks: Boolean = true,
    val showRegistrationTargets: Boolean = false,
    val showSafeZones: Boolean = false,

    // Zine settings
    val zineType: ZineType = ZineType.ONE_SHEET_8PAGE
)
