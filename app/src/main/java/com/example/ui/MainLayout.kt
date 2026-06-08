package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blur.ResolutionType
import com.example.blur.CameraProcessManager
import com.example.blur.ProcessingState

@Composable
fun MainLayout(
    cameraProcessManager: CameraProcessManager,
    modifier: Modifier = Modifier
) {
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
    val previewFrame by cameraProcessManager.previewFrame.collectAsStateWithLifecycle()
    val exportProgress by cameraProcessManager.exportProgress.collectAsStateWithLifecycle()
    val lastVideoUri by cameraProcessManager.lastSavedVideoUri.collectAsStateWithLifecycle()
    
    val currentRes by cameraProcessManager.resolution.collectAsStateWithLifecycle()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { cameraProcessManager.loadVideoForPreview(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CINEMATIC STUDIO", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.5.sp)
                Text("OFFLINE VIDEO EDITOR", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }

        // Preview Window
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141417))
                .clickable { if (processingState !is ProcessingState.ProcessingVideo) galleryLauncher.launch("video/*") },
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
                previewFrame?.let { bitmap ->
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Studio Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = "Add Video", tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("TAP TO SELECT VIDEO", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Controls
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val currentDepth by cameraProcessManager.depthIntensity.collectAsStateWithLifecycle()
            val currentWarmth by cameraProcessManager.warmth.collectAsStateWithLifecycle()

            // Sliders Update Preview Automatically
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF141417), RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Blur Intensity", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Slider(
                        value = currentDepth, 
                        onValueChange = { 
                            cameraProcessManager.depthIntensity.value = it 
                        },
                        onValueChangeFinished = {
                            cameraProcessManager.updatePreviewBlur() // Update UI when sliding is done
                        },
                        modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                    Text("${(currentDepth * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Color Temp", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Slider(
                        value = currentWarmth, 
                        onValueChange = { 
                            cameraProcessManager.warmth.value = it 
                        },
                        onValueChangeFinished = {
                            cameraProcessManager.updatePreviewBlur()
                        },
                        modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.Yellow, activeTrackColor = Color.Yellow))
                    Text(if (currentWarmth > 0.55f) "Warm" else if (currentWarmth < 0.45f) "Cool" else "Neut", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }
            }

            // Resolution and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.height(34.dp).background(Color(0xFF141417), RoundedCornerShape(20.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(ResolutionType.RES_480P to "480p", ResolutionType.RES_720P to "720p", ResolutionType.RES_1080P to "1080p").forEach { (type, label) ->
                        val selected = currentRes == type
                        Box(modifier = Modifier.fillMaxHeight().width(56.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Color.Yellow else Color.Transparent).clickable { cameraProcessManager.resolution.value = type }, contentAlignment = Alignment.Center) {
                            Text(label, color = if (selected) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            // Separate Select and Export Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { galleryLauncher.launch("video/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141417)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp).weight(1f)
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SELECT VIDEO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = { cameraProcessManager.processAndExportGalleryVideo() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp).weight(1f),
                    enabled = previewFrame != null && processingState !is ProcessingState.ProcessingVideo
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("EXPORT", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            }
        }
    }

    if (processingState is ProcessingState.Success) {
        AlertDialog(
            onDismissRequest = { cameraProcessManager.resetState() },
            confirmButton = { TextButton(onClick = { cameraProcessManager.resetState() }) { Text("AWESOME", color = Color.Yellow, fontWeight = FontWeight.Black) } },
            title = { Text("CINEMATIC EXPORT COMPLETED") },
            text = { Text("Video successfully processed and saved to your gallery:\n\n${lastVideoUri?.path}") },
            containerColor = Color(0xFF141417),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    } else if (processingState is ProcessingState.Error) {
        AlertDialog(
            onDismissRequest = { cameraProcessManager.resetState() },
            confirmButton = { TextButton(onClick = { cameraProcessManager.resetState() }) { Text("OK", color = Color.Red, fontWeight = FontWeight.Black) } },
            title = { Text("ERROR") },
            text = { Text((processingState as ProcessingState.Error).message) },
            containerColor = Color(0xFF141417),
            titleContentColor = Color.Red,
            textContentColor = Color.LightGray
        )
    }
}
