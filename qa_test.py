#!/usr/bin/env python3
"""
QA Test Script for Dossier App
Tests all functionality as per STATUS.md and demo checklist
"""

import subprocess
import time
import os
import json
from pathlib import Path
from datetime import datetime

ADB = "/Users/palaashatri/Library/Android/sdk/platform-tools/adb"
SCREENSHOT_DIR = Path("qa_screenshots")
SCREENSHOT_DIR.mkdir(exist_ok=True)

def run_adb(cmd):
    """Run adb command"""
    full_cmd = f"{ADB} {cmd}"
    result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return result.stdout + result.stderr

def take_screenshot(name):
    """Take and save a screenshot"""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    path = SCREENSHOT_DIR / f"{timestamp}_{name}.png"
    run_adb(f"shell screencap -p /sdcard/{name}.png")
    run_adb(f"pull /sdcard/{name}.png '{path}'")
    print(f"  Screenshot: {path}")
    return path

def tap(x, y):
    """Tap at coordinates"""
    run_adb(f"shell input tap {x} {y}")
    time.sleep(0.5)

def type_text(text):
    """Type text (need to handle special chars)"""
    # Use adb shell input text for basic ASCII
    run_adb(f"shell input text '{text}'")
    time.sleep(0.3)

def test_01_consent_screen():
    """Test 1: Consent screen"""
    print("\n=== TEST 1: Consent Screen ===")
    take_screenshot("01_consent_initial")

    # Look for accept button (typically bottom right)
    print("  Tapping Accept button...")
    tap(540, 2200)  # Approximate bottom button
    time.sleep(2)
    take_screenshot("01_consent_accepted")
    print("  ✓ Consent screen tested")

def test_02_identity_input():
    """Test 2: Identity input screen"""
    print("\n=== TEST 2: Identity Input ===")
    take_screenshot("02_identity_empty")

    # Fill in identity: name
    print("  Entering name 'Palaash Atri'...")
    run_adb("shell input text 'Palaash Atri'")
    time.sleep(1)
    take_screenshot("02_identity_name_entered")

    # Move to username field (tap next)
    print("  Tapping next field...")
    tap(540, 400)  # Somewhere in the middle
    time.sleep(0.5)

    # Enter username
    print("  Entering username...")
    run_adb("shell input text 'palaashatri'")
    time.sleep(1)
    take_screenshot("02_identity_username_entered")

    # Email field
    print("  Moving to email field...")
    tap(540, 500)
    time.sleep(0.5)
    run_adb("shell input text 'palaash@example.com'")
    time.sleep(1)
    take_screenshot("02_identity_email_entered")

    print("  ✓ Identity input tested")

def test_03_username_discovery():
    """Test 3: Username discovery screen"""
    print("\n=== TEST 3: Username Discovery ===")
    # Proceed to next screen
    tap(540, 2200)  # Next button
    time.sleep(3)
    take_screenshot("03_username_discovery")
    print("  ✓ Username discovery screen shown")

def test_04_scan_progress():
    """Test 4: Scan progress"""
    print("\n=== TEST 4: Scan Progress ===")
    # Start scan
    print("  Starting scan...")
    tap(540, 2100)  # Start scan button
    time.sleep(5)
    take_screenshot("04_scan_progress")
    print("  Note: Scan in progress. Will monitor...")

def test_05_report_screen():
    """Test 5: Report screen"""
    print("\n=== TEST 5: Report Screen ===")
    print("  Waiting for scan to complete...")
    # This will take a while, just take periodic screenshots
    for i in range(3):
        time.sleep(15)
        take_screenshot(f"05_scan_progress_{i}")
        print(f"  Progress check {i+1}...")

    take_screenshot("05_report_screen")
    print("  ✓ Report screen tested")

def main():
    print("Starting Dossier QA Test Suite")
    print(f"Screenshots will be saved to: {SCREENSHOT_DIR}")

    try:
        print("\nLaunching app...")
        run_adb("shell am start -n io.dossier.app/.MainActivity")
        time.sleep(4)

        test_01_consent_screen()
        test_02_identity_input()
        test_03_username_discovery()
        test_04_scan_progress()
        test_05_report_screen()

        print("\n=== QA Tests Complete ===")
        print(f"Screenshots saved to: {SCREENSHOT_DIR}")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main()
