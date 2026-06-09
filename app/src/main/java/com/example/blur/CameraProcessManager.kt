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
import kotlin.math.max

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
                // Keep preview resolution low (480p) for instant UI updates
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
                    ResolutionType.RES_1080P -> 8000000
                    ResolutionType.RES_720P -> 4000000
                    ResolutionType.RES_480P -> 1500000
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
                        // Native C++ resizing
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

    // ─── 🚀 THE 10x SPEED OPTIMIZATION BLOCK ───
    private fun processSingleBitmapFrame(src: Bitmap, segmenter: ImageSegmenter): Bitmap? {
        val mpImage = BitmapImageBuilder(src).build()
        
        // FIX: Renamed variable to avoid conflict
        val segmentationResult = segmenter.segment(mpImage)
        val masksOpt = segmentationResult.confidenceMasks()
        val maskImage = if (masksOpt != null && masksOpt.isPresent) masksOpt.get()[0] else return null

        val byteBuffer = ByteBufferExtractor.extract(maskImage)
        val confidenceBuffer = byteBuffer.asFloatBuffer()

        val w = src.width
        val h = src.height

        // 1. FAST NATIVE MASK GENERATION
        val maskW = maskImage.width
        val maskH = maskImage.height
        val maskPixels = IntArray(maskW * maskH)
        
        // Loop over small 256x256 mask only (Not 2 Million pixels)
        for (i in 0 until (maskW * maskH)) {
            val conf = confidenceBuffer.get(i)
            // Smoothstep curve
            val t = conf.coerceIn(0f, 1f)
            val fg = t * t * (3f - 2f * t)
            val alpha = (fg * 255).toInt().coerceIn(0, 255)
            // Save as Greyscale Image Array
            maskPixels[i] = (255 shl 24) or (alpha shl 16) or (alpha shl 8) or alpha
        }
        
        val smallMaskBmp = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        smallMaskBmp.setPixels(maskPixels, 0, maskW, 0, 0, maskW, maskH)
        
        // Native C++ Engine automatically interpolates/feathers edges during upscale
        val smoothMaskHW = Bitmap.createScaledBitmap(smallMaskBmp, w, h, true)
        smallMaskBmp.recycle()

        // 2. FAST NATIVE BLUR GENERATION
        val intensity = depthIntensity.value
        val blurredBgHW = if (intensity > 0.05f) {
            // Extreme downscale (5% to 15%)
            val blurScale = 0.15f - (intensity * 0.10f)
            val smallW = max(16, (w * blurScale).toInt())
            val smallH = max(16, (h * blurScale).toInt())
            
            val tinyBg = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val tinyBlurred = fastBoxBlur(tinyBg) // 1 pass only
            tinyBg.recycle()
            
            // Upscaling heavily smoothed tiny image natively gives high-quality DSLR look instantly
            val heavyBlur = Bitmap.createScaledBitmap(tinyBlurred, w, h, true)
            tinyBlurred.recycle()
            heavyBlur
        } else {
            src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        }

        // 3. FAST BLENDING
        val origPixels = IntArray(w * h)
        val blurPixels = IntArray(w * h)
        val maskFullPixels = IntArray(w * h)
        val outPixels = IntArray(w * h)

        src.getPixels(origPixels, 0, w, 0, 0, w, h)
        blurredBgHW.getPixels(blurPixels, 0, w, 0, 0, w, h)
        smoothMaskHW.getPixels(maskFullPixels, 0, w, 0, 0, w, h)

        val warmthVal = warmth.value
        val rFactor = if (warmthVal > 0.5f) 1f + (warmthVal - 0.5f) * 0.2f else 1f - (0.5f - warmthVal) * 0.15f
        val bFactor = if (warmthVal > 0.5f) 1f - (warmthVal - 0.5f) * 0.15f else 1f + (0.5f - warmthVal) * 0.2f

        for (i in 0 until (w * h)) {
            // Extract alpha from grey mask (0-255)
            val fgAlpha = maskFullPixels[i] and 0xFF 
            val fgRatio = fgAlpha / 255f
            val bgRatio = 1f - fgRatio

            val op = origPixels[i]
            val bp = blurPixels[i]

            var outR = (((op ushr 16) and 0xff) * fgRatio + ((bp ushr 16) and 0xff) * bgRatio).toInt()
            var outG = (((op ushr 8) and 0xff) * fgRatio + ((bp ushr 8) and 0xff) * bgRatio).toInt()
            var outB = ((op and 0xff) * fgRatio + (bp and 0xff) * bgRatio).toInt()

            if (warmthVal != 0.5f) {
                outR = (outR * rFactor).toInt().coerceIn(0, 255)
                outB = (outB * bFactor).toInt().coerceIn(0, 255)
            }

            outPixels[i] = (255 shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        // FIX: Renamed final output variable
        val finalOutputBitmap = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        finalOutputBitmap.setPixels(outPixels, 0, w, 0, 0, w, h)

        blurredBgHW.recycle()
        smoothMaskHW.recycle()
        return finalOutputBitmap
    }

    private fun fastBoxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val out = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        System.arraycopy(pixels, 0, out, 0, pixels.size)
        
        for (y in 1 until h - 1) {
            val offset = y * w
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (ky in -1..1) {
                    val kOffset = offset + (ky * w)
                    for (kx in -1..1) {
                        val p = pixels[kOffset + x + kx]
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
