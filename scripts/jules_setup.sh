#!/bin/bash
# Jules AI Environment Setup Script for PDF Toolkit
# Run this first in every Jules session

set -e

echo "🔧 Starting Jules environment setup for PDF Toolkit..."

# Check if we're in the right directory
if [ ! -f "settings.gradle.kts" ]; then
    echo "❌ Error: Not in project root. Please run from pdf_tools directory."
    exit 1
fi

# 1. SDK and Tooling Check
echo "☕ Checking for Java 17..."
if ! command -v java &> /dev/null; then
    echo "Java not found. Installing OpenJDK 17..."
    apt-get update ; apt-get install -y openjdk-17-jdk
fi
java -version 2>&1 | head -1

# 2. Install Android CLI (Specialized for Agent Mode)
echo "📱 Installing Android CLI Specialist..."
if ! command -v android &> /dev/null; then
    curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
    # Add to PATH for current session
    export PATH="$PATH:/usr/local/bin"
fi

# 3. Initialize Agent Environment
echo "🔧 Initializing Android CLI..."
export PATH="$PATH:/usr/local/bin"
android init || true

# 4. Prepare Gradle and Build Cache
echo "🔨 Warming up Gradle build cache..."
chmod +x ./gradlew
./gradlew --version

# 5. Run initial build to populate cache
echo "🏗️ Running initial build (fdroid debug flavor)..."
./gradlew assembleFdroidDebug --no-daemon || true

# 6. Diagnostic Info
echo ""
echo "� Environment Setup Complete."
echo "================================"
android info 2>/dev/null || echo "Android CLI: Not fully initialized (expected on first run)"
java -version 2>&1 | head -1
./gradlew --version | head -3
echo "================================"
echo ""
echo "✅ Jules is ready for autonomous PDF Toolkit optimization!"
echo ""
echo "Next steps:"
echo "  1. Check AI_RUN_LOG.md for recent work"
echo "  2. Start weekly optimization task"
echo "  3. Run: ./gradlew assembleFdroidDebug"
