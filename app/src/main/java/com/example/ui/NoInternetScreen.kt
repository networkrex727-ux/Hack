package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NoInternetScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }

    // Infinite transition for pulsing radar waves
    val infiniteTransition = rememberInfiniteTransition(label = "RadarWaves")
    
    // Wave 1 animations
    val wave1Radius by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave1Radius"
    )
    val wave1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave1Alpha"
    )

    // Wave 2 animations (staggered by 1000ms)
    val wave2Radius by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave2Radius"
    )
    val wave2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave2Alpha"
    )

    // Glowing core pulse
    val coreScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CorePulse"
    )

    // Button spin animation when loading/checking
    val rotationAngle by animateFloatAsState(
        targetValue = if (isChecking) 360f else 0f,
        animationSpec = if (isChecking) {
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            snap()
        },
        label = "BtnRotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A), // Deep Slate Dark
                        Color(0xFF020617)  // Midnight Dark
                    )
                )
            )
            .padding(24.dp)
            .testTag("no_internet_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // pulsing radar signal
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .testTag("network_status_indicator"),
                contentAlignment = Alignment.Center
            ) {
                // Drawing waves on Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val centerPoint = androidx.compose.ui.geometry.Offset(canvasWidth / 2, canvasHeight / 2)

                    // Wave 1
                    drawCircle(
                        color = Color(0xFFEF4444),
                        radius = wave1Radius.dp.toPx(),
                        center = centerPoint,
                        alpha = wave1Alpha,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
                        )
                    )

                    // Wave 2
                    drawCircle(
                        color = Color(0xFFEF4444),
                        radius = wave2Radius.dp.toPx(),
                        center = centerPoint,
                        alpha = wave2Alpha,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 15f), 0f)
                        )
                    )
                }

                // Central high-tech neon glowing warning orb
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = coreScale
                            scaleY = coreScale
                        }
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF5F5F),
                                    Color(0xFFEF4444),
                                    Color(0xFF7F1D1D)
                                )
                            ),
                            shape = CircleShape
                        )
                        .border(1.5.dp, Color(0xFFFECACA), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Warning Title
            Text(
                text = "CONNECTION INTERRUPTED",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Body text description explaining that active internet connection is required
            Text(
                text = "Wingo Prediction requires an active network connection to synchronize real-time game states and generate hack forecasts.",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // High-fidelity custom retry button complying with 48dp target standard
            Box(
                modifier = Modifier
                    .height(52.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF059669), // Rich Emerald Green
                                Color(0xFF10B981)  // Neon Emerald Accent
                            )
                        )
                    )
                    .clickable(
                        enabled = !isChecking,
                        onClick = {
                            coroutineScope.launch {
                                isChecking = true
                                delay(1200) // Beautiful simulated brief check animation
                                isChecking = false
                                onRetry()
                            }
                        }
                    )
                    .testTag("retry_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry Connection",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                rotationZ = rotationAngle
                            }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isChecking) "DIAGNOSING NETWORK..." else "RETRY CONNECTION",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Troubleshooting checklist card
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .border(BorderStroke(1.dp, Color(0xFF1E293B)), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1329)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Quick Checklist:",
                        color = Color(0xFFF1F5F9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    
                    TroubleshootItem(text = "Verify Wi-Fi or cellular data signal is active.")
                    TroubleshootItem(text = "Ensure airplane mode is turned off.")
                    TroubleshootItem(text = "Check if other applications can access the web.")
                }
            }
        }
    }
}

@Composable
fun TroubleshootItem(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color(0xFFEF4444), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
