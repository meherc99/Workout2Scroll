# Verification and Acceptance

## Automated Checks

Verified on Linux with the bundled Gradle wrapper and an Android 16 / API 36 x86_64 emulator.

| Area | Coverage |
| --- | --- |
| Squat state machine | Complete repetitions, initial standing, tracking loss, missing/degenerate coordinates, non-finite coordinates, both-leg standing, reset |
| Push-up state machine | Complete cycles, initial extension, tracking loss, posture loss, upright arm bends, partial bends, missing/degenerate coordinates, side changes, single-frame jitter, mirroring, aspect ratio, reset |
| Exercise rewards | One minute per squat, two minutes per push-up, exercise selection resets session counts |
| Video timeline | Whole-clip microsecond sampling, partial intervals, duration boundaries |
| Time balance | Rewards, exact elapsed debits, no overspend, negative input rejection, date/year rollover |
| Foreground sessions | App switches, lock/unlock, repeated window events |
| Video fingerprints | Stable content hashes, content changes, cancellation |
| Bundled MoveNet | Actual portrait/landscape inference, finite outputs, no rewards for a blank scene |
| CameraX | Streaming preview, live frame conversion and inference, start/stop |
| Video decoding | Actual MP4 decode, full-clip progress, completion once, invalid input, cancellation without completion |
| Upload workflow | Picker result to validation to completion, no false rewards, replay rejection |
| Android persistence | Manager recreation, legacy migration, date rollover, concurrent rewards, duplicate rewards |
| Activity UI | Startup, live/video tabs, processing visibility, preview/overlay bounds, guard disclosure |

Run local checks:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Run Android checks on a disposable emulator:

```bash
adb devices
./gradlew connectedDebugAndroidTest
```

Instrumentation clears the app preferences and grants camera permission. The test runner can uninstall the app after tests; reinstall the debug APK for manual inspection. If Espresso reports no window focus, check for emulator lock screens or system ANR dialogs before changing app code.

The generated two-second, blank H.264 fixture can be reproduced with:

```bash
ffmpeg -f lavfi -i color=c=black:s=240x320:r=10:d=2 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart no_person.mp4
```

## Physical-Device Acceptance

These checks are not replaced by emulator tests and remain manual, especially native Instagram enforcement and human pose accuracy.

- [ ] Clean install: deny camera permission; video selection remains usable. Permanently deny it and verify the app-settings recovery action.
- [ ] Start live mode, stand with the full body visible, perform five complete squats, and verify five minutes are earned. Verify incomplete reps and leaving the frame do not earn time.
- [ ] Select Push-ups, position the camera side-on with a shoulder, elbow, wrist, hip, and ankle visible, perform five complete push-ups, and verify ten minutes are earned. Verify upright arm bends, partial reps, and tracking loss do not earn time.
- [ ] Upload a known push-up clip and verify two minutes per manually counted rep. Confirm the exercise selector is disabled during live workouts and video validation/processing, re-enables afterwards, and retains its choice after restarting the app.
- [ ] Background and resume during a live workout. Verify it stops, the camera releases, and a new session can start without stale rewards.
- [ ] Upload a known workout clip; compare the result to manual counting. Cancel during validation and decoding; neither cancellation should award time.
- [ ] Upload the same completed file again; verify no additional reward. Verify unreadable, too-short, oversized-duration, and unsupported videos fail visibly.
- [ ] Enable the guard only after reading its disclosure. With no balance, launch native Instagram directly and verify return to Home.
- [ ] Earn one minute, use Instagram until expiry, and verify return to Home. Repeat a direct launch at zero balance.
- [ ] Switch to another app, open the keyboard inside Instagram, lock/unlock the screen, and verify only active Instagram time is charged.
- [ ] Restart Workout2Scroll and the device; verify the balance persists and the guard status accurately reflects whether Android connected the service.
- [ ] Cross local midnight with and without Instagram open; verify daily balance and totals reset while lifetime earnings remain.
- [ ] Disable the accessibility service and verify the app shows setup is needed rather than claiming enforcement is active.
- [ ] Check compact screens, large font settings, and at least one older supported Android device. Verify controls remain reachable by scrolling.

## Guard Boundaries

Only the native `com.instagram.android` package is timed. Browser sessions and cloned apps are unsupported. Accessibility delivery, split-screen, picture-in-picture, and OEM power restrictions require device-specific assessment. Service disablement and app force-stop are intentional user escape hatches, not security boundaries.