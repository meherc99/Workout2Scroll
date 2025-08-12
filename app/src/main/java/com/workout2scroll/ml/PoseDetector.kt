package com.workout2scroll.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Keypoint(
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class PoseData(
    val keypoints: List<Keypoint>,
    val confidence: Float
)

class PoseDetector(private val context: Context) {
    
    companion object {
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
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()
    
    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(model)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun detectPose(imageProxy: ImageProxy): PoseData? {
        return try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
            detectPoseFromBitmap(rotatedBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    suspend fun detectPoseFromBitmap(bitmap: Bitmap): PoseData? {
        return detectPose(bitmap)
    }
    
    private fun detectPose(bitmap: Bitmap): PoseData? {
        val interpreter = this.interpreter ?: return null
        
        try {
            // Prepare input
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)
            val inputBuffer = processedImage.buffer
            
            // Prepare output
            val outputArray = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
            
            // Run inference
            interpreter.run(inputBuffer, outputArray)
            
            // Parse results
            val keypoints = mutableListOf<Keypoint>()
            val keypointData = outputArray[0][0]
            
            for (i in keypointData.indices) {
                val y = keypointData[i][0]
                val x = keypointData[i][1]
                val confidence = keypointData[i][2]
                
                keypoints.add(Keypoint(x, y, confidence))
            }
            
            // Calculate overall confidence
            val avgConfidence = keypoints.map { it.confidence }.average().toFloat()
            
            return PoseData(keypoints, avgConfidence)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        // Convert YUV to RGB (simplified - in real implementation you'd handle YUV properly)
        // For now, we'll create a placeholder bitmap
        return Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
    }
    
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    fun close() {
        interpreter?.close()
    }
}
