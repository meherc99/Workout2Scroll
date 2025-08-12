#!/bin/bash

# Download MoveNet model for pose detection
echo "Downloading MoveNet Lightning model..."

MODEL_URL="https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4?lite-format=tflite"
MODEL_FILE="app/src/main/assets/movenet_singlepose_lightning.tflite"

# Create assets directory if it doesn't exist
mkdir -p app/src/main/assets

# Download the model
curl -L "$MODEL_URL" -o "$MODEL_FILE"

if [ $? -eq 0 ]; then
    echo "Model downloaded successfully to $MODEL_FILE"
    echo "File size: $(ls -lh $MODEL_FILE | awk '{print $5}')"
else
    echo "Failed to download model. Please download manually from:"
    echo "$MODEL_URL"
    echo "Save as: $MODEL_FILE"
fi
