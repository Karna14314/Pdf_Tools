#!/bin/bash
set -e
ERRORS=0
WARNINGS=0

echo "========================================="
echo "PDF Toolkit Static Analysis"
echo "========================================="

# Check 1: Bitmap recycle guard
echo "Checking bitmap recycle guards..."
# Find Canvas(bitmap) constructions without isRecycled check nearby
grep -rn "Canvas(bitmap\|Canvas(bmp\|Canvas(mBitmap" app/src/main --include="*.kt" | while read line; do
    FILE=$(echo $line | cut -d: -f1)
    LINENUM=$(echo $line | cut -d: -f2)
    # Check if isRecycled appears within 5 lines before
    START=$((LINENUM - 5))
    if ! sed -n "${START},${LINENUM}p" $FILE | grep -q "isRecycled"; then
        echo "  WARNING: Canvas construction without recycle guard at $FILE:$LINENUM"
        WARNINGS=$((WARNINGS + 1))
    fi
done

# Check 2: OOM risk — Bitmap.createBitmap in loops
echo "Checking for Bitmap creation in loops..."
grep -rn "Bitmap.createBitmap" app/src/main --include="*.kt" -l | while read file; do
    if grep -n "Bitmap.createBitmap" $file | head -1; then
        echo "  INFO: Bitmap created in $file — verify it is recycled after use"
    fi
done

# Check 3: Missing bitmap.recycle() after use
echo "Checking for unrecycled bitmaps..."
FILES_WITH_BITMAP=$(grep -rln "Bitmap.createBitmap\|BitmapFactory.decode" app/src/main --include="*.kt")
for file in $FILES_WITH_BITMAP; do
    if ! grep -q "\.recycle()" $file; then
        echo "  WARNING: $file creates bitmaps but never calls recycle()"
        WARNINGS=$((WARNINGS + 1))
    fi
done

# Check 4: Main thread IO operations
echo "Checking for main thread IO..."
grep -rn "File(\|FileInputStream\|FileOutputStream\|BufferedReader" app/src/main --include="*.kt" | \
grep -v "//\|suspend\|withContext\|Dispatchers\|ViewModel\|Repository" | while read line; do
    echo "  WARNING: Possible main thread IO: $line"
    WARNINGS=$((WARNINGS + 1))
done

# Check 5: WebView on main thread
echo "Checking WebView usage..."
grep -rn "webView.loadUrl\|webView.loadData\|evaluateJavascript" app/src/main --include="*.kt" | while read line; do
    if ! echo $line | grep -q "Dispatchers.Main\|LaunchedEffect\|runOnUiThread"; then
        echo "  INFO: WebView call found — verify it runs on main thread: $line"
    fi
done

# Check 6: Missing null checks on PdfRenderer
echo "Checking PdfRenderer null safety..."
grep -rn "PdfRenderer\|openPage" app/src/main --include="*.kt" | while read line; do
    echo "  INFO: PdfRenderer usage: $line"
done

# Check 7: OutOfMemoryError not caught in heavy operations
echo "Checking OOM catch coverage..."
HEAVY_FILES=$(grep -rln "pdfToImages\|PdfSplitter\|PdfMerger\|PdfOcrProcessor" app/src/main --include="*.kt")
for file in $HEAVY_FILES; do
    if ! grep -q "OutOfMemoryError\|catch.*OOM\|catch.*Error" $file; then
        echo "  ERROR: $file does heavy PDF ops but does not catch OutOfMemoryError"
        ERRORS=$((ERRORS + 1))
    fi
done

# Check 8: Hardcoded file paths
echo "Checking for hardcoded file paths..."
grep -rn '"/sdcard/\|"/storage/\|"/data/data/' app/src/main --include="*.kt" | while read line; do
    echo "  ERROR: Hardcoded file path: $line"
    ERRORS=$((ERRORS + 1))
done

# Check 9: System.getenv for version (F-Droid incompatible)
echo "Checking version code safety..."
grep -rn "System.getenv.*VERSION\|System.getenv.*version" app/src/main --include="*.kt" | while read line; do
    echo "  ERROR: System.getenv for version — F-Droid incompatible: $line"
    ERRORS=$((ERRORS + 1))
done

# Check 10: PlayStore references in fdroid/opensource flavors
echo "Checking flavor isolation..."
grep -rn "play.google.com\|google.android.gms\|play.core" app/src/fdroid app/src/opensource 2>/dev/null --include="*.kt" | while read line; do
    echo "  ERROR: Play Store reference in FOSS flavor: $line"
    ERRORS=$((ERRORS + 1))
done

echo ""
echo "========================================="
echo "Results: $ERRORS errors, $WARNINGS warnings"
echo "========================================="

if [ $ERRORS -gt 0 ]; then
    echo "FAILED — fix errors before release"
    exit 1
else
    echo "PASSED — static analysis clean"
    exit 0
fi
