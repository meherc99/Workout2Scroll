# Workout2Scroll - Earn Instagram Time Through Exercise

Workout2Scroll is an Android app that lets users perform squats or push-ups to earn Instagram time. Using Google's MoveNet pose detection model, the app tracks the selected exercise in real time or in uploaded videos and rewards completed repetitions with Instagram access minutes.

## Features

- **Real-time Pose Detection**: Uses MoveNet TensorFlow Lite for squat and push-up detection
- **Exercise Selection**: Choose squats or push-ups before starting a camera workout or uploading a clip; the choice persists between launches and is locked during processing
- **Gamified Instagram Access**: Earn 1 minute per squat or 2 minutes per push-up
- **Visual Feedback**: Live pose overlay showing skeleton tracking during workouts
- **Daily Reset**: Instagram time resets each day to encourage daily exercise
- **Video Workouts**: Analyze a local workout clip, with progress, cancellation, and duplicate-reward protection
- **Instagram Guard**: An opt-in accessibility service times the native Instagram app and returns Home when time expires
- **Persistent Balance**: Second-level remaining time and daily earned/used totals survive app restarts

## How It Works

1. **Start Workout**: Grant camera access when starting a live workout. The front camera is preferred; the back camera is used if necessary. Video uploads do not need camera or storage permission.
2. **Choose and Perform an Exercise**: Squats require both hips, knees, and ankles visible, starting standing, bending both knees, and returning to standing. For push-ups, position the camera side-on so at least one shoulder, elbow, wrist, hip, and ankle are visible. Start with arms extended in a plank, lower, and return to extended arms. Lost tracking discards an incomplete repetition.
3. **Earn Time**: Each completed squat earns one minute; each push-up earns two. Uploaded clips use the same exercise selection and reward rates. Clips must be 1 second to 10 minutes, at least 224x224, and supported by the device decoder. A completed file can earn a reward only once, even if the exercise selection changes.
4. **Enable Instagram Guard**: Read the disclosure and explicitly enable Workout2Scroll in Android accessibility settings. Sideloaded apps may require "Allow restricted settings" in Android app settings first.
5. **Use Instagram**: Install the native Instagram app and open it with earned time. Launching does not charge a minute; foreground usage is deducted as it happens, including direct launches outside Workout2Scroll.

## Technical Implementation

### Pose Detection
- **MoveNet Lightning**: Fast, accurate pose detection optimized for mobile
- **17 Keypoints**: Tracks full body pose including hips, knees, and ankles for squat detection
- **Angle Calculation**: Measures knee angles to determine squat depth and completion

### Squat Detection Algorithm
- Monitors hip-knee-ankle angles
- Detects transition from standing to squatting position
- Validates proper form before counting repetitions
- Prevents false positives through confidence thresholds

### Push-up Detection Algorithm
- Uses the best visible side and requires consistent side tracking through a repetition
- Checks horizontal plank-like posture, shoulder-hip-ankle alignment, and a wrist below the shoulder
- Requires an extended-arm, lowered, extended-arm cycle with at least two consecutive frames at each endpoint
- Uses aspect-correct elbow angles; tracking loss, posture loss, or a side change discards an incomplete repetition

### Instagram Integration
- Observes foreground package changes using a user-enabled AccessibilityService, without retrieving window contents
- Deducts elapsed foreground time, pauses for screen lock and app switches, and returns Home at zero balance
- Launches the native Instagram app; browser Instagram is deliberately not launched or monitored
- Resets daily balance and daily totals on the first balance access after the local date changes, including while the guard is running

## Setup Requirements

### Android Permissions
- **Camera**: Requested only for live workouts; denied access does not prevent video uploads
- **Accessibility Service**: Explicit opt-in required for the Instagram guard; independently revocable in system settings
- No broad storage, usage-access, internet, or all-packages permission is required

### Dependencies
- TensorFlow Lite 2.14.0
- CameraX for camera handling
- Material Design 3 components

### Hardware Requirements
- Android 7.0 (API level 24) or higher
- A camera for live workouts (optional for uploaded videos)
- Minimum 3GB RAM for optimal performance

## Installation

1. Clone the repository
2. The MoveNet asset is already included. Run `bash download_model.sh` to verify it or download it if absent. The helper rejects non-TFLite downloads without overwriting the existing asset.
3. Build with `./gradlew assembleDebug` and install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Grant camera permission when starting a live workout, or select Upload Video
5. Enable the Instagram Guard through the in-app setup button and install Instagram

## Model Files

The app requires the MoveNet model file:
- **File**: `movenet_singlepose_lightning.tflite`
- **Size**: Approximately 4.6MB for the bundled float16 Lightning model
- **Source**: [TensorFlow Hub](https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4)

## Project Structure

```
app/src/main/java/com/workout2scroll/
├── MainActivity.kt                 # Main activity with camera and UI
├── ml/
│   ├── PoseDetector.kt            # MoveNet integration
│   ├── SquatCounter.kt            # Squat detection logic
│   ├── PushupCounter.kt           # Push-up detection logic
│   └── ExerciseCounter.kt         # Exercise selection and reward rates
├── ui/
│   └── PoseOverlayView.kt         # Pose visualization overlay
└── utils/
    └── InstagramTimeManager.kt    # Time tracking and persistence
```

## Future Enhancements

- **More Exercises**: Add jumping jacks and other movements
- **Social Features**: Leaderboards and challenges
- **Workout Plans**: Structured exercise routines
- **Health Integration**: Connect with fitness apps
- **Custom Time Rewards**: Adjust time-per-exercise ratios

## Privacy & Safety

- All pose detection happens locally on device
- No video data is stored or transmitted
- Usage data stays on device
- App encourages healthy social media habits
- Only SHA-256 fingerprints of completed uploads are retained to prevent duplicate rewards; video contents are not copied into persistent app storage
- Accessibility events are used for package names only; window content retrieval is disabled

## Testing

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
# Requires a booted Android emulator or connected test device:
./gradlew connectedDebugAndroidTest
```

Local tests cover squat and push-up state machines, exercise reward rates, balances, and video helpers. Device tests run the bundled TFLite model, live CameraX frames, actual video decoding, picker-result processing, cancellation, duplicate rewards, persistence, exercise selection, and UI navigation. The small blank-video fixture is generated media, not a recorded workout. Instrumentation tests clear the app balance and grant camera permission: run them on a disposable emulator, not an installation whose data matters.

See [TESTING.md](TESTING.md) for verified coverage and the physical-device acceptance checklist.

## Limitations

The guard is a voluntary habit tool, not tamper-proof parental control. It depends on an enabled, connected accessibility service and Android delivering window events. It cannot monitor browser Instagram, cloned package variants, or reliably account for every OEM overlay or multi-window mode. Disabling or force-stopping the service disables enforcement. Native Instagram expiry and real human squat and push-up accuracy still require physical-device acceptance testing. Push-up detection expects a side-on, largely horizontal body view; knee push-ups and strongly angled camera views are not supported.

Duplicate detection identifies identical file bytes, not edited or re-encoded copies. Pose estimates are not a medical assessment or a guarantee of safe exercise form. Publishing an accessibility-based guard also requires reviewing the current Google Play Accessibility API policies and completing applicable declarations.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Implement your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Disclaimer

This app is designed to encourage healthy exercise habits and mindful social media usage. Users should perform exercises safely and consult healthcare providers for fitness advice.
