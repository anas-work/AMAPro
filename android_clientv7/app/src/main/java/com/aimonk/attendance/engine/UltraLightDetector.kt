package com.aimonk.attendance.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.RectF
import com.aimonk.attendance.model.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class UltraLightDetector(
    context: Context,
    val confThreshold: Float = 0.58f,
    val nmsThreshold: Float = 0.25f
) {
    val width = 320
    val height = 240

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val priors: FloatArray
    private val inputBuffer: ByteBuffer
    private val intValues = IntArray(width * height)

    // Pre-allocated scaling Canvas and Bitmap to avoid per-frame GC allocations
    private val scaledBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val scaleCanvas = android.graphics.Canvas(scaledBitmap)
    private val scalePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val srcRect = android.graphics.Rect()
    private val dstRect = android.graphics.Rect(0, 0, width, height)

    // Pre-allocated output arrays (Zero Garbage Collection during live 60 FPS loop)
    private val outputScores = Array(1) { Array(4420) { FloatArray(2) } }
    private val outputBoxes = Array(1) { Array(4420) { FloatArray(4) } }
    private val outputMap = mutableMapOf<Int, Any>()

    init {
        val modelBuffer = loadModelFile(context, "version-RFB-320.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                addDelegate(gpuDelegate)
            }
            useNNAPI = true
        }

        interpreter = Interpreter(modelBuffer, options)
        priors = generatePriors()

        // 1 x 240 x 320 x 3 x 4 bytes (Float32 direct buffer)
        inputBuffer = ByteBuffer.allocateDirect(1 * height * width * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        outputMap[0] = outputScores
        outputMap[1] = outputBoxes
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun generatePriors(): FloatArray {
        val minBoxes = arrayOf(
            intArrayOf(10, 16, 24),
            intArrayOf(32, 48),
            intArrayOf(64, 96),
            intArrayOf(128, 192, 256)
        )
        val strides = intArrayOf(8, 16, 32, 64)
        val list = mutableListOf<Float>()

        for (k in strides.indices) {
            val stride = strides[k]
            val fH = ceil(height.toFloat() / stride).toInt()
            val fW = ceil(width.toFloat() / stride).toInt()
            for (i in 0 until fH) {
                for (j in 0 until fW) {
                    for (minBox in minBoxes[k]) {
                        val sKx = minBox.toFloat() / width
                        val sKy = minBox.toFloat() / height
                        val cx = (j + 0.5f) * stride / width
                        val cy = (i + 0.5f) * stride / height
                        list.addAll(listOf(cx, cy, sKx, sKy))
                    }
                }
            }
        }
        return list.toFloatArray()
    }

    @Synchronized
    fun detect(bitmap: Bitmap, originalWidth: Int, originalHeight: Int): List<Detection> {
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        scaleCanvas.drawBitmap(bitmap, srcRect, dstRect, scalePaint)
        scaledBitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        inputBuffer.rewind()

        // Fast NHWC Normalization: (RGB - 127.0) / 128.0 == RGB * 0.0078125 - 0.9921875
        val totalPixels = width * height
        for (i in 0 until totalPixels) {
            val p = intValues[i]
            val r = ((p shr 16 and 0xFF) * 0.0078125f) - 0.9921875f
            val g = ((p shr 8 and 0xFF) * 0.0078125f) - 0.9921875f
            val b = ((p and 0xFF) * 0.0078125f) - 0.9921875f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        val inputArray = arrayOf<Any>(inputBuffer)
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)

        val scores = outputScores[0]
        val boxes = outputBoxes[0]
        val numPriors = priors.size / 4
        val detections = mutableListOf<Detection>()

        for (i in 0 until numPriors) {
            val score = scores[i][1]
            if (score >= confThreshold) {
                val pIdx = i * 4
                val cx = priors[pIdx] + boxes[i][0] * 0.1f * priors[pIdx + 2]
                val cy = priors[pIdx + 1] + boxes[i][1] * 0.1f * priors[pIdx + 3]
                val w = priors[pIdx + 2] * exp(boxes[i][2] * 0.2f)
                val h = priors[pIdx + 3] * exp(boxes[i][3] * 0.2f)

                val x1 = max(0f, (cx - w / 2f) * originalWidth)
                val y1 = max(0f, (cy - h / 2f) * originalHeight)
                val x2 = min(originalWidth.toFloat(), (cx + w / 2f) * originalWidth)
                val y2 = min(originalHeight.toFloat(), (cy + h / 2f) * originalHeight)

                val rawW = x2 - x1
                val rawH = y2 - y1

                // Calibration: Increase height from top, reduce from bottom, expand width from both sides
                val padX = rawW * 0.07f
                val adjX1 = max(0f, x1 - padX)
                val adjX2 = min(originalWidth.toFloat(), x2 + padX)
                val adjY1 = max(0f, y1 - rawH * 0.08f) // Expand from top
                val adjY2 = min(originalHeight.toFloat(), y2 - rawH * 0.08f) // Reduce from bottom

                val finalW = adjX2 - adjX1
                val finalH = adjY2 - adjY1
                val finalAspect = finalW / max(1f, finalH)

                if (finalW >= 30 && finalH >= 30 && finalAspect in 0.65f..1.45f) {
                    detections.add(Detection(RectF(adjX1, adjY1, adjX2, adjY2), score))
                }
            }
        }

        return nms(detections)
    }

    private fun nms(dets: MutableList<Detection>): List<Detection> {
        if (dets.size <= 1) return dets
        dets.sortByDescending { it.score }
        val keep = mutableListOf<Detection>()
        val suppressed = BooleanArray(dets.size)

        for (i in dets.indices) {
            if (suppressed[i]) continue
            keep.add(dets[i])
            val r1 = dets[i].bbox
            val area1 = (r1.width() + 1f) * (r1.height() + 1f)

            for (j in i + 1 until dets.size) {
                if (suppressed[j]) continue
                val r2 = dets[j].bbox
                val interLeft = max(r1.left, r2.left)
                val interTop = max(r1.top, r2.top)
                val interRight = min(r1.right, r2.right)
                val interBottom = min(r1.bottom, r2.bottom)

                val interW = max(0f, interRight - interLeft + 1f)
                val interH = max(0f, interBottom - interTop + 1f)
                val interArea = interW * interH
                val area2 = (r2.width() + 1f) * (r2.height() + 1f)
                val iou = interArea / max(1f, area1 + area2 - interArea)

                if (iou >= nmsThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
