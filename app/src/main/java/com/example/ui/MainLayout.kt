package com.example.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainLayout(
    cameraProcessManager: CameraProcessManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    val isFrontCamera = remember { MutableStateFlow(true) }
    val isFrontCameraState by isFrontCamera.collectAsStateWithLifecycle()

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted, isFrontCameraState) {
        if (permissionsState.allPermissionsGranted) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val selector = if (isFrontCameraState) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), cameraProcessManager)

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
                } catch (e: Exception) {
                    Log.e("MainLayout", "Failed to bind camera lifecycle", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070708))
    ) {
        if (permissionsState.allPermissionsGranted) {
            CameraContent(cameraProcessManager = cameraProcessManager, isFrontCamera = isFrontCamera)
        } else {
            PermissionRequiredContent(onRequestPermissions = { permissionsState.launchMultiplePermissionRequest() })
        }
    }
}

@Composable
fun CameraContent(
    cameraProcessManager: CameraProcessManager,
    isFrontCamera: MutableStateFlow<Boolean>
) {
    val processingState by cameraProcessManager.processingState.collectAsStateWithLifecycle()
    val frameBitmap by cameraProcessManager.latestFrame.collectAsStateWithLifecycle()
    val isRecording by cameraProcessManager.isRecording.collectAsStateWithLifecycle()
    val recordDurationSec by cameraProcessManager.recordingDurationSec.collectAsStateWithLifecycle()
    val lastVideoUri by cameraProcessManager.lastSavedVideoUri.collectAsStateWithLifecycle()
    
    val currentAspect by cameraProcessManager.aspectRatio.collectAsStateWithLifecycle()
    val currentRes by cameraProcessManager.resolution.collectAsStateWithLifecycle()

    var showInfoDialog by remember { mutableStateOf(false) }

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
                Text(
                    text = "PORTRAIT BLUR",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "REAL-TIME DEPTH CINEMA",
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { showInfoDialog = true },
                    modifier = Modifier.size(40.dp).background(Color(0x22FFFFFF), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Info", tint = Color.White)
                }

                val isFront by isFrontCamera.collectAsStateWithLifecycle()
                IconButton(
                    onClick = { isFrontCamera.value = !isFront },
                    modifier = Modifier.size(40.dp).background(Color(0x22FFFFFF), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Flip", tint = Color.White)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141417))
        ) {
            when (val state = processingState) {
                ProcessingState.InitializingEngine -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("INITIALIZING PORTRAIT BLUR...", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
                ProcessingState.Ready -> {
                    frameBitmap?.let { bitmap ->
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (currentAspect == AspectRatioType.RATIO_1_1) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "1:1 Viewfinder",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "9:16 Viewfinder",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } ?: run {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("CONNECTING CAMERA...", color = Color.DarkGray, fontSize = 11.sp)
                        }
                    }
                }
                is ProcessingState.Error -> {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = "Error", tint = Color.Red, modifier = Modifier.size(48.dp))
                        Text(state.message, color = Color.Gray, textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                }
                else -> {}
            }

            if (isRecording) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color.Red, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("REC  %02d:%02d".format(recordDurationSec / 60, recordDurationSec % 60), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Depth", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Depth Blur", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Slider(
                        value = currentDepth, onValueChange = { cameraProcessManager.depthIntensity.value = it }, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Warmth", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Color Warm", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Slider(
                        value = currentWarmth, onValueChange = { cameraProcessManager.warmth.value = it }, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color.Red, activeTrackColor = Color.Red, inactiveTrackColor = Color.DarkGray)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Formatting Toggles (Aspect + Resolution)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.height(34.dp).background(Color(0xFF141417), RoundedCornerShape(20.dp)).padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(AspectRatioType.RATIO_9_16 to "9:16", AspectRatioType.RATIO_1_1 to "1:1").forEach { (type, label) ->
                            val selected = currentAspect == type
                            Box(
                                modifier = Modifier.fillMaxHeight().width(46.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color.White else Color.Transparent).clickable { cameraProcessManager.aspectRatio.value = type },
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = if (selected) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                        }
                    }

                    Row(
                        modifier = Modifier.height(34.dp).background(Color(0xFF141417), RoundedCornerShape(20.dp)).padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(ResolutionType.RES_720P to "720p", ResolutionType.RES_1080P to "1080p").forEach { (type, label) ->
                            val selected = currentRes == type
                            Box(
                                modifier = Modifier.fillMaxHeight().width(46.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color.Yellow else Color.Transparent).clickable { cameraProcessManager.resolution.value = type },
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = if (selected) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                        }
                    }
                }

                Box(
                    modifier = Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape).background(Color.Transparent).clickable {
                        if (isRecording) cameraProcessManager.stopRecording() else cameraProcessManager.startRecording()
                    },
                    contentAlignment = Alignment.Center
                ) {
                    val scale by animateFloatAsState(targetValue = if (isRecording) 0.85f else 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    Box(modifier = Modifier.fillMaxSize().scale(scale).background(color = if (isRecording) Color.Red else Color.White, shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape))
                }

                Box(
                    modifier = Modifier.size(42.dp).background(Color(0xFF141417), CircleShape).clickable { showInfoDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastVideoUri != null) Icon(imageVector = Icons.Default.Check, contentDescription = "Saved", tint = Color.Green, modifier = Modifier.size(24.dp))
                    else Box(modifier = Modifier.size(12.dp).border(1.5.dp, Color.Gray, CircleShape))
                }
            }
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("OK", color = Color.White) } },
            title = { Text("PORTRAIT BLUR v2", fontWeight = FontWeight.Black, fontSize = 14.sp) },
            text = { Text("Added Smoothstep Polynomial Edge Blending for DSLR-like hair feathering. Added dynamic 1080p encoding at 8Mbps.", fontSize = 12.sp, color = Color.LightGray) },
            containerColor = Color(0xFF141417)
        )
    }
}

@Composable
fun PermissionRequiredContent(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("GRANT CAMERA PERMISSION", color = Color.Black, fontWeight = FontWeight.Black)
        }
    }
}
