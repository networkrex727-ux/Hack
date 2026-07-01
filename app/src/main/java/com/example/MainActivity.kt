package com.example

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WingoAppScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WingoAppScreen() {
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
    val viewModel: PredictionViewModel = viewModel()
    val coroutineScope = rememberCoroutineScope()

    // Configuration / URL States
    var websiteUrlState by remember { mutableStateOf(session.websiteUrl) }
    var baseUrlState by remember { mutableStateOf(session.baseUrl) }
    var periodOffsetState by remember { mutableStateOf(session.periodOffset) }
    var timeOffsetState by remember { mutableStateOf(session.timeOffset) }
    var isAdminModeState by remember { mutableStateOf(session.isAdminMode) }

    // Session States
    var isLoggedIn by remember { mutableStateOf(session.isLoggedIn) }
    var userId by remember { mutableStateOf(session.userId) }

    // Bottom Sheet State
    var showBottomSheet by remember { mutableStateOf(false) }
    var isSettingsMode by remember { mutableStateOf(false) }
    
    // Deposit Log Dialog States
    var showDepositDialog by remember { mutableStateOf(false) }
    var depositAmountInput by remember { mutableStateOf("5000") }
    var depositUtrInput by remember { mutableStateOf("") }

    // Floating Widget Drag States
    var floatingYOffset by remember { mutableStateOf(0f) }
    var floatingXOffset by remember { mutableStateOf(0f) }
    var isMiniScreenExpanded by remember { mutableStateOf(false) }

    // Developer Secret Mode
    var developerTapCount by remember { mutableStateOf(0) }
    var isDeveloperMode by remember { mutableStateOf(false) }

    // Bypasses / Overrides for Developer Testing
    var bypassLogin by remember { mutableStateOf(false) }
    var forceHackActive by remember { mutableStateOf(false) }

    // Live Data flows from ViewModel
    val currentPeriod by viewModel.currentPeriod.collectAsState()
    val timeLeft by viewModel.timeLeft.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val hackActiveState by viewModel.hackActive.collectAsState()
    val prediction by viewModel.prediction.collectAsState()
    val depositInfo by viewModel.depositInfo.collectAsState()
    val depInfo = depositInfo
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Effective states including developer overrides
    val effectiveIsLoggedIn = isLoggedIn || bypassLogin
    val effectiveHackActive = hackActiveState || forceHackActive

    // Track previous login state to play the custom voice pack exactly when login changes from false to true,
    // preventing it from playing at app startup when already logged in.
    var previouslyLoggedIn by remember { mutableStateOf(effectiveIsLoggedIn) }
    var activeMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var lastVoicePlayTime by remember { mutableStateOf(0L) }

    LaunchedEffect(effectiveIsLoggedIn) {
        val currentTime = System.currentTimeMillis()
        if (effectiveIsLoggedIn && !previouslyLoggedIn) {
            if (currentTime - lastVoicePlayTime > 10000) { // 10 seconds cooldown
                lastVoicePlayTime = currentTime
                try {
                    activeMediaPlayer?.release()
                    val mp = MediaPlayer.create(context.applicationContext, R.raw.login_sound)
                    activeMediaPlayer = mp
                    mp?.apply {
                        setOnCompletionListener {
                            it.release()
                            if (activeMediaPlayer == it) {
                                activeMediaPlayer = null
                            }
                        }
                        start()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        previouslyLoggedIn = effectiveIsLoggedIn
    }

    // WebView reference to handle back press and reloading
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Handle Android system back press
    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            (context as? ComponentActivity)?.finish()
        }
    }

    // Trigger user data loading on login
    LaunchedEffect(userId, forceHackActive, bypassLogin) {
        if (effectiveIsLoggedIn) {
            val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
            viewModel.loadUserData(effectiveId, forceHackActive)
        }
    }

    // Handle toast error messages gracefully
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // Main layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
    ) {
        
        // 1. WebView filling the screen safely between status bar and navigation bar
        AndroidView(
            modifier = Modifier
                .fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewInstance = this
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                    }

                    // Enable Cookies
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    // JavaScript Bridge
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onLogin(uId: String) {
                                (context as? ComponentActivity)?.runOnUiThread {
                                    session.userId = uId
                                    session.isLoggedIn = true
                                    userId = uId
                                    isLoggedIn = true
                                    Toast.makeText(context, "Logged in: $uId", Toast.LENGTH_SHORT).show()
                                    viewModel.loadUserData(uId)
                                }
                            }

                            @JavascriptInterface
                            fun onLogout() {
                                (context as? ComponentActivity)?.runOnUiThread {
                                    session.isLoggedIn = false
                                    session.userId = ""
                                    userId = ""
                                    isLoggedIn = false
                                    showBottomSheet = false
                                    Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        "AndroidBridge"
                    )

                    // WebViewClient with redirect & login detection
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            
                            // Inject login detector JS
                            val detectorJs = """
                                (function() {
                                    var token = localStorage.getItem('token') || 
                                                localStorage.getItem('userToken') ||
                                                localStorage.getItem('authToken') || '';
                                                
                                    var uId = localStorage.getItem('user_id') ||
                                              localStorage.getItem('userId') ||
                                              localStorage.getItem('uid') || '';
                                    
                                    if (token && token.length > 10) {
                                        try {
                                            var payload = JSON.parse(atob(token.split('.')[1]));
                                            var extractedUid = payload.id || payload.user_id || payload.uid || uId;
                                            if (extractedUid) {
                                                AndroidBridge.onLogin(extractedUid.toString());
                                            } else if (uId) {
                                                AndroidBridge.onLogin(uId);
                                            } else {
                                                AndroidBridge.onLogout();
                                            }
                                        } catch(e) {
                                            if (uId) {
                                                AndroidBridge.onLogin(uId);
                                            } else {
                                                AndroidBridge.onLogout();
                                            }
                                        }
                                    } else if (uId) {
                                        AndroidBridge.onLogin(uId);
                                    } else {
                                        AndroidBridge.onLogout();
                                    }
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(detectorJs, null)

                            // Detect URL paths
                            val loggedInUrls = listOf("home", "dashboard", "game", "lottery", "wingo")
                            val logoutUrls = listOf("login", "register", "index", "logout")
                            
                            val isOnLoggedInPage = loggedInUrls.any { url.contains(it, ignoreCase = true) }
                            val isOnLogoutPage = logoutUrls.any { url.contains(it, ignoreCase = true) }

                            if (isOnLoggedInPage && !isLoggedIn) {
                                view.evaluateJavascript(
                                    "localStorage.getItem('user_id') || localStorage.getItem('token')"
                                ) { result ->
                                    val cleaned = result?.trim('"') ?: ""
                                    if (cleaned.isNotEmpty() && cleaned != "null") {
                                        session.userId = cleaned
                                        session.isLoggedIn = true
                                        userId = cleaned
                                        isLoggedIn = true
                                        viewModel.loadUserData(cleaned)
                                    }
                                }
                            } else if (isOnLogoutPage) {
                                session.isLoggedIn = false
                                session.userId = ""
                                userId = ""
                                isLoggedIn = false
                            }
                        }
                    }

                    // Load website URL
                    loadUrl(websiteUrlState)
                }
            },
            update = { webView ->
                // Ensure correct URL loads if website state changes
                if (webView.url != websiteUrlState) {
                    webView.loadUrl(websiteUrlState)
                }
            }
        )

        // 2. Draggable Floating Hacking Widget (Always visible, animated RGB rotating border, expanded mini screen)
        val rainbowColors = remember {
            listOf(
                Color(0xFFFF0000), // Red
                Color(0xFFFF5500), // Orange
                Color(0xFFFFD700), // Yellow
                Color(0xFF00FF00), // Green
                Color(0xFF00FFFF), // Cyan
                Color(0xFF0033FF), // Blue
                Color(0xFF9900FF), // Violet
                Color(0xFFFF0000)  // Red (close loop)
            )
        }

        val infiniteTransition = rememberInfiniteTransition(label = "RGBBorderAnimation")
        val rgbAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "RGBAngle"
        )

        AnimatedVisibility(
            visible = true, // ALWAYS visible from the start so any user sees it immediately!
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd) // Start at middle-right edge
                .offset { IntOffset(floatingXOffset.roundToInt(), floatingYOffset.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        floatingXOffset += dragAmount.x
                        floatingYOffset += dragAmount.y
                    }
                }
                .padding(end = 12.dp)
        ) {
            AnimatedContent(
                targetState = isMiniScreenExpanded,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)) +
                            scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.8f, animationSpec = tween(150)))
                },
                label = "MiniScreenAnimation"
            ) { expanded ->
                if (!expanded) {
                    // COLLAPSED: Small Beautiful Pulsing / Spinning Loading Floating Icon
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .clickable {
                                isMiniScreenExpanded = true
                            }
                    ) {
                        // Rotating RGB border background
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = rgbAngle }
                                .background(Brush.sweepGradient(colors = rainbowColors))
                        )

                        // Inner Dark core masking to leave an RGB border
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color(0xFF1F1F24), Color(0xFF0B0B0E))
                                    )
                                )
                        ) {
                            val infinitePulse = rememberInfiniteTransition(label = "HackerPulse")
                            val pulseGlow by infinitePulse.animateFloat(
                                initialValue = 0.6f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1100, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "PulseGlow"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = ">_",
                                    color = Color(0xFF00FF66).copy(alpha = pulseGlow),
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "HACK",
                                    color = if (effectiveHackActive) Color(0xFFFF6B00) else Color(0xFF00FF66).copy(alpha = pulseGlow),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                } else {
                    // EXPANDED: Premium, larger high-tech Mini Screen with dynamic rotating RGB borders!
                    
                    // Setup internal scanner and pulsing animations
                    val scannerTransition = rememberInfiniteTransition(label = "Scanner")
                    val scanOffset by scannerTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "ScanOffset"
                    )
                    val pulseScaleTransition = rememberInfiniteTransition(label = "PulseScale")
                    val pulseScale by pulseScaleTransition.animateFloat(
                        initialValue = 0.95f,
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "PulseScale"
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .width(195.dp)
                            .wrapContentHeight()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            // 1. Rotating RGB Border background for the card
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { rotationZ = rgbAngle }
                                    .background(Brush.sweepGradient(colors = rainbowColors))
                            )

                            // 2. Inner Deep Black Core Content
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(2.dp) // Leave perfect 2dp RGB border gap
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0D0D11))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Header row with title & compact Close button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(
                                                    if (effectiveHackActive) Color(0xFF00FF66) else Color(0xFFFF3333),
                                                    CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (!effectiveIsLoggedIn) "NOT CONNECTED" else if (effectiveHackActive) "WINGO ACTIVE" else "LOCKED",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (!effectiveIsLoggedIn) Color.Gray else if (effectiveHackActive) Color(0xFF00FF66) else Color(0xFFFF3333),
                                            letterSpacing = 0.5.sp
                                        )
                                    }

                                    // Close Button
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .clickable { isMiniScreenExpanded = false }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Mini Screen",
                                            tint = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Body Content matching state
                                if (!effectiveIsLoggedIn) {
                                    // CASE A: User not logged in/registered
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Lock Icon",
                                        tint = Color(0xFFFF3333),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "LOGIN REQUIRED",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Log in or register below to auto-sync calculations in real-time.",
                                        fontSize = 7.5.sp,
                                        color = Color.LightGray,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    CircularProgressIndicator(
                                        color = Color(0xFFFF3333),
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "WAITING FOR LOGIN...",
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray
                                    )
                                } else if (!effectiveHackActive) {
                                    // CASE B: Logged in but Hack is Locked
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Security Alert",
                                        tint = Color(0xFFFF6B00),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "SECURITY LOCK ACTIVE",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFFFF6B00),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Requires minimum ₹${(if (depInfo != null && depInfo.required > 0) depInfo.required else 5000.0).toInt()} game balance to activate prediction calculations.",
                                        fontSize = 7.5.sp,
                                        color = Color.LightGray,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    val requiredAmount = if (depInfo != null && depInfo.required > 0) depInfo.required else 5000.0
                                    val currentPaid = depInfo?.balance ?: 0.0

                                    LinearProgressIndicator(
                                        progress = {
                                            val fraction = if (requiredAmount > 0) (currentPaid / requiredAmount).toFloat() else 0f
                                            fraction.coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = Color(0xFFFF6B00),
                                        trackColor = Color.White.copy(alpha = 0.1f)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Balance: ₹${currentPaid.toInt()} / ₹${requiredAmount.toInt()}",
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                                viewModel.tryActivateHack(effectiveId)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f),
                                            enabled = !isLoading,
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            if (isLoading) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(10.dp), strokeWidth = 1.2.dp)
                                            } else {
                                                Text("⚡ ACTIVATE", fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                showDepositDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            Text("➕ ADD LOG", fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    // CASE C: Hack Active - Show ONLY next predicted number!
                                    val pred = prediction
                                    if (pred != null) {
                                        val numColor = when (pred.color?.lowercase()) {
                                            "green" -> Color(0xFF00FF66)
                                            "violet" -> Color(0xFFBA68C8)
                                            else -> Color(0xFFFF3333)
                                        }

                                        Text(
                                            text = "NEXT PREDICTED NUMBER",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.Gray,
                                            letterSpacing = 0.8.sp
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .scale(pulseScale)
                                                .background(
                                                    brush = Brush.radialGradient(
                                                        colors = listOf(numColor.copy(alpha = 0.25f), Color.Transparent)
                                                    ),
                                                    shape = CircleShape
                                                )
                                                .border(1.dp, numColor.copy(alpha = 0.35f), CircleShape)
                                        ) {
                                            Text(
                                                text = pred.number.toString(),
                                                color = numColor,
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                textAlign = TextAlign.Center
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "SYNCED • NEXT IN ${timeLeft}s",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (timeLeft <= 10) Color(0xFFFF3333) else Color(0xFF00FF66)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Animated dynamic frequency telemetry bars
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            verticalAlignment = Alignment.Bottom,
                                            modifier = Modifier.height(12.dp)
                                        ) {
                                            repeat(6) { index ->
                                                val barHeightPhase = rememberInfiniteTransition(label = "BarHeight$index")
                                                val barHeight by barHeightPhase.animateFloat(
                                                    initialValue = 3f,
                                                    targetValue = 13f,
                                                    animationSpec = infiniteRepeatable(
                                                        animation = tween(
                                                            durationMillis = 400 + (index * 120),
                                                            easing = FastOutLinearInEasing,
                                                            delayMillis = index * 60
                                                        ),
                                                        repeatMode = RepeatMode.Reverse
                                                    ),
                                                    label = "BarHeight"
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.dp)
                                                        .height(barHeight.dp)
                                                        .background(
                                                            Color(0xFF00FF66),
                                                            RoundedCornerShape(1.dp)
                                                        )
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(vertical = 12.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = Color(0xFFFF6B00),
                                                strokeWidth = 2.5.dp
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "SENSING...",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF6B00)
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. Floating High-Tech Scanline Overlay (Canvas)
                            val scanColor = if (effectiveHackActive) Color(0xFF00FF66) else Color(0xFFFF6B00)
                            Canvas(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(18.dp))
                            ) {
                                val y = size.height * scanOffset
                                drawLine(
                                    color = scanColor.copy(alpha = 0.5f),
                                    start = androidx.compose.ui.geometry.Offset(0f, y),
                                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                                    strokeWidth = 3f
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, scanColor.copy(alpha = 0.05f), Color.Transparent),
                                        startY = y - 40f,
                                        endY = y + 40f
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, (y - 40f).coerceAtLeast(0f)),
                                    size = androidx.compose.ui.geometry.Size(size.width, 80f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Floating Setup Button when Logged-out (Only visible when Developer Mode is active!)
        AnimatedVisibility(
            visible = !effectiveIsLoggedIn && isDeveloperMode,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, end = 20.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    isSettingsMode = true
                    showBottomSheet = true
                },
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(56.dp)
                    .border(2.dp, Color(0xFFFF6B00).copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Open Setup Panel",
                    tint = Color(0xFFFF6B00),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Invisible Developer Easter Egg Trigger at Top-Right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    developerTapCount++
                    if (developerTapCount >= 5) {
                        isDeveloperMode = !isDeveloperMode
                        Toast.makeText(context, "Developer Mode: ${if (isDeveloperMode) "ACTIVE" else "DISABLED"}", Toast.LENGTH_SHORT).show()
                        developerTapCount = 0
                    }
                }
        )

        // 4. Futuristic Settings & Developer Configurations Dialog (Replacing the old slide-up bottom sheet!)
        if (showBottomSheet) {
            Dialog(onDismissRequest = { showBottomSheet = false }) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F13)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.5.dp, Color(0xFFFF6B00).copy(alpha = 0.8f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(18.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚙️ HACK CONFIGS",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFF6B00),
                                letterSpacing = 0.5.sp
                            )
                            IconButton(
                                onClick = { showBottomSheet = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Configs",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                        Spacer(modifier = Modifier.height(12.dp))

                        // Web site URL Input
                        OutlinedTextField(
                            value = websiteUrlState,
                            onValueChange = { websiteUrlState = it },
                            label = { Text("Game Website URL", fontSize = 11.sp, color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6B00),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                focusedLabelColor = Color(0xFFFF6B00)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // BASE API URL Input
                        OutlinedTextField(
                            value = baseUrlState,
                            onValueChange = { baseUrlState = it },
                            label = { Text("Prediction API Base URL", fontSize = 11.sp, color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6B00),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                focusedLabelColor = Color(0xFFFF6B00)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                    viewModel.loadUserData(effectiveId)
                                    Toast.makeText(context, "Syncing data...", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync Info", fontSize = 10.sp)
                            }

                            Button(
                                onClick = {
                                    session.websiteUrl = websiteUrlState
                                    session.baseUrl = baseUrlState
                                    session.isAdminMode = isAdminModeState
                                    Toast.makeText(context, "Configs Saved!", Toast.LENGTH_SHORT).show()
                                    showBottomSheet = false
                                    webViewInstance?.loadUrl(websiteUrlState)
                                    
                                    // Trigger refresh/load user data to update prediction flows
                                    val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                    viewModel.loadUserData(effectiveId, forceHackActive)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.2f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Save & Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Old bottom sheet deactivated
        if (false) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF1E1E1E), // Premium dark theme bottom sheet
                contentColor = Color.White,
                scrimColor = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                developerTapCount++
                                if (developerTapCount >= 5) {
                                    isDeveloperMode = !isDeveloperMode
                                    Toast.makeText(context, "Developer Mode: ${if (isDeveloperMode) "ACTIVE" else "DISABLED"}", Toast.LENGTH_SHORT).show()
                                    developerTapCount = 0
                                }
                            }
                        ) {
                            Text(
                                text = "🎯 Wingo Prediction",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF6B00)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B00).copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "PRO",
                                    color = Color(0xFFFF6B00),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row {
                            if (isDeveloperMode) {
                                IconButton(onClick = { isSettingsMode = !isSettingsMode }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Config Domains & Bypasses",
                                        tint = if (isSettingsMode) Color(0xFFFF6B00) else Color.Gray
                                    )
                                }
                            }
                            IconButton(onClick = { showBottomSheet = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close overlay",
                                    tint = Color.Gray
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    if (isSettingsMode) {
                        // === ADVANCED SETTINGS MODE ===
                        Text(
                            text = "⚙️ Advanced Configurations",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Web site URL Input
                        OutlinedTextField(
                            value = websiteUrlState,
                            onValueChange = { websiteUrlState = it },
                            label = { Text("Website Game URL", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6B00),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                                focusedLabelColor = Color(0xFFFF6B00),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // BASE API URL Input
                        OutlinedTextField(
                            value = baseUrlState,
                            onValueChange = { baseUrlState = it },
                            label = { Text("Prediction Backend API Base URL", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6B00),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                                focusedLabelColor = Color(0xFFFF6B00),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Save Button
                        Button(
                            onClick = {
                                session.websiteUrl = websiteUrlState
                                session.baseUrl = baseUrlState
                                Toast.makeText(context, "Configurations Saved Successfully!", Toast.LENGTH_SHORT).show()
                                isSettingsMode = false
                                // Reload WebView
                                webViewInstance?.loadUrl(websiteUrlState)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save & Apply Settings", fontWeight = FontWeight.Bold)
                        }

                        // Reset Button
                        TextButton(
                            onClick = {
                                websiteUrlState = SessionManager.DEFAULT_WEBSITE_URL
                                baseUrlState = SessionManager.DEFAULT_BASE_URL
                                periodOffsetState = 0
                                timeOffsetState = 0
                                session.websiteUrl = SessionManager.DEFAULT_WEBSITE_URL
                                session.baseUrl = SessionManager.DEFAULT_BASE_URL
                                session.periodOffset = 0
                                session.timeOffset = 0
                                viewModel.updatePeriod()
                                Toast.makeText(context, "Settings Reset to Defaults", Toast.LENGTH_SHORT).show()
                                isSettingsMode = false
                                webViewInstance?.loadUrl(websiteUrlState)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset to System Defaults", color = Color.Red.copy(alpha = 0.8f))
                        }

                        Spacer(modifier = Modifier.height(16.dp))



                    } else {
                        // === STANDARD OVERLAY SCREEN ===

                        // Live Info Row (Period, Timer, Balance)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Period Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PERIOD", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                Text(
                                    text = currentPeriod.ifEmpty { "Calculating..." },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }

                            // Timer Count
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("TIME LEFT", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                val timerMins = timeLeft / 60
                                val timerSecs = timeLeft % 60
                                val timeFormatted = String.format("%02d:%02d", timerMins, timerSecs)
                                
                                val timerColor = if (timeLeft <= 10) Color(0xFFE53935) else Color(0xFF4CAF50)
                                val timerScale by animateFloatAsState(
                                    targetValue = if (timeLeft <= 10) 1.2f else 1.0f,
                                    animationSpec = repeatTransitionSpec(),
                                    label = "TimerPulse"
                                )

                                Text(
                                    text = timeFormatted,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    color = timerColor,
                                    modifier = Modifier.scale(timerScale)
                                )
                            }

                            // Balance Tracker
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text("MY BALANCE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "₹${String.format("%.2f", balance)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (effectiveHackActive) {
                            // === PREMIUM PREDICTION IS UNLOCKED ===
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFFFF6B00).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "NEXT NUMBER PREDICTION",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        letterSpacing = 1.sp
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (prediction == null) {
                                        // Genuine Loading state representation
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(CircleShape)
                                                .background(Brush.radialGradient(listOf(Color(0xFF424242), Color(0xFF212121))))
                                                .border(2.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color(0xFFFF6B00),
                                                strokeWidth = 3.dp,
                                                modifier = Modifier.size(40.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "⏳ WAITING FOR SERVER PREDICTION...",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Gray
                                            )
                                        }
                                    } else {
                                        val predictedNumber = prediction!!.number
                                        val predictedColorStr = prediction!!.color.lowercase()
                                        val predictedSizeStr = prediction!!.size.lowercase()

                                        val bgGradient = when (predictedColorStr) {
                                            "green" -> Brush.radialGradient(listOf(Color(0xFF81C784), Color(0xFF2E7D32)))
                                            "red" -> Brush.radialGradient(listOf(Color(0xFFE57373), Color(0xFFC62828)))
                                            "violet" -> Brush.radialGradient(listOf(Color(0xFFBA68C8), Color(0xFF6A1B9A)))
                                            else -> Brush.radialGradient(listOf(Color(0xFFFFB74D), Color(0xFFE65100)))
                                        }

                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(CircleShape)
                                                .background(bgGradient)
                                                .border(2.dp, Color.White, CircleShape)
                                        ) {
                                            Text(
                                                text = predictedNumber.toString(),
                                                fontSize = 48.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color.White
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        // Display attributes (Color and Size labels)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val colorDisplay = when (predictedColorStr) {
                                                "green" -> "🟢 GREEN"
                                                "red" -> "🔴 RED"
                                                "violet" -> "🟣 VIOLET"
                                                else -> "⚪ MIXED"
                                            }
                                            val textColor = when (predictedColorStr) {
                                                "green" -> Color(0xFF4CAF50)
                                                "red" -> Color(0xFFF44336)
                                                "violet" -> Color(0xFFBA68C8)
                                                else -> Color.White
                                            }

                                            Text(
                                                text = colorDisplay,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textColor
                                            )

                                            Spacer(modifier = Modifier.width(20.dp))

                                            Text(
                                                text = "SIZE: ${predictedSizeStr.uppercase()}",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFFF6B00)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Strategic algorithm badge
                                        val strategyName = prediction!!.strategy ?: "Auto-Sensing Quant Matrix"
                                        Box(
                                             modifier = Modifier
                                                 .clip(RoundedCornerShape(8.dp))
                                                 .background(Color(0xFF1E1E1E))
                                                 .border(1.dp, Color(0xFFFF6B00).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                 .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                             Text(
                                                 text = "🧠 STRATEGY: ${strategyName.uppercase()}",
                                                 fontSize = 11.sp,
                                                 fontWeight = FontWeight.SemiBold,
                                                 color = Color(0xFFFF8C3D),
                                                 letterSpacing = 0.5.sp
                                             )
                                        }
                                    }
                                }
                            }
                        } else {
                            // === HACK LOCKED (DEPOSIT SYSTEM ALERT) ===
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2622)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "🔒 PREDICTION SYSTEM LOCKED",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Wingo 30s predictions are locked to secure high-rate predictions. Access requires a minimum security deposit match.",
                                        fontSize = 12.sp,
                                        color = Color.LightGray,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Required vs Deposited Progress Card
                                    val requiredAmount = if (depInfo != null && depInfo.required > 0) depInfo.required else 5000.0
                                    val currentPaid = depInfo?.balance ?: 0.0
                                    val remainingAmount = (requiredAmount - currentPaid).coerceAtLeast(0.0)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Required Balance:", fontSize = 12.sp, color = Color.Gray)
                                        Text("₹${String.format("%.0f", requiredAmount)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Your Game Balance:", fontSize = 12.sp, color = Color.Gray)
                                        Text("₹${String.format("%.0f", currentPaid)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            val fraction = if (requiredAmount > 0) (currentPaid / requiredAmount).toFloat() else 0f
                                            fraction.coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = Color(0xFFFF6B00),
                                        trackColor = Color.White.copy(alpha = 0.1f)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = "Remaining Balance: ₹${String.format("%.0f", remainingAmount)}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Side by Side action buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                                viewModel.tryActivateHack(effectiveId)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f),
                                            enabled = !isLoading
                                        ) {
                                            if (isLoading) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                            } else {
                                                Text("⚡ ACTIVATE HACK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                showDepositDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("➕ SUBMIT LOG", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    // Deposit History List Section
                                    Text(
                                        text = "📜 YOUR DEPOSIT LOGS",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF8C3D),
                                        modifier = Modifier.align(Alignment.Start)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    val effectiveIdForLogs = if (userId.isEmpty()) "test_user_777" else userId
                                    val logs = viewModel.getDepositLogs(effectiveIdForLogs)

                                    if (logs.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No deposit logs found. Submit a deposit log to sync.",
                                                fontSize = 10.sp,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            logs.take(4).forEach { log ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                     horizontalArrangement = Arrangement.SpaceBetween,
                                                     verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column {
                                                        Text("₹${String.format("%.0f", log.amount)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                        Text("UTR: ${log.utr}", fontSize = 9.sp, color = Color.Gray)
                                                        Text(log.timestamp, fontSize = 8.sp, color = Color.DarkGray)
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "🟢 APPROVED",
                                                            color = Color(0xFF4CAF50),
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Deposit dialog definition popup
                                    if (showDepositDialog) {
                                        androidx.compose.ui.window.Dialog(onDismissRequest = { showDepositDialog = false }) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                                                shape = RoundedCornerShape(18.dp),
                                                border = BorderStroke(1.dp, Color(0xFFFF6B00).copy(alpha = 0.8f)),
                                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(18.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "💸 SUBMIT DEPOSIT LOG",
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 15.sp,
                                                        color = Color(0xFFFF6B00)
                                                    )
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        text = "Enter your deposit details manually to sync your game account balance and unlock the premium predictions instantly.",
                                                        fontSize = 11.sp,
                                                        color = Color.LightGray,
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Spacer(modifier = Modifier.height(16.dp))

                                                    OutlinedTextField(
                                                        value = depositAmountInput,
                                                        onValueChange = { depositAmountInput = it },
                                                        label = { Text("Deposit Amount (₹)", color = Color.Gray, fontSize = 11.sp) },
                                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = Color(0xFFFF6B00),
                                                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                                            focusedLabelColor = Color(0xFFFF6B00)
                                                        ),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    Spacer(modifier = Modifier.height(10.dp))

                                                    OutlinedTextField(
                                                        value = depositUtrInput,
                                                        onValueChange = { depositUtrInput = it },
                                                        label = { Text("UTR / Transaction ID (12 digits)", color = Color.Gray, fontSize = 11.sp) },
                                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                                                        colors = OutlinedTextFieldDefaults.colors(
                                                            focusedBorderColor = Color(0xFFFF6B00),
                                                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f),
                                                            focusedLabelColor = Color(0xFFFF6B00)
                                                        ),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    Spacer(modifier = Modifier.height(20.dp))

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        OutlinedButton(
                                                            onClick = { showDepositDialog = false },
                                                            modifier = Modifier.weight(1f),
                                                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f)),
                                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                                        ) {
                                                            Text("Cancel", fontSize = 11.sp)
                                                        }

                                                        Button(
                                                            onClick = {
                                                                val amt = depositAmountInput.toDoubleOrNull() ?: 0.0
                                                                if (amt <= 0) {
                                                                    Toast.makeText(context, "Please enter a valid amount!", Toast.LENGTH_SHORT).show()
                                                                    return@Button
                                                                }
                                                                if (depositUtrInput.trim().length < 6) {
                                                                    Toast.makeText(context, "Please enter a valid Transaction/UTR ID!", Toast.LENGTH_SHORT).show()
                                                                    return@Button
                                                                }
                                                                val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                                                viewModel.addDepositLog(effectiveId, amt, depositUtrInput.trim())
                                                                Toast.makeText(context, "Deposit Log added! Balance Synced!", Toast.LENGTH_LONG).show()
                                                                showDepositDialog = false
                                                                depositUtrInput = ""
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                                                            modifier = Modifier.weight(1.2f)
                                                        ) {
                                                            Text("Submit Log", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bottom Actions (Refresh, Manual Sync)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                    viewModel.loadUserData(effectiveId)
                                    Toast.makeText(context, "Syncing data...", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync Info")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Manual Sync", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Custom animation helper to pulse countdown numbers nicely
@Composable
fun repeatTransitionSpec(): AnimationSpec<Float> {
    return infiniteRepeatable(
        animation = tween(500, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
    )
}
