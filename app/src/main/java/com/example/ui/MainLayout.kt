package com.example.ui

import android.Manifest
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blur.AspectRatioType
import com.example.blur.ResolutionType
import com.example.blur.CameraProcessManager
import com.example.blur.ProcessingState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainLayout(
    cameraProcessManager: CameraProcessManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val selector = CameraSelector.DEFAULT_FRONT_CAMERA

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), cameraProcessManager)

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
                } catch (e: Exception) {
                    Log.e("MainLayout", "Preview engine configuration leakage prevented", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070708))
    ) {
        VideoStudioContent(cameraProcessManager = cameraProcessManager)
    }
}

@Composable
fun VideoStudioContent(cameraProcessManager: CameraProcessManager) {
    val processingState by cameraProcessManager.processingState.collectAsStateWithLifecycle()
    val frameBitmap by cameraProcessManager.latestFrame.collectAsStateWithLifecycle()
    val exportProgress by cameraProcessManager.exportProgress.collectAsStateWithLifecycle()
    val lastVideoUri by cameraProcessManager.lastSavedVideoUri.collectAsStateWithLifecycle()
    
    val currentAspect by cameraProcessManager.aspectRatio.collectAsStateWithLifecycle()
    val currentRes by cameraProcessManager.resolution.collectAsStateWithLifecycle()
    val isRecording by cameraProcessManager.isRecording.collectAsStateWithLifecycle()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { cameraProcessManager.processAndExportGalleryVideo(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CINEMATIC STUDIO", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.5.sp)
                Text("OFFLINE VIDEO BOKEH PROCESSING", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141417)),
            contentAlignment = Alignment.Center
        ) {
            if (processingState is ProcessingState.ProcessingVideo) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { exportProgress }, color = Color.Yellow, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("RENDERING HIGH-RES BOKEH...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${(exportProgress * 100).toInt()}% COMPLETED", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                frameBitmap?.let { bitmap ->
                    if (currentAspect == AspectRatioType.RATIO_1_1) {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Studio", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                    } else {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Studio", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Live", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("STARTING LIVE CAMERA PREVIEW...", color = Color.Gray, fontSize = 12.sp)
                }
            }

            if (isRecording) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color.Red, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RENDERING FRAME ENGINE PASS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val currentDepth by cameraProcessManager.depthIntensity.collectAsStateWithLifecycle()
            val currentWarmth by cameraProcessManager.warmth.collectAsStateWithLifecycle()

            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF141417), RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Bokeh Intensity", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Slider(value = currentDepth, onValueChange = { cameraProcessManager.depthIntensity.value = it }, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                    Text("${(currentDepth * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Color Temperature", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Slider(value = currentWarmth, onValueChange = { cameraProcessManager.warmth.value = it }, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.Yellow, activeTrackColor = Color.Yellow))
                    Text(if (currentWarmth > 0.55f) "Warm" else if (currentWarmth < 0.45f) "Cool" else "Neut", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.height(34.dp).background(Color(0xFF141417), RoundedCornerShape(20.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(AspectRatioType.RATIO_9_16 to "9:16", AspectRatioType.RATIO_1_1 to "1:1").forEach { (type, label) ->
                            val selected = currentAspect == type
                            Box(modifier = Modifier.fillMaxHeight().width(46.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color.White else Color.Transparent).clickable { cameraProcessManager.aspectRatio.value = type }, contentAlignment = Alignment.Center) {
                                Text(label, color = if (selected) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    Row(modifier = Modifier.height(34.dp).background(Color(0xFF141417), RoundedCornerShape(20.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(ResolutionType.RES_480P to "480p", ResolutionType.RES_720P to "720p", ResolutionType.RES_1080P to "1080p").forEach { (type, label) ->
                            val selected = currentRes == type
                            Box(modifier = Modifier.fillMaxHeight().width(46.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color.Yellow else Color.Transparent).clickable { cameraProcessManager.resolution.value = type }, contentAlignment = Alignment.Center) {
                                Text(label, color = if (selected) Color.Black else Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }

                Button(
                    onClick = { galleryLauncher.launch("video/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.height(56.dp).weight(1f).padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SELECT & EXPORT", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }

    if (processingState is ProcessingState.Success) {
        AlertDialog(
            onDismissRequest = { cameraProcessManager.resetState() },
            confirmButton = { TextButton(onClick = { cameraProcessManager.resetState() }) { Text("AWESOME", color = Color.Yellow, fontWeight = FontWeight.Black) } },
            title = { Text("CINEMATIC EXPORT COMPLETED") },
            text = { Text("Your processed video with smoothed polynomial mask blending was saved perfectly with zero lag configuration:\n\n${lastVideoUri?.path}") },
            containerColor = Color(0xFF141417),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}
