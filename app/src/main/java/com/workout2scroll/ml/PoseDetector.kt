package com.workout2scroll.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class Keypoint(
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class PoseData(
    val keypoints: List<Keypoint>,
    val confidence: Float,
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val mirrored: Boolean = false
)

class PoseDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "W2S.Pose"
        private const val MODEL_FILE = "movenet_singlepose_lightning.tflite"
        private const val INPUT_SIZE = 192
        
        // MoveNet keypoint indices
        const val NOSE = 0
        const val LEFT_EYE = 1
        const val RIGHT_EYE = 2
        const val LEFT_EAR = 3
        const val RIGHT_EAR = 4
        const val LEFT_SHOULDER = 5
        const val RIGHT_SHOULDER = 6
        const val LEFT_ELBOW = 7
        const val RIGHT_ELBOW = 8
        const val LEFT_WRIST = 9
        const val RIGHT_WRIST = 10
        const val LEFT_HIP = 11
        const val RIGHT_HIP = 12
        const val LEFT_KNEE = 13
        const val RIGHT_KNEE = 14
        const val LEFT_ANKLE = 15
        const val RIGHT_ANKLE = 16
    }
    
    private var interpreter: Interpreter? = null
    private var inferenceCount = 0L
    var initializationError: String? = null
        private set
    val isReady: Boolean get() = interpreter != null
    
    init {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "Loading MoveNet: asset=$MODEL_FILE threads=2")
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
            require(interpreter!!.getInputTensor(0).shape().contentEquals(intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))) {
                "Unsupported MoveNet input shape"
            }
            val input = interpreter!!.getInputTensor(0)
            val output = interpreter!!.getOutputTensor(0)
            Log.i(TAG, "Model ready: inputShape=${input.shape().contentToString()} inputType=${input.dataType()} inputBytes=${input.numBytes()} outputShape=${output.shape().contentToString()} outputType=${output.dataType()} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (e: Exception) {
            Log.e(TAG, "MoveNet initialization failed after ${SystemClock.elapsedRealtime() - startedAt}ms", e)
            interpreter?.close()
            interpreter = null
            initializationError = e.message ?: "MoveNet model could not be loaded"
        }
    }
    
    fun detectPose(imageProxy: ImageProxy, mirrored: Boolean = true): PoseData {
        val bitmap = imageProxy.toBitmap()
        var rotatedBitmap = bitmap
        try {
            rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
            return detectPoseFromBitmap(rotatedBitmap).copy(mirrored = mirrored)
        } finally {
            if (rotatedBitmap !== bitmap) rotatedBitmap.recycle()
            bitmap.recycle()
        }
    }
    
    @Synchronized
    fun detectPoseFromBitmap(bitmap: Bitmap): PoseData {
        val startedAt = SystemClock.elapsedRealtime()
        val interpreter = checkNotNull(this.interpreter) { initializationError ?: "Pose detector is closed" }
        val scale = min(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (INPUT_SIZE - scaledWidth) / 2f
        val top = (INPUT_SIZE - scaledHeight) / 2f
        val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(inputBitmap).apply {
                drawColor(Color.BLACK)
                drawBitmap(bitmap, null, RectF(left, top, left + scaledWidth, top + scaledHeight),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            val inputTensor = interpreter.getInputTensor(0)
            val inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            inputBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (pixel in pixels) {
                for (channel in intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    when (inputTensor.dataType()) {
                        DataType.INT32 -> inputBuffer.putInt(channel)
                        DataType.UINT8 -> inputBuffer.put(channel.toByte())
                        DataType.FLOAT32 -> inputBuffer.putFloat(channel.toFloat())
                        else -> error("Unsupported MoveNet input type: ${inputTensor.dataType()}")
                    }
                }
            }
            inputBuffer.rewind()
            val outputArray = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
            interpreter.run(inputBuffer, outputArray)
            val keypoints = outputArray[0][0].map { values ->
                Keypoint(
                    (values[1] * INPUT_SIZE - left) / scaledWidth,
                    (values[0] * INPUT_SIZE - top) / scaledHeight,
                    values[2]
                )
            }
            val confidence = keypoints.map { it.confidence }.average().toFloat()
            inferenceCount++
            if (inferenceCount == 1L || inferenceCount % 60L == 0L) {
                Log.d(TAG, "Inference completed: frame=$inferenceCount size=${bitmap.width}x${bitmap.height} bitmapConfig=${bitmap.config} scale=$scale confidence=$confidence elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
            return PoseData(keypoints, confidence, bitmap.width, bitmap.height)
        } catch (error: Exception) {
            Log.e(TAG, "Inference failed: frame=${inferenceCount + 1} size=${bitmap.width}x${bitmap.height} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}", error)
            throw error
        } finally {
            inputBitmap.recycle()
        }
    }
    
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    @Synchronized
    fun close() {
        Log.i(TAG, "Closing pose detector: ready=$isReady successfulInferences=$inferenceCount")
        interpreter?.close()
        interpreter = null
    }
}
