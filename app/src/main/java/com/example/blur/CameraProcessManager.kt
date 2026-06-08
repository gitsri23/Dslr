package com.example.blur

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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

enum class ResolutionType { RES_480P, RES_720P, RES_1080P }

sealed class ProcessingState {
    object Idle : ProcessingState()
    object InitializingEngine : ProcessingState()
    object Ready : ProcessingState()
    object ProcessingVideo : ProcessingState()
    data class Success(val uri: Uri) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class CameraProcessManager(private val context: Context) {

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> get() = _processingState

    var depthIntensity = MutableStateFlow(0.7f)
    var warmth = MutableStateFlow(0.5f)
    var resolution = MutableStateFlow(ResolutionType.RES_720P)

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> get() = _previewFrame

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> get() = _exportProgress

    private val _lastSavedVideoUri = MutableStateFlow<Uri?>(null)
    val lastSavedVideoUri: StateFlow<Uri?> get() = _lastSavedVideoUri

    var selectedVideoUri: Uri? = null
    private var rawPreviewBitmap: Bitmap? = null

    private var imageSegmenter: ImageSegmenter? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    init {
        initSegmenter()
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

    // ─── 🚀 UI Preview Processing ───
    fun loadVideoForPreview(uri: Uri) {
        selectedVideoUri = uri
        processingScope.launch {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                // Get the very first frame to show as thumbnail preview
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()

                if (frame != null) {
                    rawPreviewBitmap = frame
                    updatePreviewBlur() // Apply blur instantly to the loaded frame
                } else {
                    _processingState.value = ProcessingState.Error("Could not extract frame from video.")
                }
            } catch (e: Exception) {
                Log.e("VideoProcessor", "Error loading video", e)
            }
        }
    }

    fun updatePreviewBlur() {
        val srcBitmap = rawPreviewBitmap ?: return
        val segmenter = imageSegmenter ?: return
        
        processingScope.launch {
            try {
                // Downscale for fast UI preview rendering
                val targetHeight = 480
                val targetWidth = (srcBitmap.width * (targetHeight.toFloat() / srcBitmap.height)).toInt()
                val workingBitmap = Bitmap.createScaledBitmap(srcBitmap, targetWidth, targetHeight, true)

                val processed = processSingleBitmapFrame(workingBitmap, segmenter)
                if (processed != null) {
                    withContext(Dispatchers.Main) {
                        _previewFrame.value = processed
                    }
                }
                workingBitmap.recycle()
            } catch (e: Exception) {
                Log.e("VideoProcessor", "Preview update failed", e)
            }
        }
    }

    // ─── 🚀 Full Video Export Processing ───
    fun processAndExportGalleryVideo() {
        val uri = selectedVideoUri ?: return
        val segmenter = imageSegmenter ?: return
        _processingState.value = ProcessingState.ProcessingVideo
        _exportProgress.value = 0f

        processingScope.launch {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                val isPortrait = rotation == 90 || rotation == 270
                val originalW = if (isPortrait) videoHeight else videoWidth
                val originalH = if (isPortrait) videoWidth else videoHeight

                // Target Resolutions maintaining aspect ratio
                val exportHeight = when (resolution.value) {
                    ResolutionType.RES_1080P -> 1920
                    ResolutionType.RES_720P -> 1280
                    ResolutionType.RES_480P -> 854
                }
                val exportWidth = (originalW * (exportHeight.toFloat() / originalH)).toInt()

                val calculatedBitrate = when (resolution.value) {
                    ResolutionType.RES_1080P -> 10000000 
                    ResolutionType.RES_720P -> 5000000   
                    ResolutionType.RES_480P -> 2000000   
                }

                val tempOutputFile = File(context.cacheDir, "Cinematic_${System.currentTimeMillis()}.mp4")
                val encoder = VideoEncoder(context, tempOutputFile, exportWidth, exportHeight, bitRate = calculatedBitrate)
                encoder.start()

                // Assume 30 fps for smooth extraction mapping
                val fps = 30f
                val totalFrames = ((durationMs / 1000f) * fps).toInt().coerceAtLeast(1)

                for (frameIdx in 0 until totalFrames) {
                    val timeUs = (frameIdx * 1000000L / fps).toLong()
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    
                    if (frame != null) {
                        val scaledFrame = Bitmap.createScaledBitmap(frame, exportWidth, exportHeight, true)
                        val outputBlurredFrame = processSingleBitmapFrame(scaledFrame, segmenter)
                        
                        if (outputBlurredFrame != null) {
                            encoder.encodeFrame(outputBlurredFrame, frameIdx * 33333333L)
                            outputBlurredFrame.recycle()
                        }
                        scaledFrame.recycle()
                        frame.recycle()
                    }
                    _exportProgress.value = (frameIdx.toFloat() / totalFrames).coerceIn(0f, 1f)
                }

                val savedUri = encoder.stop()
                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        _lastSavedVideoUri.value = savedUri
                        _processingState.value = ProcessingState.Success(savedUri)
                    } else {
                        _processingState.value = ProcessingState.Error("Export failed during muxing.")
                    }
                }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("Export processing error: ${e.localizedMessage}")
            } finally {
                try { retriever.release() } catch(e: Exception){}
            }
        }
    }

    private fun processSingleBitmapFrame(src: Bitmap, segmenter: ImageSegmenter): Bitmap? {
        val mpImage = BitmapImageBuilder(src).build()
        val result = segmenter.segment(mpImage)
        val masksOpt = result.confidenceMasks()
        val maskImage = if (masksOpt != null && masksOpt.isPresent) masksOpt.get()[0] else return null

        val byteBuffer = ByteBufferExtractor.extract(maskImage)
        val confidenceBuffer = byteBuffer.asFloatBuffer()

        val blurredBitmap = creamyCPUBlur(src, depthIntensity.value)
        val blendedFrame = blendLayersNatively(src, blurredBitmap, confidenceBuffer, warmth.value)

        blurredBitmap.recycle()
        return blendedFrame
    }

    private fun creamyCPUBlur(src: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.05f) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        
        val scale = 0.20f - (intensity * 0.12f)
        val w = Math.max(16, (src.width * scale).toInt())
        val h = Math.max(16, (src.height * scale).toInt())

        var current = Bitmap.createScaledBitmap(src, w, h, true)
        for (i in 0 until 2) {
            val temp = boxBlurCPU(current)
            current.recycle()
            current = temp
        }

        val finalOutput = Bitmap.createScaledBitmap(current, src.width, src.height, true)
        current.recycle()
        return finalOutput
    }

    private fun boxBlurCPU(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val out = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        
        for (y in 1 until h - 1) {
            val offset = y * w
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (ky in -1..1) {
                    val kOffset = offset + (ky * w)
                    for (kx in -1..1) {
                        val p = pixels[kOffset + (x + kx)]
                        r += (p ushr 16) and 0xff
                        g += (p ushr 8) and 0xff
                        b += p and 0xff
                    }
                }
                out[offset + x] = (255 shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        val res = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }

    private fun blendLayersNatively(original: Bitmap, blurred: Bitmap, mask: FloatBuffer, warmthVal: Float): Bitmap {
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
            val maskVal = if (i < mask.limit()) mask.get(i) else 0.5f
            val smoothedMask = maskVal * maskVal * (3f - 2f * maskVal)
            val op = origPixels[i]
            val bp = blurPixels[i]
            
            var outR = (((op ushr 16) and 0xff) * smoothedMask + ((bp ushr 16) and 0xff) * (1f - smoothedMask)).toInt()
            var outG = (((op ushr 8) and 0xff) * smoothedMask + ((bp ushr 8) and 0xff) * (1f - smoothedMask)).toInt()
            var outB = ((op and 0xff) * smoothedMask + (bp and 0xff) * (1f - smoothedMask)).toInt()
            
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

    fun resetState() {
        _processingState.value = ProcessingState.Ready
        _lastSavedVideoUri.value = null
        selectedVideoUri = null
        _previewFrame.value = null
        _exportProgress.value = 0f
    }
}
