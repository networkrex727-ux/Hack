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
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        enableEdgeToEdge()
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

    // Session States
    var isLoggedIn by remember { mutableStateOf(session.isLoggedIn) }
    var userId by remember { mutableStateOf(session.userId) }

    // Bottom Sheet State
    var showBottomSheet by remember { mutableStateOf(false) }
    var isSettingsMode by remember { mutableStateOf(false) }

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
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Effective states including developer overrides
    val effectiveIsLoggedIn = isLoggedIn || bypassLogin
    val effectiveHackActive = hackActiveState || forceHackActive

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
    Box(modifier = Modifier.fillMaxSize()) {
        
        // 1. WebView filling the screen safely between status bar and navigation bar
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewInstance = this
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = false
                        loadWithOverviewMode = false
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
                                            }
                                        } catch(e) {
                                            if (uId) AndroidBridge.onLogin(uId);
                                        }
                                    } else if (uId) {
                                        AndroidBridge.onLogin(uId);
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

        // 2. Draggable Floating Hacking Widget (Icon or expanded Mini Screen)
        AnimatedVisibility(
            visible = effectiveIsLoggedIn,
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
            if (!isMiniScreenExpanded) {
                // COLLAPSED: Small Pulsing/Loading Floating Icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF2C2C35), Color(0xFF141419))
                            )
                        )
                        .border(
                            2.dp,
                            if (effectiveHackActive) Color(0xFFFF6B00) else Color(0xFFFF5252).copy(alpha = 0.8f),
                            CircleShape
                        )
                        .clickable {
                            isMiniScreenExpanded = true
                        }
                ) {
                    // Pulsing / Spinning Loading Effect
                    val infiniteTransition = rememberInfiniteTransition(label = "IconLoader")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.9f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "Pulse"
                    )

                    val rotationAngle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "Rotation"
                    )

                    // Spinning outer hacker tech ring
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .scale(pulseScale)
                            .border(
                                width = 1.5.dp,
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        if (effectiveHackActive) Color(0xFFFF6B00) else Color(0xFFFF5252),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // Center active indicator (pulsing chip/lock/arrow)
                    if (effectiveHackActive) {
                        // Glowing target/chip
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFFFF6B00).copy(alpha = 0.15f), CircleShape)
                        ) {
                            Text(
                                text = "H",
                                color = Color(0xFFFF6B00),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Hacking Tool Locked",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                // EXPANDED: Mini Screen showing ONLY the next predicted number or Locked status
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFA141419)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        2.dp,
                        if (effectiveHackActive) Color(0xFFFF6B00) else Color(0xFFFF5252).copy(alpha = 0.8f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    modifier = Modifier.width(140.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header row with Close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (effectiveHackActive) "WINGO HACK" else "LOCKED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (effectiveHackActive) Color(0xFFFF6B00) else Color(0xFFFF5252)
                            )

                            // Compact Close Button to minimize to floating icon
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { isMiniScreenExpanded = false }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Mini Screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Body Content: ONLY the next predicted number, NO big/small, NO color!
                        if (effectiveHackActive) {
                            val pred = prediction
                            if (pred != null) {
                                // Giant center predicted number
                                val numColor = when (pred.color?.lowercase()) {
                                    "green" -> Color(0xFF4CAF50)
                                    "violet" -> Color(0xFF9C27B0)
                                    else -> Color(0xFFE53935)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        // Tapping number can open the full detail bottom sheet if they want settings/info!
                                        isSettingsMode = false
                                        showBottomSheet = true
                                    }
                                ) {
                                    Text(
                                        text = "NEXT NO.",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        letterSpacing = 1.sp
                                    )
                                    
                                    Spacer(modifier = Modifier.height(2.dp))

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(numColor.copy(alpha = 0.25f), Color.Transparent)
                                                ),
                                                shape = CircleShape
                                            )
                                    ) {
                                        Text(
                                            text = pred.number.toString(),
                                            color = numColor,
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Small clean timer at the bottom of mini screen to stay helpful
                                    Text(
                                        text = "UPDATES IN ${timeLeft}s",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (timeLeft <= 10) Color(0xFFFF5252) else Color(0xFF4CAF50)
                                    )
                                }
                            } else {
                                // SENSING Loader
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
                        } else {
                            // Locked Status with Lock Icon and Deposit indicator
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        isSettingsMode = false
                                        showBottomSheet = true
                                    }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Locked",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "LOCKED",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFF5252)
                                )
                                Text(
                                    text = "TAP TO UNLOCK",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
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

        // 4. Modal Bottom Sheet containing Wingo prediction and controls
        if (showBottomSheet) {
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

                        // DEVELOPER OVERRIDES/BYPASS PANEL
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "🛠️ Developer Testing Tools",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFB74D),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Bypass Login Switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Force Login Status", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text("Simulates a valid logged-in user session", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    Switch(
                                        checked = bypassLogin,
                                        onCheckedChange = { bypassLogin = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6B00))
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Force Hack Active Switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Bypass Security Deposit Lock", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text("Directly unlock and preview Wingo predictions", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    Switch(
                                        checked = forceHackActive,
                                        onCheckedChange = { forceHackActive = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6B00))
                                    )
                                }
                            }
                        }

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
                                    val requiredAmount = depositInfo?.required ?: 500.0
                                    val currentPaid = depositInfo?.totalDeposit ?: 0.0
                                    val remainingAmount = depositInfo?.remaining ?: 500.0

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Required Deposit:", fontSize = 12.sp, color = Color.Gray)
                                        Text("₹${String.format("%.0f", requiredAmount)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Your Paid Balance:", fontSize = 12.sp, color = Color.Gray)
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
                                        text = "Remaining Deposit: ₹${String.format("%.0f", remainingAmount)}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Activate Button
                                    Button(
                                        onClick = {
                                            val effectiveId = if (userId.isEmpty()) "test_user_777" else userId
                                            viewModel.tryActivateHack(effectiveId)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isLoading
                                    ) {
                                        if (isLoading) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                        } else {
                                            Text("⚡ ACTIVATE HACK", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
