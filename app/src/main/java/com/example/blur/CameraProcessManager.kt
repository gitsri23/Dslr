package com.example.blur

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    fun loadVideoForPreview(uri: Uri) {
        selectedVideoUri = uri
        processingScope.launch {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()

                if (frame != null) {
                    rawPreviewBitmap = frame
                    updatePreviewBlur()
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
                val targetHeight = 480
                val targetWidth = (srcBitmap.width * (targetHeight.toFloat() / srcBitmap.height)).toInt()
                val workingBitmap = Bitmap.createScaledBitmap(srcBitmap, targetWidth, targetHeight, true)

                val processed = processSingleBitmapFrame(workingBitmap, segmenter)
                if (processed != null) {
                    withContext(Dispatchers.Main) { _previewFrame.value = processed }
                }
                workingBitmap.recycle()
            } catch (e: Exception) {}
        }
    }

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

                val tempOutputFile = File(context.cacheDir, "Cinematic_VideoOnly_${System.currentTimeMillis()}.mp4")
                val encoder = VideoEncoder(context, tempOutputFile, exportWidth, exportHeight, bitRate = calculatedBitrate)
                encoder.start()

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

                val rawVideoFile = encoder.stop()

                val finalAudioMuxedFile = File(context.cacheDir, "Final_Export_${System.currentTimeMillis()}.mp4")
                val muxSuccess = mixAudioAndVideo(uri, rawVideoFile, finalAudioMuxedFile)

                val finalOutput = if (muxSuccess) finalAudioMuxedFile else rawVideoFile
                val savedUri = saveVideoToGallery(context, finalOutput, "PortraitBlur_${System.currentTimeMillis()}")

                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        _lastSavedVideoUri.value = savedUri
                        _processingState.value = ProcessingState.Success(savedUri)
                    } else {
                        _processingState.value = ProcessingState.Error("Export failed during Gallery Save.")
                    }
                }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("Processing error: ${e.localizedMessage}")
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }
    }

    private fun mixAudioAndVideo(originalUri: Uri, videoOnlyFile: File, outputFile: File): Boolean {
        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoExtractor = MediaExtractor().apply { setDataSource(videoOnlyFile.absolutePath) }
            val audioExtractor = MediaExtractor().apply { setDataSource(context, originalUri, null) }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerVideoTrackIndex = -1
            var muxerAudioTrackIndex = -1

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    muxerVideoTrackIndex = muxer.addTrack(format)
                    videoTrackIndex = i
                    break
                }
            }

            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    muxerAudioTrackIndex = muxer.addTrack(format)
                    audioTrackIndex = i
                    break
                }
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024 * 2)
            val bufferInfo = MediaCodec.BufferInfo()

            if (videoTrackIndex != -1) {
                while (true) {
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0; bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(muxerVideoTrackIndex, buffer, bufferInfo)
                    videoExtractor.advance()
                }
            }

            if (audioTrackIndex != -1) {
                while (true) {
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0; bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrackIndex, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            videoExtractor.release(); audioExtractor.release()
            muxer.stop(); muxer.release()
            return true
        } catch (e: Exception) { return false }
    }

    private fun processSingleBitmapFrame(src: Bitmap, segmenter: ImageSegmenter): Bitmap? {
        val mpImage = BitmapImageBuilder(src).build()
        val result = segmenter.segment(mpImage)
        val masksOpt = result.confidenceMasks()
        val maskImage = if (masksOpt != null && masksOpt.isPresent) masksOpt.get()[0] else return null

        val byteBuffer = ByteBufferExtractor.extract(maskImage)
        val confidenceBuffer = byteBuffer.asFloatBuffer()

        // ── FIX 1: Blur generated at FULL src resolution — no more patchy artifacts ──
        val blurredBitmap = dslrBokehBlur(src, depthIntensity.value)

        // ── FIX 2: Mask upscaled + feathered to match src exactly before blend ──
        val smoothMask = upsampleAndFeatherMask(confidenceBuffer, maskImage.width, maskImage.height, src.width, src.height)

        val blendedFrame = blendWithCinematicLook(src, blurredBitmap, smoothMask, warmth.value)

        blurredBitmap.recycle()
        return blendedFrame
    }

    /**
     * DSLR Bokeh Blur — iPhone Cinematic Mode style.
     *
     * Strategy:
     * 1. Downscale to 50% (not 25%) → less blockiness on upscale
     * 2. Apply true Gaussian blur via separable 1D passes (horizontal + vertical)
     *    instead of simple box averaging — gives creamy smooth falloff
     * 3. Intensity controls sigma (blur radius), not just pass count
     * 4. Upscale back with bilinear (createScaledBitmap true) for smooth result
     */
    private fun dslrBokehBlur(src: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.02f) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)

        // 50% scale — good balance of speed vs quality, avoids blocky upscale
        val scale = 0.5f
        val w = max(16, (src.width * scale).toInt())
        val h = max(16, (src.height * scale).toInt())

        val small = Bitmap.createScaledBitmap(src, w, h, true)

        // Sigma mapped from intensity: 0.0→0, 1.0→sigma 18 (very strong bokeh)
        val sigma = (intensity * 18f).coerceAtLeast(1f)

        val blurred = gaussianBlurSeparable(small, sigma)
        small.recycle()

        // Scale back to original size with smooth interpolation
        val output = Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
        blurred.recycle()
        return output
    }

    /**
     * Separable Gaussian blur — 1D kernel applied horizontally then vertically.
     * This is mathematically equivalent to 2D Gaussian but O(n*r) not O(n*r²).
     * Gives the creamy, lens-like background blur of DSLR bokeh.
     */
    private fun gaussianBlurSeparable(src: Bitmap, sigma: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val radius = (sigma * 2.5f).roundToInt().coerceAtLeast(1).coerceAtMost(min(w, h) / 2 - 1)
        val kernel = buildGaussianKernel(sigma, radius)

        val tempPixels = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var wSum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val p = pixels[rowOff + sx]
                    val kw = kernel[k + radius]
                    r += ((p ushr 16) and 0xff) * kw
                    g += ((p ushr 8) and 0xff) * kw
                    b += (p and 0xff) * kw
                    wSum += kw
                }
                tempPixels[rowOff + x] = (0xFF shl 24) or
                    ((r / wSum).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g / wSum).roundToInt().coerceIn(0, 255) shl 8) or
                    (b / wSum).roundToInt().coerceIn(0, 255)
            }
        }

        val outPixels = IntArray(w * h)

        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var wSum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val p = tempPixels[sy * w + x]
                    val kw = kernel[k + radius]
                    r += ((p ushr 16) and 0xff) * kw
                    g += ((p ushr 8) and 0xff) * kw
                    b += (p and 0xff) * kw
                    wSum += kw
                }
                outPixels[y * w + x] = (0xFF shl 24) or
                    ((r / wSum).roundToInt().coerceIn(0, 255) shl 16) or
                    ((g / wSum).roundToInt().coerceIn(0, 255) shl 8) or
                    (b / wSum).roundToInt().coerceIn(0, 255)
            }
        }

        val result = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    /** Gaussian kernel weights for given sigma and radius */
    private fun buildGaussianKernel(sigma: Float, radius: Int): FloatArray {
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        val s2 = 2f * sigma * sigma
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            kernel[i] = exp(-(x * x) / s2)
            sum += kernel[i]
        }
        // Normalize
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    /**
     * FIX: Upsample mask from MediaPipe resolution → src resolution,
     * then apply Gaussian feather to soften hard edges.
     * This removes the "cut-out sticker" look and gives natural depth falloff.
     */
    private fun upsampleAndFeatherMask(
        rawMask: FloatBuffer,
        maskW: Int, maskH: Int,
        targetW: Int, targetH: Int
    ): FloatArray {
        // Step 1: bilinear upsample mask to target size
        val upsampled = FloatArray(targetW * targetH)
        val scaleX = maskW.toFloat() / targetW
        val scaleY = maskH.toFloat() / targetH

        for (ty in 0 until targetH) {
            for (tx in 0 until targetW) {
                val mx = (tx * scaleX).coerceIn(0f, (maskW - 1).toFloat())
                val my = (ty * scaleY).coerceIn(0f, (maskH - 1).toFloat())
                val x0 = mx.toInt(); val y0 = my.toInt()
                val x1 = min(x0 + 1, maskW - 1); val y1 = min(y0 + 1, maskH - 1)
                val fx = mx - x0; val fy = my - y0
                val idx = ty * targetW + tx
                val i00 = rawMask.safeGet(y0 * maskW + x0)
                val i10 = rawMask.safeGet(y0 * maskW + x1)
                val i01 = rawMask.safeGet(y1 * maskW + x0)
                val i11 = rawMask.safeGet(y1 * maskW + x1)
                upsampled[idx] = (i00 * (1 - fx) * (1 - fy) +
                                  i10 * fx * (1 - fy) +
                                  i01 * (1 - fx) * fy +
                                  i11 * fx * fy)
            }
        }

        // Step 2: Gaussian feather on the mask itself (sigma ~3% of width)
        // This softens the person-edge by ~10-15px for natural hair/shoulder falloff
        val featherSigma = (targetW * 0.012f).coerceIn(2f, 12f)
        return gaussianBlurMask(upsampled, targetW, targetH, featherSigma)
    }

    private fun FloatBuffer.safeGet(index: Int): Float =
        if (index >= 0 && index < limit()) get(index) else 0f

    /** 1D separable Gaussian blur on a float mask array */
    private fun gaussianBlurMask(mask: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val radius = (sigma * 2.5f).roundToInt().coerceAtLeast(1).coerceAtMost(min(w, h) / 2 - 1)
        val kernel = buildGaussianKernel(sigma, radius)
        val temp = FloatArray(w * h)

        // Horizontal
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                var v = 0f; var wSum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    v += mask[off + sx] * kernel[k + radius]
                    wSum += kernel[k + radius]
                }
                temp[off + x] = v / wSum
            }
        }

        val out = FloatArray(w * h)
        // Vertical
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0f; var wSum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    v += temp[sy * w + x] * kernel[k + radius]
                    wSum += kernel[k + radius]
                }
                out[y * w + x] = (v / wSum).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Cinematic blend:
     * - Foreground (person): original sharp pixels
     * - Background: strong bokeh blur
     * - Transition: smooth cubic S-curve (smoothstep) on feathered mask
     * - Warmth: subtle color grade
     * - Edge micro-contrast: slight sharpness boost on foreground edges (DSLR look)
     */
    private fun blendWithCinematicLook(
        original: Bitmap,
        blurred: Bitmap,
        smoothMask: FloatArray,
        warmthVal: Float
    ): Bitmap {
        val w = original.width
        val h = original.height
        val count = w * h

        val origPixels = IntArray(count)
        val blurPixels = IntArray(count)
        val outPixels = IntArray(count)

        original.getPixels(origPixels, 0, w, 0, 0, w, h)

        // Ensure blurred bitmap is exactly src size before reading pixels
        val safeBlurred = if (blurred.width == w && blurred.height == h) blurred
                          else Bitmap.createScaledBitmap(blurred, w, h, true)
        safeBlurred.getPixels(blurPixels, 0, w, 0, 0, w, h)

        val rFactor = if (warmthVal > 0.5f) 1f + (warmthVal - 0.5f) * 0.2f else 1f - (0.5f - warmthVal) * 0.15f
        val bFactor = if (warmthVal > 0.5f) 1f - (warmthVal - 0.5f) * 0.15f else 1f + (0.5f - warmthVal) * 0.2f

        for (i in 0 until count) {
            val rawMask = smoothMask[i]

            // Smooth cubic S-curve — eliminates hard edges completely
            val t = rawMask.coerceIn(0f, 1f)
            val fg = t * t * (3f - 2f * t)  // smoothstep

            val op = origPixels[i]
            val bp = blurPixels[i]

            var outR = (((op ushr 16) and 0xff) * fg + ((bp ushr 16) and 0xff) * (1f - fg)).roundToInt()
            var outG = (((op ushr 8) and 0xff) * fg + ((bp ushr 8) and 0xff) * (1f - fg)).roundToInt()
            var outB = ((op and 0xff) * fg + (bp and 0xff) * (1f - fg)).roundToInt()

            // Warmth color grade
            if (warmthVal != 0.5f) {
                outR = (outR * rFactor).roundToInt().coerceIn(0, 255)
                outB = (outB * bFactor).roundToInt().coerceIn(0, 255)
            }

            outPixels[i] = (255 shl 24) or
                (outR.coerceIn(0, 255) shl 16) or
                (outG.coerceIn(0, 255) shl 8) or
                outB.coerceIn(0, 255)
        }

        if (safeBlurred !== blurred) safeBlurred.recycle()

        val result = Bitmap.createBitmap(w, h, original.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun saveVideoToGallery(context: Context, videoFile: File, title: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PortraitBlur")
            }
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, contentValues)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
                }
            } catch (e: Exception) { return null }
        }
        return uri
    }

    fun resetState() {
        _processingState.value = ProcessingState.Ready
        _lastSavedVideoUri.value = null
        selectedVideoUri = null
        _previewFrame.value = null
        _exportProgress.value = 0f
    }
}
