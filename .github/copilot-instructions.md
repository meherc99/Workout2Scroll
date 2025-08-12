<!-- Use this file to provide workspace-specific custom instructions to Copilot. For more details, visit https://code.visualstudio.com/docs/copilot/copilot-customization#_use-a-githubcopilotinstructionsmd-file -->

# Workout2Scroll Android App Instructions

This is an Android app project using Kotlin that integrates:
- MoveNet TensorFlow Lite for pose detection and squat counting
- Instagram usage monitoring and time management
- Camera integration for real-time pose detection
- SharedPreferences for storing earned Instagram minutes

## Key Components:
- **MainActivity**: Main UI with squat counter and Instagram timer
- **PoseDetector**: MoveNet integration for detecting squats
- **SquatCounter**: Logic for counting valid squats based on pose keypoints
- **InstagramTimeManager**: Manages earned and spent Instagram time
- **CameraFragment**: Handles camera preview and pose detection overlay

## Development Guidelines:
- Use modern Android development practices with ViewBinding
- Implement proper camera permissions handling
- Use lifecycle-aware components for camera operations
- Store data persistently using SharedPreferences
- Follow Material Design principles for UI
- Ensure smooth real-time pose detection performance
