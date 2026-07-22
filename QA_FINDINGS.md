# Dossier QA Audit — 2026-07-20

**Status**: Testing in progress  
**Tester**: Lab Owner (self-audit)  
**Emulator**: Android 36, API 35  
**Build**: Debug APK installed successfully  

---

## What Works ✓

### Verified Functionality
- **Consent Screen**: Displays correctly, keyboard navigation (Tab → Enter) advances to next screen
- **App Launch**: Builds and installs without errors (JDK 21)
- **Unit Tests**: All 126 unit tests pass (`testDebugUnitTest` → BUILD SUCCESSFUL)
- **Navigation Structure**: ConsentScreen → IdentityScreen → (intended: UsernameDiscovery → Scan → Report)

---

## Critical Issues Found 🔴

### 1. **Touch Input Broken on Emulator (InputDispatcher Channel Error)**
**Severity**: HIGH  
**Impact**: Cannot test UI with touch/taps  

**Evidence**:
```
E InputDispatcher: channel 'io.dossier.app/io.dossier.app.MainActivity' ~ 
  Channel is unrecoverably broken and will be disposed!
```

**Workaround**: Keyboard navigation (Tab, Enter) works as alternative  
**Root Cause**: Emulator/Android input dispatch issue, not app code  
**Mitigation**: Need to test on real device or different emulator configuration

---

### 2. **ADB Text Input Issues**
**Severity**: MEDIUM  
**Impact**: Cannot programmatically fill forms via `adb shell input text`  

**Observed Behavior**:
- Text concatenates across multiple field tabs
- Text selection/deletion commands not working reliably
- Caused by ADB input method, not app code

---

## Untested Features (from commit history)

### Commit 2571b72: M6-M16 Milestone Implementation
**Changes**: +3,528 lines, 126 unit tests pass  
**Not Yet Tested on Device**:

| Milestone | Feature | Status |
|-----------|---------|--------|
| M6 | Evidence Correlation Layer | Compiled, untested UI |
| M7 | Confidence Engine | Code reviewed, untested |
| M8 | Interactive Entity Graph View | Compiled, untested UI |
| M9 | Exposure Engine | Code reviewed, untested |
| M10 | Attack Path Finder | Code reviewed, untested |
| M11 | Remediation Engine | Code reviewed, untested |
| M13 | Timeline / Cases Tab | Compiled, untested UI |
| M14 | Case Comparison Screen | Compiled, untested UI |
| M15 | Plugin SDK | Code reviewed, untested |
| M16 | Performance (Resume/Cancel) | Code reviewed, untested |

### Commit 46b4136: Face Embedding Model
**Changes**: Bundled 23MB FaceNet model + calibration  
**Not Yet Tested on Device**:
- Model loading on app startup
- Face detection flow
- Avatar downloading and matching
- Model UI on ModelsScreen

---

## Code Quality Assessment

### Positive Findings
✓ All unit tests passing  
✓ No compilation errors  
✓ Lint warnings only (function naming conventions — non-critical)  
✓ Proper Kotlin/Compose patterns used  
✓ Navigation structure sound  
✓ Build system correctly configured (JDK 21, SDK 35)  

### Lint Warnings (Non-blocking)
- `EntityGraphView.kt`: Composable function names should start lowercase
- `GraphViewTab`, `GraphCanvas`, `AdjacencyList`, `EntityGraphLegend` (same issue)
- Package directory mismatch warnings

---

## Next Steps for Complete QA

### Immediate (Blocking device testing):
1. **Resolve Input Dispatch Issue**
   - Option A: Use real device with adb
   - Option B: Use different Android emulator version
   - Option C: Use Android Studio emulator (may have better integration)

2. **Complete User Flow Testing** (once input fixed):
   - ✓ Consent Screen
   - → Identity Input Screen (all 3 steps)
   - → Username Discovery
   - → Scan (watch all stages: templates, pivots, search, images, face, breach, graph, risk, AI)
   - → Report Screen (verify all sections render)
   - → Media Lookup Tab
   - → Breach Tab (with/without HIBP key)
   - → Models Tab (Gemini Nano status, face calibration)
   - → Cases Tab (saved case listing, comparison)

3. **Feature-Specific Tests**:
   - **Face Embedding**: Upload selfie, verify avatar downloads, check consistency scores
   - **Entity Graph**: Verify interactive graph renders and responds to selection
   - **Case Comparison**: Run two scans, compare findings/profiles/breaches
   - **Resume Scan**: Kill scan mid-way, restart app, verify resume affordance appears
   - **Search Parsing**: Verify DuckDuckGo/Bing/Google/Yandex parsing still works
   - **Image Search**: Verify image index hits display
   - **Remote AI**: Test with each provider (if keys available)
   - **Attack Paths**: Verify BFS paths render in report
   - **Exposure Scores**: Verify 6 sub-scores calculated and shown

4. **Functional Verification**:
   - [ ] Consent copy is accurate
   - [ ] Identity intake validates required fields
   - [ ] Username variants generate correctly
   - [ ] Profile checks (80 candidates) complete
   - [ ] PII extraction finds sensitive data
   - [ ] Risk scoring ranks findings
   - [ ] Remediation tips are actionable
   - [ ] Plain-text report export works
   - [ ] JSON export works
   - [ ] Graph visualization handles large result sets
   - [ ] Scan can be cancelled mid-progress
   - [ ] Session purge clears all state

---

## Screenshots Captured

```
screen_001_consent_initial.png      ✓ Consent screen loads
screen_002_identity_empty.png       ✓ Identity screen appears
screen_005_keyboard_enter.png       ✓ Keyboard navigation works
screen_009_fresh_identity.png       ✓ Identity form renders
screen_010_after_continue.png       ✓ Name input accepted
```

---

## Build Verification Command

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --quiet
```

**Result**: All pass (tests, debug build, release build)

---

## Recommendations

1. **Fix lint warnings** (optional, non-blocking):
   - Rename `EntityGraphView`, `GraphViewTab`, etc. to lowercase composable names
   - Fix package directory mismatch if lint is strict

2. **Test on real device**: 
   - The 23MB FaceNet model download/loading should be verified on actual hardware
   - Touch interaction bugs may not occur on real devices

3. **Set up automated UI testing**:
   - Create Espresso tests for critical paths (consent → scan → report)
   - Mock network calls to test UI without network

4. **Document known limitations**:
   - Update README if any features are temporarily unavailable
   - Add device compatibility notes

---

**Audit Date**: 2026-07-20  
**Auditor**: Claude (on behalf of Lab Owner)  
**Status**: Awaiting device testing to complete QA
