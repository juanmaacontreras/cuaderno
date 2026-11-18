#!/usr/bin/env bash
# Draws the app's real screens to PNG without a device (Robolectric).
# Usage: tools/screens.sh [OUT_DIR]   (default: build/screens)
set -euo pipefail
cd "$(dirname "$0")/.."

CUADERNO_SCREENS=$(realpath -m "${1:-build/screens}")
export CUADERNO_SCREENS
gradle --no-daemon -q :app:testDebugUnitTest --tests 'com.cuaderno.app.ScreensTest' --tests 'com.cuaderno.app.NotebooksDesignTest'
ls "$CUADERNO_SCREENS"
