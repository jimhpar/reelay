package com.reelay.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reelay.app.data.PreferencesManager
import com.reelay.app.receiver.ReelayDeviceAdminReceiver
import com.reelay.app.service.ReelayAccessibilityService
import com.reelay.app.stealth.StealthManager
import com.reelay.app.stealth.StealthNotificationManager
import com.reelay.app.theme.ReelayTheme
import com.reelay.app.ui.AuthActivity

class MainActivity : ComponentActivity() {

    companion object {
        var isSessionAuthenticated = false
        var isNavigatingToSystemSettings = false
    }

    private lateinit var prefs: PreferencesManager
    private val refreshTrigger = mutableIntStateOf(0)
    private val isUnlockedState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        // Session lock state: if password is set, start locked unless already authenticated in this session
        isUnlockedState.value = !prefs.isPasswordSet || isSessionAuthenticated

        StealthNotificationManager.showStealthNotification(this, true)

        enableEdgeToEdge()
        setContent {
            ReelayTheme {
                if (prefs.isPasswordSet && !isUnlockedState.value) {
                    AppLockScreen(
                        onUnlock = { password ->
                            if (prefs.verifyPassword(password)) {
                                isSessionAuthenticated = true
                                isUnlockedState.value = true
                                prefs.updateLastAuthTime()
                                true
                            } else {
                                false
                            }
                        },
                        onExit = {
                            finishAffinity()
                        }
                    )
                } else {
                    MainDashboardScreen(
                        prefs = prefs,
                        refreshTrigger = refreshTrigger.intValue,
                        onNavigateToSettings = {
                            isNavigatingToSystemSettings = true
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++

        if (prefs.isPasswordSet) {
            if (isNavigatingToSystemSettings) {
                // Returned from system permission settings dialog (Accessibility, Admin, Battery)
                isNavigatingToSystemSettings = false
            } else {
                // Entering the app from outside (launcher, recents, notification, tile, url link)
                // Always require password!
                isUnlockedState.value = isSessionAuthenticated
            }
        } else {
            isUnlockedState.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isNavigatingToSystemSettings) {
            // App was backgrounded (home pressed, other app opened, phone locked)
            // Immediately lock the session so returning requires the password!
            isSessionAuthenticated = false
            isUnlockedState.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    prefs: PreferencesManager,
    refreshTrigger: Int,
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize with direct live state
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, ReelayAccessibilityService::class.java))
    }
    var isDeviceAdminActive by remember {
        mutableStateOf(checkDeviceAdminActive(context))
    }
    var isBatteryOptimized by remember {
        mutableStateOf(checkBatteryExemption(context))
    }

    var ytShortsEnabled by remember { mutableStateOf(prefs.blockYouTubeShorts) }
    var instaReelsEnabled by remember { mutableStateOf(prefs.blockInstaReels) }
    var fbReelsEnabled by remember { mutableStateOf(prefs.blockFacebookReels) }
    var tiktokEnabled by remember { mutableStateOf(prefs.blockTikTok) }
    var isStealthEnabled by remember { mutableStateOf(StealthManager.isAppHidden(context)) }
    var uninstallProtection by remember { mutableStateOf(prefs.antiUninstallProtection) }
    var blockedCount by remember { mutableIntStateOf(prefs.blockedCount) }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showStealthInfoDialog by remember { mutableStateOf(false) }
    var showDeactivateAdminDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    fun refreshStatus() {
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context, ReelayAccessibilityService::class.java)
        isDeviceAdminActive = checkDeviceAdminActive(context)
        isBatteryOptimized = checkBatteryExemption(context)
        blockedCount = prefs.blockedCount
        isStealthEnabled = StealthManager.isAppHidden(context)
    }

    // Triggered on every onResume() from Activity lifecycle
    LaunchedEffect(refreshTrigger) {
        refreshStatus()
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF090D16),
            Color(0xFF0F172A),
            Color(0xFF020617)
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF2563EB),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "Logo",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Reelay",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = Color.White
                            )
                            Text(
                                text = "by BlackBox THC",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        refreshStatus()
                        Toast.makeText(context, "Permissions Refreshed", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF38BDF8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF090D16)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBg)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.85f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Distraction Shield",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$blockedCount Blocked",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = "Shield",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // Permissions / Core Setup Card
            Text(
                text = "SYSTEM PERMISSIONS",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )

            // Restricted Setting Helper Card (Crucial for Xiaomi / Android 13+)
            if (!isAccessibilityEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF7C2D12).copy(alpha = 0.45f)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFEA580C).copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Alert",
                                tint = Color(0xFFFB923C),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Restricted Setting Warning?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFFFED7AA)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Xiaomi / Android 13+ restricts downloaded apps by default:\n1. Tap 'Open App Info' below\n2. Tap the 3 dots (⋮) in the top-right corner\n3. Tap 'Allow restricted settings'\n4. Then come back and enable Accessibility!",
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                onNavigateToSettings()
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                Toast.makeText(context, "Tap 3 dots (⋮) at top right -> Allow restricted settings", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Open App Info (Allow Restricted)", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }

            // Accessibility Status
            StatusCard(
                title = "Accessibility Service",
                description = if (isAccessibilityEnabled) "Active and monitoring Reels/Shorts" else "Required to detect and block short videos",
                isActive = isAccessibilityEnabled,
                icon = Icons.Default.Visibility,
                actionLabel = if (isAccessibilityEnabled) "Active" else "Enable",
                onAction = {
                    onNavigateToSettings()
                    val componentName = ComponentName(context, ReelayAccessibilityService::class.java).flattenToString()
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(":settings:fragment_args_key", componentName)
                        val bundle = Bundle().apply {
                            putString(":settings:fragment_args_key", componentName)
                        }
                        putExtra(":settings:show_fragment_args", bundle)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                    Toast.makeText(context, "Tap 'Reelay Protection Service' and toggle ON", Toast.LENGTH_LONG).show()
                }
            )

            // Device Admin (Uninstall Protection)
            StatusCard(
                title = "Device Admin Protection",
                description = if (isDeviceAdminActive) "Active. Tap 'Turn Off' to deactivate admin and allow uninstall." else "Locks system 'Uninstall' button to prevent removal.",
                isActive = isDeviceAdminActive,
                icon = Icons.Default.Security,
                actionLabel = if (isDeviceAdminActive) "Turn Off" else "Activate",
                actionEnabled = true,
                isDestructive = isDeviceAdminActive,
                onAction = {
                    if (isDeviceAdminActive) {
                        showDeactivateAdminDialog = true
                    } else {
                        onNavigateToSettings()
                        val adminComponent = ComponentName(context, ReelayDeviceAdminReceiver::class.java)
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "BlackBox THC: Reelay requires Device Admin to prevent unauthorized uninstallation without password."
                            )
                        }
                        try {
                            deviceAdminLauncher.launch(intent)
                        } catch (_: Exception) {
                            // Fallback directly to Device Admin Settings list (especially for Xiaomi/HyperOS)
                            try {
                                val fallbackIntent = Intent("android.settings.DEVICE_ADMIN_SETTINGS").apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallbackIntent)
                            } catch (_: Exception) {
                                val fallbackSecurity = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallbackSecurity)
                            }
                        }
                        Toast.makeText(context, "Tap 'Activate' or enable Reelay in the list", Toast.LENGTH_LONG).show()
                    }
                }
            )

            // Battery Optimization Exemption
            StatusCard(
                title = "Background Liveness",
                description = if (isBatteryOptimized) "Protected from background killer" else "Exclude from battery restrictions",
                isActive = isBatteryOptimized,
                icon = Icons.Default.BatteryChargingFull,
                actionLabel = if (isBatteryOptimized) "Optimized" else "Fix",
                onAction = {
                    onNavigateToSettings()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            )

            // Platforms to block
            Text(
                text = "BLOCKING RULES",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = "YouTube Shorts",
                        subtitle = "Redirects Shorts tab to Home, normal videos work fine",
                        checked = ytShortsEnabled,
                        onCheckedChange = {
                            ytShortsEnabled = it
                            prefs.blockYouTubeShorts = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    ToggleRow(
                        title = "Instagram Reels",
                        subtitle = "Redirects Reels tab to Feed, posts & DMs work",
                        checked = instaReelsEnabled,
                        onCheckedChange = {
                            instaReelsEnabled = it
                            prefs.blockInstaReels = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    ToggleRow(
                        title = "Facebook Reels",
                        subtitle = "Redirects Reels viewer, normal posts work",
                        checked = fbReelsEnabled,
                        onCheckedChange = {
                            fbReelsEnabled = it
                            prefs.blockFacebookReels = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    ToggleRow(
                        title = "TikTok App",
                        subtitle = "Blocks TikTok app completely",
                        checked = tiktokEnabled,
                        onCheckedChange = {
                            tiktokEnabled = it
                            prefs.blockTikTok = it
                        }
                    )
                }
            }

            // Security & Stealth Section
            Text(
                text = "SECURITY & STEALTH",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = "Hide from Homescreen",
                        subtitle = if (isStealthEnabled) "Hidden. Open via Notification bar or Quick Tile." else "Visible in launcher and app drawer",
                        checked = isStealthEnabled,
                        onCheckedChange = { hide ->
                            if (hide && !prefs.isPasswordSet) {
                                Toast.makeText(context, "Set a Master Password first before hiding!", Toast.LENGTH_LONG).show()
                                showPasswordDialog = true
                            } else {
                                isStealthEnabled = hide
                                StealthManager.setAppHidden(context, hide)
                                StealthNotificationManager.showStealthNotification(context, true)
                                if (hide) {
                                    showStealthInfoDialog = true
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    ToggleRow(
                        title = "Notification Bar Access",
                        subtitle = "Shows 'System Guard' in top bar to open Reelay anytime",
                        checked = true,
                        onCheckedChange = { show ->
                            StealthNotificationManager.showStealthNotification(context, show)
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    ToggleRow(
                        title = "Uninstall Protection Overlay",
                        subtitle = "Intercepts Settings -> Apps and asks for password",
                        checked = uninstallProtection,
                        onCheckedChange = {
                            uninstallProtection = it
                            prefs.antiUninstallProtection = it
                        }
                    )
                    HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Master Password",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (prefs.isPasswordSet) "Configured (Protected)" else "Not set! Tap to set password",
                                fontSize = 13.sp,
                                color = if (prefs.isPasswordSet) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                        }
                        Button(
                            onClick = { showPasswordDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (prefs.isPasswordSet) "Change" else "Set", color = Color.White)
                        }
                    }
                }
            }

            // Danger Zone & App Removal
            Text(
                text = "DANGER ZONE & UNINSTALL",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEF4444),
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF450A0A).copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFDC2626).copy(alpha = 0.2f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = "Uninstall",
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Uninstall Reelay",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Deactivate admin and remove app",
                                fontSize = 12.sp,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Tap below to immediately remove Device Admin privilege, disable uninstall protection, and launch the system uninstaller.",
                        fontSize = 12.sp,
                        color = Color(0xFFE2E8F0),
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            showUninstallDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Uninstall App", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Password Setup / Change Dialog
    if (showPasswordDialog) {
        PasswordSetupDialog(
            isAlreadySet = prefs.isPasswordSet,
            onDismiss = { showPasswordDialog = false },
            onSave = { newPass ->
                prefs.setMasterPassword(newPass)
                prefs.updateLastAuthTime()
                Toast.makeText(context, "Password Saved Successfully!", Toast.LENGTH_SHORT).show()
                showPasswordDialog = false
            }
        )
    }

    // Stealth Info Dialog
    if (showStealthInfoDialog) {
        AlertDialog(
            onDismissRequest = { showStealthInfoDialog = false },
            title = {
                Text(text = "App Icon Hidden", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    text = "Reelay icon is now hidden from the home screen.\n\n" +
                            "To open Reelay anytime:\n" +
                            "1. Pull down your top notification bar and tap 'System Guard'.\n" +
                            "2. Or pull down Quick Settings and tap the 'Reelay Guard' tile.\n" +
                            "3. Enter your Master Password to unlock.",
                    color = Color(0xFFCBD5E1),
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showStealthInfoDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Got it")
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // Deactivate Admin Dialog
    if (showDeactivateAdminDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateAdminDialog = false },
            title = {
                Text(text = "Turn Off Device Admin?", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    text = "Deactivating Device Administrator will unlock the system 'Uninstall' button. You will be able to easily uninstall or modify the app.",
                    color = Color(0xFFCBD5E1),
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeactivateAdminDialog = false
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val adminComponent = ComponentName(context, ReelayDeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(adminComponent)) {
                            dpm.removeActiveAdmin(adminComponent)
                        }
                        prefs.updateLastAuthTime()
                        refreshStatus()
                        Toast.makeText(context, "Device Admin Deactivated! App can now be uninstalled.", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Turn Off Admin", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateAdminDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // Uninstall Confirmation Dialog
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = {
                Text(text = "Uninstall Reelay?", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    text = "Are you sure you want to uninstall Reelay? Device Administrator privileges will be removed immediately and Android's uninstaller prompt will open.",
                    color = Color(0xFFCBD5E1),
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstallDialog = false
                        // 1. Deactivate Device Admin
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val adminComponent = ComponentName(context, ReelayDeviceAdminReceiver::class.java)
                        if (dpm.isAdminActive(adminComponent)) {
                            dpm.removeActiveAdmin(adminComponent)
                        }
                        // 2. Disable anti-uninstall protection and update auth time
                        prefs.antiUninstallProtection = false
                        prefs.updateLastAuthTime()
                        // 3. Make sure app is unhidden
                        StealthManager.setAppHidden(context, false)
                        // 4. Launch uninstall intent
                        onNavigateToSettings()
                        try {
                            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(uninstallIntent)
                        } catch (e: Exception) {
                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(fallback)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Uninstall Now", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}

@Composable
fun StatusCard(
    title: String,
    description: String,
    isActive: Boolean,
    icon: ImageVector,
    actionLabel: String,
    actionEnabled: Boolean = !isActive,
    isDestructive: Boolean = false,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B).copy(alpha = 0.85f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isActive) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (isActive) Color(0xFF10B981) else Color(0xFFEF4444),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAction,
                enabled = actionEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isDestructive -> Color(0xFFDC2626)
                        isActive -> Color(0xFF065F46)
                        else -> Color(0xFF2563EB)
                    },
                    disabledContainerColor = Color(0xFF065F46).copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2563EB),
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFF334155)
            )
        )
    }
}

@Composable
fun PasswordSetupDialog(
    isAlreadySet: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isAlreadySet) "Change Master Password" else "Set Master Password",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New Password (min 4 chars)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )
                if (error != null) {
                    Text(text = error ?: "", color = Color(0xFFEF4444), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.length < 4) {
                        error = "Password must be at least 4 characters"
                    } else if (password != confirmPassword) {
                        error = "Passwords do not match"
                    } else {
                        onSave(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
    // 1. Direct memory check
    if (ReelayAccessibilityService.isRunning) return true

    // 2. System AccessibilityManager check
    try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val resolveInfo = service.resolveInfo
            if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                if (resolveInfo.serviceInfo.packageName == context.packageName) {
                    return true
                }
            }
        }
    } catch (_: Exception) {}

    // 3. String contains packageName
    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (enabledServices.contains(context.packageName, ignoreCase = true)) {
            return true
        }
    } catch (_: Exception) {}

    return false
}

fun checkDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, ReelayDeviceAdminReceiver::class.java)
    return dpm.isAdminActive(adminComponent)
}

fun checkBatteryExemption(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true
}

@Composable
fun AppLockScreen(
    onUnlock: (String) -> Boolean,
    onExit: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Intercept back button to exit app completely
    BackHandler {
        onExit()
    }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF090D16),
            Color(0xFF0F172A),
            Color(0xFF020617)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E293B).copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2563EB).copy(alpha = 0.2f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Reelay Guard",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "by BlackBox THC",
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Enter Master Password to open Reelay",
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Master Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val success = onUnlock(password)
                            if (!success) {
                                errorMessage = "Incorrect password! Try again."
                            }
                        }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle password visibility",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569),
                        focusedLabelColor = Color(0xFF38BDF8),
                        unfocusedLabelColor = Color(0xFF94A3B8),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val success = onUnlock(password)
                        if (!success) {
                            errorMessage = "Incorrect password! Try again."
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB)
                    )
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text("Exit", color = Color(0xFF94A3B8), fontSize = 14.sp)
                }
            }
        }
    }
}
