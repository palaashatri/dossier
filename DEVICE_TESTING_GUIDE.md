# Device Testing Guide for Dossier

**Date**: 2026-07-20  
**Target**: Real Android Device (phone or tablet with API 26+)  
**Prerequisites**: Android SDK tools, USB debugging enabled  
**Expected Duration**: 1-2 hours for full end-to-end test

---

## Overview

The Dossier app has been built and unit-tested successfully, but a **critical navigation bug** prevents end-to-end testing on the emulator. This guide walks through testing on a real device and capturing logcat output to diagnose the bug.

**Current Status**:
- ✅ Build passes
- ✅ 126/126 unit tests pass
- ✅ Code compiles with no errors
- ❌ Scan→Report navigation fails on emulator (loops back to Identity)
- ❌ Cannot test Report, Entity Graph, Case Comparison, Face Embedding

---

## Pre-Device Setup

### 1. Connect Device

```bash
# Verify device is connected
adb devices

# Expected output:
# 3a2d4f8e1b2c3d4e    device  (or "emulator-5554" for Genymotion, etc.)
```

### 2. Install App

```bash
# From repo root:
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./gradlew :app:installDebug

# Or manually:
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 3. Clear App Data (First Run)

```bash
adb shell pm clear io.dossier.app
```

---

## Test Flow (Step-by-Step)

### Phase 1: Consent → Identity (UI Smoke Test)

1. **Start app** and let it load (should show Consent screen)
   ```bash
   adb shell am start -n io.dossier.app/.MainActivity
   ```
   - ✅ Expected: Consent screen with "Accept & Start" button

2. **Accept consent** (tap the button)
   - ✅ Expected: Navigate to Identity screen (3-step form)

3. **Fill in identity** (Step 1/3)
   - Enter a name (e.g., "Palaash Atri")
   - Tap "Continue"
   - ✅ Expected: Navigate to Step 2 (optional signals: aliases, emails, phones, etc.)

4. **Skip Step 2** (optional signals)
   - Leave empty or enter optional data
   - Tap "Continue"
   - ✅ Expected: Navigate to Step 3 (profile URLs, selfie)

5. **Skip Step 3** (profile URLs, selfie)
   - Leave empty
   - Tap "Continue"
   - ✅ Expected: Navigate to Username Discovery screen

### Phase 2: Username Discovery → Scan

6. **Username Discovery Screen**
   - Should show variants of the name you entered
   - Can select/deselect variants
   - Tap "Next" or "Start Scan"
   - ✅ Expected: Navigate to Scan screen

### Phase 3: Scan (Core Bug Testing)

7. **Scan Screen**
   - Will start automatically
   - Watch the live log (shows: "Resolving variants", "Checking profiles", "Face comparison", "Breach exposure", etc.)
   - Scan will take 30-60 seconds depending on device/network
   - ✅ Expected: Progress indicator, live log entries appear as scan stages complete

8. **Scan Completes** ← **CRITICAL OBSERVATION POINT**
   - Screen should show "Compiling report" text
   - Live log shows "Scan complete."
   - **THIS IS WHERE THE BUG OCCURS** ← See Phase 4

### Phase 4: Post-Scan Navigation (Bug Diagnosis)

**What SHOULD happen**:
- After scan completes, app should navigate to Report screen
- Report should show: findings, entity graph, risk level, remediation tips, AI summary

**What CURRENTLY happens on emulator**:
- App returns to Identity screen instead of Report
- All report data is lost
- Cannot access any downstream features

**On Device, Capture Logcat**:

```bash
# Before starting scan, clear logcat:
adb logcat -c

# Run the scan flow (steps 1-8 above)

# After scan completes, wait 5 seconds, then capture:
adb logcat -d > /tmp/dossier_scan_logcat.txt

# Look for these key log entries:
grep "ScanScreen\|MainHub\|Navigat" /tmp/dossier_scan_logcat.txt | tail -20
```

**Expected log output (if fix works)**:
```
ScanScreen: Navigating to report (isScanning=false, hasStarted=true)
MainHub: ScanScreen.onScanComplete() called, navigating to report
MainHub: Current back stack: scan
MainHub: Navigation to report succeeded
```

**If bug occurs** (navigation fails):
```
ScanScreen: Navigating to report (isScanning=false, hasStarted=true)
MainHub: ScanScreen.onScanComplete() called, navigating to report
MainHub: Navigation to report failed: <error message>
```

OR (if onScanComplete never called):
```
(no "MainHub" logs at all)
(Screen shows Identity instead of Report)
```

---

## Screenshots to Capture (For README Update)

If you reach the Report screen, take screenshots of:

1. **Report Screen** (main view)
   - Shows: Risk level, exposure scores, key findings list

2. **Entity Graph Tab** (on Report)
   - Interactive node-link graph showing person → profiles → breaches → PII
   - Colored nodes by entity type

3. **Breach Section** (on Report)
   - Lists breaches found during scan
   - Shows affected platforms, exposure details

4. **Remediation Tips** (on Report)
   - Problem/Evidence/Risk/Fix/Impact cards

5. **Bottom Navigation Tabs** (from any Report-level screen)
   - Show: Dossier, Image Lookup, Breach, Cases, Models
   - Tap each to verify tab behavior

6. **Cases Tab** (if time allows)
   - Should show saved scan history
   - Can select two cases to compare

7. **Models Tab** (if time allows)
   - Gemini Nano status (should say "available" or show download prompt)
   - Face calibration section
   - Remote AI provider configuration

---

## What to Test (If Report Screen Reached)

### Core Functionality
- [ ] Report loads and doesn't crash
- [ ] Entity graph renders (nodes visible, edges visible)
- [ ] Clicking/tapping entities highlights related evidence
- [ ] Scroll through report sections without crashing
- [ ] Risk level color matches (Low=green, Medium/High=orange, Critical=red)
- [ ] Export buttons work ("Plain Text", "JSON", "Share")

### Features to Verify
- [ ] Face consistency section shows avatar comparison (if face model loaded)
- [ ] Breach section lists any found breaches
- [ ] PII extraction section shows sensitive data found
- [ ] AI summary section shows summary text or baseline narrative
- [ ] Remediation tips have actionable advice

### Bottom Tabs
- [ ] Image Lookup tab works (can search images)
- [ ] Breach tab works (can enter HIBP key or email)
- [ ] Cases tab works (shows scan history)
- [ ] Models tab works (shows face calibration, AI provider config)

---

## Capturing Full Test Session

For debugging, capture the entire logcat session:

```bash
# Terminal 1: Start recording logcat
adb logcat -v threadtime > dossier_full_session.log &
LOGCAT_PID=$!

# Terminal 2: Run the test flow (steps 1-8 above)
# ... run through complete flow, take screenshots ...

# Terminal 1: Stop logcat after you're done
kill $LOGCAT_PID

# This file now contains every log line with exact timestamps
# Useful for identifying what happened and when
```

---

## If Navigation Bug Occurs (Logs Show Failure)

### Next Steps to Diagnose

1. **Check Back Stack State**
   - The log line shows: `Current back stack: <route>`
   - If it says "identity" instead of "scan", something is resetting the navigation state

2. **Check for Hidden Pop**
   - Search logcat for "popBackStack" or "pop up to"
   - See if something is explicitly popping the stack after navigation

3. **Check NavController State**
   - Look for "Navigation" errors or "route not found"
   - Verify the "report" composable exists and is reachable

4. **Check if onScanComplete is Called**
   - If "ScanScreen: Navigating..." doesn't appear in logs
   - Then the callback isn't firing (timing issue in ScanScreen.kt)

### File Locations for Investigation

- **ScanScreen navigation logic**: `app/src/main/java/io/dossier/app/ui/screens/ScanScreen.kt` lines 94-102
- **MainHubScreen navigation setup**: `app/src/main/java/io/dossier/app/ui/screens/MainHubScreen.kt` lines 149-185
- **DossierNavGraph routes**: `app/src/main/java/io/dossier/app/ui/screens/MainHubScreen.kt` lines 153-184

---

## Reference: Test Checklist

```
PRE-DEVICE:
  [ ] APK built: ./gradlew :app:assembleDebug
  [ ] APK installed: adb install -r -g <path-to-apk>
  [ ] Device connected: adb devices

PHASE 1: Smoke Test
  [ ] App launches
  [ ] Consent screen visible
  [ ] Accept consent button works
  [ ] Navigate to Identity

PHASE 2: Identity → Discovery
  [ ] Fill in Step 1 (name)
  [ ] Continue to Step 2
  [ ] Skip Step 2
  [ ] Continue to Step 3
  [ ] Skip Step 3
  [ ] Navigate to Username Discovery

PHASE 3: Discovery → Scan
  [ ] Username Discovery screen visible
  [ ] Variants shown
  [ ] Can proceed to Scan

PHASE 4: Scan Execution
  [ ] Scan starts
  [ ] Live log shows progress
  [ ] Scan stages appear (discovery, compare, breach, entity, compile, AI)
  [ ] Wait for "Scan complete" message

PHASE 5: Post-Scan Navigation (BUG TEST)
  [ ] Check logcat: `adb logcat -d | grep "ScanScreen\|MainHub"`
  [ ] Expected: "Navigation to report succeeded" or actual screen is Report
  [ ] Actual: ??
  [ ] Document exact behavior

IF SUCCESSFUL (Report Screen):
  [ ] Report loads without crash
  [ ] Entity graph visible
  [ ] Risk level displayed
  [ ] Findings listed
  [ ] Breach info shown
  [ ] AI summary visible
  [ ] Export buttons work
  [ ] Bottom tabs functional

CLEANUP:
  [ ] Logcat captured: adb logcat -d > /tmp/session.log
  [ ] Screenshots saved
  [ ] Findings documented
```

---

## Contact & Questions

If the navigation bug manifests on device differently than expected, or if there are blockers, share:
1. **Logcat output** (`adb logcat -d`)
2. **Screenshots** of the screen state when bug occurs
3. **Device info** (`adb shell getprop ro.build.fingerprint`)
4. **Exact reproduction steps** (which buttons you tapped, what you entered)

---

**Ready to test?** Start with Phase 1 above. Good luck! 🚀
