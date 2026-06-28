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

    // Session States
    var isLoggedIn by remember { mutableStateOf(session.isLoggedIn) }
    var userId by remember { mutableStateOf(session.userId) }

    // Bottom Sheet State
    var showBottomSheet by remember { mutableStateOf(false) }
    var isSettingsMode by remember { mutableStateOf(false) }

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
            viewModel.loadUserData(effectiveId)
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
        
        // 1. WebView filling the screen
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

        // 2. Floating Action Button (FAB)
        // Animates visibility when the user is logged in
        AnimatedVisibility(
            visible = effectiveIsLoggedIn,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, end = 20.dp) // Pinned safely above system gesture area
        ) {
            FloatingActionButton(
                onClick = {
                    isSettingsMode = false
                    showBottomSheet = true
                },
                containerColor = Color(0xFFFF6B00),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(60.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Pulsing Ring Effect
                    val infiniteTransition = rememberInfiniteTransition(label = "FABPulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "PulseScale"
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 0.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "PulseAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(
                                color = Color(0xFFFF6B00).copy(alpha = pulseAlpha),
                                shape = CircleShape
                            )
                    )

                    // Target Icon
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open Wingo Prediction Tool",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // 3. Logged-out Helper Badge (Provides nice onboarding state for users)
        if (!effectiveIsLoggedIn) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .clickable {
                        isSettingsMode = true
                        showBottomSheet = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Help",
                        tint = Color(0xFFFF6B00),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Login to Website or Tap to Open Helper panel",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

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
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 40.dp) // extra bottom padding for gestures
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            IconButton(onClick = { isSettingsMode = !isSettingsMode }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Config Domains & Bypasses",
                                    tint = if (isSettingsMode) Color(0xFFFF6B00) else Color.Gray
                                )
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
                                session.websiteUrl = SessionManager.DEFAULT_WEBSITE_URL
                                session.baseUrl = SessionManager.DEFAULT_BASE_URL
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

                                    // Render number inside colorful circle
                                    val predictedNumber = prediction?.number ?: 7
                                    val predictedColorStr = prediction?.color?.lowercase() ?: "red"
                                    val predictedSizeStr = prediction?.size?.lowercase() ?: "big"

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
                                            text = if (prediction != null) predictedNumber.toString() else "?",
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
