package com.example.blur

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class VideoEncoder(
    private val context: Context,
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = 3000000, // Dynamic map structure overrides this via constructor signature
    private val frameRate: Int = 30,
    private val iframeInterval: Int = 1
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isMuxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var ptsUsOffset: Long = -1L
    private val yuvBuffer = ByteArray(width * height * 3 / 2)

    fun start() {
        val finalW = if (width % 2 == 0) width else width - 1
        val finalH = if (height % 2 == 0) height else height - 1

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, finalW, finalH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) // NV12 format tracking profile
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate) // Injecting targeted dynamic bitrate seamlessly
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval)
        }

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            ptsUsOffset = -1L
            trackIndex = -1
            isMuxerStarted = false
            Log.d("VideoEncoder", "VideoEncoder initialized perfectly with dynamic Bitrate properties: $bitRate bps for $finalW x $finalH")
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to start processing MediaCodec / MediaMuxer pipelines", e)
            release()
            throw e
        }
    }

    fun encodeFrame(bitmap: Bitmap, presentationTimeNs: Long) {
        val codec = mediaCodec ?: return
        
        val finalW = if (width % 2 == 0) width else width - 1
        val finalH = if (height % 2 == 0) height else height - 1

        convertBitmapToNV12(bitmap, yuvBuffer, finalW, finalH)

        val inputBufferIndex = codec.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(yuvBuffer)
            
            if (ptsUsOffset == -1L) {
                ptsUsOffset = presentationTimeNs / 1000L
            }
            val presentationTimeUs = (presentationTimeNs / 1000L) - ptsUsOffset
            
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                yuvBuffer.size,
                presentationTimeUs,
                0
            )
        }

        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        if (endOfStream) {
            try {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } catch (e: Exception) {
                Log.e("VideoEncoder", "Error queuing terminal stream EOS flag", e)
            }
        }

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    Log.w("VideoEncoder", "Format configurations duplicated unexpectedly, bypassing track addition")
                    break
                }
                val newFormat = codec.outputFormat
                trackIndex = muxer.addTrack(newFormat)
                muxer.start()
                isMuxerStarted = true
                Log.d("VideoEncoder", "Muxer pipeline active. Registered track token: $trackIndex")
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!isMuxerStarted) {
                        Log.w("VideoEncoder", "Multiplexer stream layout uninitialized. Dropping current frame packet.")
                    } else {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d("VideoEncoder", "Video packet data tracking loop successfully intercepted final EOS flag")
                    break
                }
            }
        }
    }

    fun stop(): Uri? {
        Log.d("VideoEncoder", "Terminating active MediaCodec lifecycle structures")
        drainEncoder(true)
        release()
        
        val uri = saveVideoToGallery(context, outputFile, "PortraitBlur_${System.currentTimeMillis()}")
        Log.d("VideoEncoder", "Asset registry callback execution completed. Uri link destination: $uri")
        return uri
    }

    private fun release() {
        try {
            mediaCodec?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Codec hardware framework exception encountered on destruction sequence", e)
        }
        mediaCodec = null

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
                mediaMuxer?.release()
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Muxer layout execution crashed on terminal stage release process", e)
        }
        mediaMuxer = null
        isMuxerStarted = false
        trackIndex = -1
    }

    private fun convertBitmapToNV12(bitmap: Bitmap, yuv: ByteArray, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        
        var yIndex = 0
        var uvIndex = width * height
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
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
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        
        val uri = resolver.insert(collection, contentValues)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                Log.e("GallerySave", "File allocation matrix linkage storage failure encountered", e)
                return null
            }
        }
        return uri
    }
}
