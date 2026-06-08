package com.example.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

enum class AspectRatioType {
    RATIO_9_16,
    RATIO_1_1
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    object InitializingEngine : ProcessingState()
    object Ready : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class CameraProcessManager(private val context: Context) : ImageAnalysis.Analyzer {

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> get() = _processingState

    var depthIntensity = MutableStateFlow(0.7f)
    var warmth = MutableStateFlow(0.5f)
    var aspectRatio = MutableStateFlow(AspectRatioType.RATIO_9_16)

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> get() = _latestFrame

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> get() = _isRecording

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> get() = _recordingDurationSec

    private val _lastSavedVideoUri = MutableStateFlow<Uri?>(null)
    val lastSavedVideoUri: StateFlow<Uri?> get() = _lastSavedVideoUri

    private var imageSegmenter: ImageSegmenter? = null
    private var videoEncoder: VideoEncoder? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    // ─── 🚀 GPU RenderScript Engines ───
    private var rs: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null

    init {
        initGPU()
        initSegmenter()
    }

    private fun initGPU() {
        try {
            rs = RenderScript.create(context)
            blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            Log.d("CameraProcessManager", "Hardware RenderScript GPU Blur Engine initialized!")
        } catch (e: Exception) {
            Log.e("CameraProcessManager", "GPU Engine init failed", e)
        }
    }

    private fun initSegmenter() {
        _processingState.value = ProcessingState.InitializingEngine
        try {
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()

            imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            _processingState.value = ProcessingState.Ready
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error("Engine error: ${e.localizedMessage}")
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val segmenter = imageSegmenter
        if (_processingState.value != ProcessingState.Ready || segmenter == null) {
            imageProxy.close()
            return
        }

        val rawBitmap = imageProxy.toBitmap()
        if (rawBitmap == null) {
            imageProxy.close()
            return
        }

        try {
            // ─── 🛠️ FIX 1: Exact Sensor Rotation Sync ───
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            // Optional: Mirror for front camera using scale(-1f, 1f)

            val rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

            // Scale down for extreme real-time smoothness
            val targetHeight = 640
            val targetWidth = (rotatedBitmap.width * (targetHeight.toFloat() / rotatedBitmap.height)).toInt()
            val workingBitmap = Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)

            val croppedBitmap = cropBitmapToRatio(workingBitmap, aspectRatio.value == AspectRatioType.RATIO_1_1)

            // MediaPipe Segmentation
            val mpImage = BitmapImageBuilder(croppedBitmap).build()
            val result = segmenter.segment(mpImage)
            
            val masksOpt = result.confidenceMasks()
            val maskImage = if (masksOpt != null && masksOpt.isPresent) masksOpt.get()[0] else null

            if (maskImage != null) {
                val byteBuffer = ByteBufferExtractor.extract(maskImage)
                val confidenceBuffer = byteBuffer.asFloatBuffer()

                // ─── 🚀 FIX 2: Hardware GPU Gaussian Blur ───
                val blurredBitmap = hardwareGaussianBlur(croppedBitmap, depthIntensity.value)

                val finalProcessedFrame = processFrame(
                    croppedBitmap,
                    blurredBitmap,
                    confidenceBuffer,
                    warmth.value,
                    depthIntensity.value
                )

                val oldFrame = _latestFrame.value
                _latestFrame.value = finalProcessedFrame

                oldFrame?.recycle()
                blurredBitmap.recycle()

                if (_isRecording.value) {
                    videoEncoder?.let { encoder ->
                        processingScope.launch {
                            try { encoder.encodeFrame(finalProcessedFrame, System.nanoTime()) } 
                            catch (e: Exception) {}
                        }
                    }
                }
            }

            // ─── 🛠️ FIX 3: Memory Cleanup ───
            croppedBitmap.recycle()
            workingBitmap.recycle()
            rotatedBitmap.recycle()
            rawBitmap.recycle()

        } catch (e: Exception) {
            Log.e("CameraProcessManager", "Frame failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun hardwareGaussianBlur(src: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.05f) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)

        val rsContext = rs ?: return src
        val script = blurScript ?: return src

        // DSLR Trick: Scale down to 25% size, apply max GPU radius (25f), then scale up. 
        // This gives massive, creamy light-bleed bokeh with zero lag.
        val scale = 0.25f 
        val w = Math.max(16, (src.width * scale).toInt())
        val h = Math.max(16, (src.height * scale).toInt())

        val scaledDown = Bitmap.createScaledBitmap(src, w, h, true)
        val blurredDown = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val allocIn = Allocation.createFromBitmap(rsContext, scaledDown)
        val allocOut = Allocation.createFromBitmap(rsContext, blurredDown)

        val radius = (intensity * 25f).coerceIn(1f, 25f)
        script.setRadius(radius)
        script.setInput(allocIn)
        script.forEach(allocOut)

        allocOut.copyTo(blurredDown)

        val finalBlurred = Bitmap.createScaledBitmap(blurredDown, src.width, src.height, true)

        allocIn.destroy()
        allocOut.destroy()
        scaledDown.recycle()
        blurredDown.recycle()

        return finalBlurred
    }

    private fun cropBitmapToRatio(bitmap: Bitmap, isSquare: Boolean): Bitmap {
        if (!isSquare) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val minDim = Math.min(w, h)
        val xOffset = (w - minDim) / 2
        val yOffset = (h - minDim) / 2
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, minDim, minDim, null, false)
    }

    private fun processFrame(
        original: Bitmap, blurred: Bitmap, maskBuffer: FloatBuffer, warmthVal: Float, intensity: Float
    ): Bitmap {
        val w = original.width
        val h = original.height
        val count = w * h
        
        val origPixels = IntArray(count)
        val blurPixels = IntArray(count)
        val outPixels = IntArray(count)
        
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        blurred.getPixels(blurPixels, 0, w, 0, 0, w, h)
        
        val rFactor = if (warmthVal > 0.5f) 1f + (warmthVal - 0.5f) * 0.18f else 1f - (0.5f - warmthVal) * 0.15f
        val bFactor = if (warmthVal > 0.5f) 1f - (warmthVal - 0.5f) * 0.15f else 1f + (0.5f - warmthVal) * 0.18f
        
        for (i in 0 until count) {
            val maskVal = if (i < maskBuffer.limit()) maskBuffer.get(i) else 0.5f
            
            val op = origPixels[i]
            val oR = (op ushr 16) and 0xff
            val oG = (op ushr 8) and 0xff
            val oB = op and 0xff
            
            val bp = blurPixels[i]
            val bR = (bp ushr 16) and 0xff
            val bG = (bp ushr 8) and 0xff
            val bB = bp and 0xff
            
            val effectiveMaskVal = maskVal + (1f - maskVal) * (1f - intensity)
            
            var outR = (oR * effectiveMaskVal + bR * (1f - effectiveMaskVal)).toInt()
            var outG = (oG * effectiveMaskVal + bG * (1f - effectiveMaskVal)).toInt()
            var outB = (oB * effectiveMaskVal + bB * (1f - effectiveMaskVal)).toInt()
            
            if (warmthVal != 0.5f) {
                outR = (outR * rFactor).toInt().coerceIn(0, 255)
                outB = (outB * bFactor).toInt().coerceIn(0, 255)
            }
            
            outPixels[i] = (255 shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
        
        val result = Bitmap.createBitmap(w, h, original.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    fun startRecording() {
        if (_isRecording.value) return
        _lastSavedVideoUri.value = null

        val currentFrame = _latestFrame.value ?: return
        val tempFile = File(context.cacheDir, "temp_record_${System.currentTimeMillis()}.mp4")
        videoEncoder = VideoEncoder(context, tempFile, currentFrame.width, currentFrame.height)

        try {
            videoEncoder?.start()
            _isRecording.value = true
            _recordingDurationSec.value = 0
            
            processingScope.launch {
                while (_isRecording.value) {
                    kotlinx.coroutines.delay(1000)
                    if (_isRecording.value) _recordingDurationSec.value += 1
                }
            }
        } catch (e: Exception) {
            videoEncoder = null
            _isRecording.value = false
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        
        processingScope.launch {
            val encoder = videoEncoder ?: return@launch
            try {
                val savedUri = encoder.stop()
                withContext(Dispatchers.Main) { _lastSavedVideoUri.value = savedUri }
            } catch (e: Exception) {} 
            finally { videoEncoder = null }
        }
    }
}
