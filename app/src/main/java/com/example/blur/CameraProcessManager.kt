package com.example.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.net.Uri
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

    // Manual adjustments
    var depthIntensity = MutableStateFlow(0.7f)
    var warmth = MutableStateFlow(0.5f)
    var aspectRatio = MutableStateFlow(AspectRatioType.RATIO_9_16)

    // Current output frame bitmap for UI display
    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> get() = _latestFrame

    // Recording states
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> get() = _isRecording

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> get() = _recordingDurationSec

    private val _lastSavedVideoUri = MutableStateFlow<Uri?>(null)
    val lastSavedVideoUri: StateFlow<Uri?> get() = _lastSavedVideoUri

    private var imageSegmenter: ImageSegmenter? = null
    private var videoEncoder: VideoEncoder? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    init {
        initSegmenter()
    }

    private fun initSegmenter() {
        _processingState.value = ProcessingState.InitializingEngine
        try {
            // Load pre-built model directly from system Assets
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_segmenter.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()

            imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            _processingState.value = ProcessingState.Ready
            Log.d("CameraProcessManager", "ImageSegmenter initialized successfully from bundled assets!")
        } catch (e: Exception) {
            Log.e("CameraProcessManager", "Error initializing MediaPipe ImageSegmenter", e)
            _processingState.value = ProcessingState.Error("Engine startup error: ${e.localizedMessage}")
        }
    }

    // Capture standard frames and process them
    override fun analyze(imageProxy: ImageProxy) {
        val segmenter = imageSegmenter
        if (_processingState.value != ProcessingState.Ready || segmenter == null) {
            imageProxy.close()
            return
        }

        try {
            // Get bitmap with correct orientation natively via CameraX
            val rawBitmap = imageProxy.toBitmap() ?: return

            // Standard camera frame is rotated. Scale down to 360p or 480p equivalent for extreme smoothness.
            val targetHeight = 640
            val targetWidth = (rawBitmap.width * (targetHeight.toFloat() / rawBitmap.height)).toInt()
            val workingBitmap = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)

            // Crop according to selected Aspect Ratio
            val croppedBitmap = cropBitmapToRatio(workingBitmap, aspectRatio.value == AspectRatioType.RATIO_1_1)

            // 1. Run MediaPipe Segmentation directly
            val mpImage = BitmapImageBuilder(croppedBitmap).build()
            val result = segmenter.segment(mpImage)
            
            // Extract the confidence mask from optional Java list safely
            val masksOpt = result.confidenceMasks()
            val maskImage = if (masksOpt != null && masksOpt.isPresent) {
                masksOpt.get()[0]
            } else {
                null
            }

            if (maskImage != null) {
                val byteBuffer = ByteBufferExtractor.extract(maskImage)
                val confidenceBuffer = byteBuffer.asFloatBuffer()

                // 2. Perform blurring natively
                val blurredBitmap = fastBlur(croppedBitmap, depthIntensity.value)

                // 3. Process the frame blending & color balance in one step
                val finalProcessedFrame = processFrame(
                    croppedBitmap,
                    blurredBitmap,
                    confidenceBuffer,
                    warmth.value,
                    depthIntensity.value
                )

                // Render frame
                val oldFrame = _latestFrame.value
                _latestFrame.value = finalProcessedFrame

                // Clean memory
                oldFrame?.recycle()
                blurredBitmap.recycle()

                // If recording, feed to encoder
                if (_isRecording.value) {
                    videoEncoder?.let { encoder ->
                        processingScope.launch {
                            try {
                                encoder.encodeFrame(finalProcessedFrame, System.nanoTime())
                            } catch (e: Exception) {
                                Log.e("CameraProcessManager", "Encoding frame failed", e)
                            }
                        }
                    }
                }
            }

            // Cleanup
            croppedBitmap.recycle()
            workingBitmap.recycle()
            rawBitmap.recycle()

        } catch (e: Exception) {
            Log.e("CameraProcessManager", "Analysis frame failed", e)
        } finally {
            imageProxy.close()
        }
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

    private fun fastBlur(src: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.05f) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        
        // Scale down is proportional to blur requirements.
        val scale = 0.05f + (1f - intensity) * 0.15f
        val w = Math.max(16, (src.width * scale).toInt())
        val h = Math.max(16, (src.height * scale).toInt())
        
        val scaledDown = Bitmap.createScaledBitmap(src, w, h, true)
        val blurredMini = boxBlur(scaledDown)
        val scaledBack = Bitmap.createScaledBitmap(blurredMini, src.width, src.height, true)
        
        scaledDown.recycle()
        blurredMini.recycle()
        return scaledBack
    }

    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val out = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val p = pixels[(y + ky) * w + (x + kx)]
                        aSum += (p ushr 24) and 0xff
                        rSum += (p ushr 16) and 0xff
                        gSum += (p ushr 8) and 0xff
                        bSum += p and 0xff
                    }
                }
                out[y * w + x] = ((aSum / 9) shl 24) or ((rSum / 9) shl 16) or ((gSum / 9) shl 8) or (bSum / 9)
            }
        }
        
        val res = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }

    private fun processFrame(
        original: Bitmap,
        blurred: Bitmap,
        maskBuffer: FloatBuffer,
        warmthVal: Float,
        intensity: Float
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
            val oA = (op ushr 24) and 0xff
            val oR = (op ushr 16) and 0xff
            val oG = (op ushr 8) and 0xff
            val oB = op and 0xff
            
            val bp = blurPixels[i]
            val bR = (bp ushr 16) and 0xff
            val bG = (bp ushr 8) and 0xff
            val bB = bp and 0xff
            
            // Scaled blur blend
            val effectiveMaskVal = maskVal + (1f - maskVal) * (1f - intensity)
            
            var outR = (oR * effectiveMaskVal + bR * (1f - effectiveMaskVal)).toInt()
            var outG = (oG * effectiveMaskVal + bG * (1f - effectiveMaskVal)).toInt()
            var outB = (oB * effectiveMaskVal + bB * (1f - effectiveMaskVal)).toInt()
            
            if (warmthVal != 0.5f) {
                outR = (outR * rFactor).toInt().coerceIn(0, 255)
                outB = (outB * bFactor).toInt().coerceIn(0, 255)
            }
            
            outPixels[i] = (oA shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
        
        val result = Bitmap.createBitmap(w, h, original.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    // Recording API
    fun startRecording() {
        if (_isRecording.value) return
        _lastSavedVideoUri.value = null

        val currentFrame = _latestFrame.value ?: return
        val w = currentFrame.width
        val h = currentFrame.height

        val tempFile = File(context.cacheDir, "temp_record_${System.currentTimeMillis()}.mp4")
        videoEncoder = VideoEncoder(context, tempFile, w, h)

        try {
            videoEncoder?.start()
            _isRecording.value = true
            _recordingDurationSec.value = 0
            
            // Duration timer ticker
            processingScope.launch {
                while (_isRecording.value) {
                    kotlinx.coroutines.delay(1000)
                    if (_isRecording.value) {
                        _recordingDurationSec.value += 1
                    }
                }
            }
            Log.d("CameraProcessManager", "Started video recording successfully")
        } catch (e: Exception) {
            Log.e("CameraProcessManager", "Failed to start recording session", e)
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
                withContext(Dispatchers.Main) {
                    _lastSavedVideoUri.value = savedUri
                    Log.d("CameraProcessManager", "Stopped video recording. File Uri: $savedUri")
                }
            } catch (e: Exception) {
                Log.e("CameraProcessManager", "Failed to stop recording correctly", e)
            } finally {
                videoEncoder = null
            }
        }
    }
}
