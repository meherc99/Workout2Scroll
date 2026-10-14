#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

MODEL_URL="https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4?lite-format=tflite"
MODEL_FILE="app/src/main/assets/movenet_singlepose_lightning.tflite"

is_tflite() {
    [[ -f "$1" && "$(dd if="$1" bs=1 skip=4 count=4 2>/dev/null)" == "TFL3" ]]
}

if is_tflite "$MODEL_FILE"; then
    echo "Using existing TensorFlow Lite model: $MODEL_FILE"
    exit 0
fi

mkdir -p app/src/main/assets
temporary_model=$(mktemp app/src/main/assets/.movenet.XXXXXX)
trap 'rm -f -- "$temporary_model"' EXIT
echo "Downloading MoveNet Lightning model..."
curl --fail --location --retry 2 --connect-timeout 15 --max-time 180 "$MODEL_URL" -o "$temporary_model"
if ! is_tflite "$temporary_model"; then
    echo "Download is not a TensorFlow Lite model. Existing assets were not changed." >&2
    echo "Download the model manually from $MODEL_URL" >&2
    exit 1
fi
mv -- "$temporary_model" "$MODEL_FILE"
echo "Model downloaded successfully to $MODEL_FILE"
