# Workout2Scroll - Earn Instagram Time Through Exercise

Workout2Scroll is an Android app that gamifies social media usage by requiring users to perform squats to earn Instagram time. Using Google's MoveNet pose detection model, the app tracks squat exercises in real-time and rewards users with Instagram access minutes.

## Features

- **Real-time Pose Detection**: Uses MoveNet TensorFlow Lite model for accurate squat detection
- **Gamified Instagram Access**: Earn 1 minute of Instagram time for every squat performed
- **Visual Feedback**: Live pose overlay showing skeleton tracking during workouts
- **Daily Reset**: Instagram time resets each day to encourage daily exercise
- **Usage Monitoring**: Tracks Instagram usage (requires usage access permission)

## How It Works

1. **Start Workout**: Point your phone's front camera at yourself and tap "Start Workout"
2. **Perform Squats**: The app uses MoveNet to detect proper squat form
3. **Earn Time**: Each detected squat earns you 1 minute of Instagram access
4. **Use Instagram**: Open Instagram through the app when you have earned time

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

### Instagram Integration
- Monitors app usage through UsageStatsManager
- Controls access through earned time system
- Launches Instagram app or web version
- Tracks daily usage patterns

## Setup Requirements

### Android Permissions
- **Camera**: Required for pose detection
- **Usage Access**: Optional, for monitoring Instagram usage

### Dependencies
- TensorFlow Lite 2.14.0
- CameraX for camera handling
- Material Design 3 components

### Hardware Requirements
- Android 7.0 (API level 24) or higher
- Front-facing camera
- Minimum 3GB RAM for optimal performance

## Installation

1. Clone the repository
2. Download MoveNet model file and place in `app/src/main/assets/`
3. Build and install the APK
4. Grant camera permissions
5. Optionally grant usage access for Instagram monitoring

## Model Files

The app requires the MoveNet model file:
- **File**: `movenet_singlepose_lightning.tflite`
- **Size**: ~12MB
- **Source**: [TensorFlow Hub](https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4)

## Project Structure

```
app/src/main/java/com/workout2scroll/
├── MainActivity.kt                 # Main activity with camera and UI
├── ml/
│   ├── PoseDetector.kt            # MoveNet integration
│   └── SquatCounter.kt            # Squat detection logic
├── ui/
│   └── PoseOverlayView.kt         # Pose visualization overlay
└── utils/
    └── InstagramTimeManager.kt    # Time tracking and persistence
```

## Future Enhancements

- **Multiple Exercises**: Add push-ups, jumping jacks, etc.
- **Social Features**: Leaderboards and challenges
- **Workout Plans**: Structured exercise routines
- **Health Integration**: Connect with fitness apps
- **Custom Time Rewards**: Adjust time-per-exercise ratios

## Privacy & Safety

- All pose detection happens locally on device
- No video data is stored or transmitted
- Usage data stays on device
- App encourages healthy social media habits

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
