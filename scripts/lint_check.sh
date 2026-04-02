#!/bin/bash
set -e

echo "========================================="
echo "PDF Toolkit Lint Check"
echo "========================================="

echo "Running Android Lint..."
./gradlew lintFdroidDebug --continue

echo ""
echo "Lint complete. Report at:"
echo "  app/build/reports/lint-results-fdroidDebug.html"
echo ""

# Check for fatal errors in lint report
if grep -q "severity=\"Fatal\"" app/build/reports/lint-results-fdroidDebug.xml 2>/dev/null; then
    echo "ERROR: Fatal lint issues found!"
    exit 1
fi

echo "Lint check passed."
