package com.noxsip.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noxsip.app.data.models.SipAccountData
import com.noxsip.app.sip.SipRegistrationState
import com.noxsip.app.telecom.CallChoiceActivity
import com.noxsip.app.telecom.NativeDialerOverlay
import com.noxsip.app.telecom.TelecomHelper
import com.noxsip.app.ui.theme.Amber500
import com.noxsip.app.ui.theme.Green500
import com.noxsip.app.ui.theme.Indigo600
import com.noxsip.app.ui.theme.Red500
import com.noxsip.app.ui.theme.Sky500
import com.noxsip.app.ui.theme.Violet500

@Composable
fun AccountSetupScreen(
    currentAccount: SipAccountData?,
    accounts: List<SipAccountData>,
    onSelectAccount: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    registrationState: SipRegistrationState,
    registrationError: String?,
    accountStates: Map<String, SipRegistrationState> = emptyMap(),
    onSaveAndRegister: (SipAccountData) -> Unit,
    onUnregister: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCallRedirectionHeld by remember {
        mutableStateOf(TelecomHelper.isCallRedirectionRoleHeld(context))
    }
    val isDefaultDialer by TelecomHelper.defaultDialerHeld.collectAsState()
    LaunchedEffect(Unit) { TelecomHelper.refreshDefaultDialer(context) }
    // "Don't show again" চাপা থাকলে সিস্টেম রোল-ডায়ালগ না দেখিয়ে সাথে সাথে ফিরে আসে; তখন ফোনের "Default apps" পেজ খুলি
    var dialerLaunchAt by remember { mutableLongStateOf(0L) }
    val dialerRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val held = TelecomHelper.refreshDefaultDialer(context)
        val quick = System.currentTimeMillis() - dialerLaunchAt < 800L
        if (!held && quick) {
            Toast.makeText(
                context,
                "Choose \"Phone app\" and select this app",
                Toast.LENGTH_LONG
            ).show()
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }
    var hasOverlay by remember {
        mutableStateOf(TelecomHelper.hasOverlayPermission(context))
    }
    var routingMode by remember {
        mutableIntStateOf(TelecomHelper.getNativeCallRoutingMode(context))
    }
    var autoRecordCalls by remember {
        mutableStateOf(TelecomHelper.isAutoRecordEnabled(context))
    }
    var batteryOk by remember {
        mutableStateOf(com.noxsip.app.util.BatteryOptimization.isIgnoring(context))
    }
    // সেটিংস থেকে ফিরলে চেকলিস্ট আবার যাচাইয়ের জন্য কাউন্টার
    var checklistTick by remember { mutableIntStateOf(0) }
    var micBoost by remember {
        mutableIntStateOf(com.noxsip.app.util.AudioQualityPrefs.getMicBoostDb(context))
    }
    var hdCodecs by remember {
        mutableStateOf(com.noxsip.app.util.AudioQualityPrefs.isHdCodecsEnabled(context))
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlay = TelecomHelper.hasOverlayPermission(context)
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isCallRedirectionHeld = TelecomHelper.isCallRedirectionRoleHeld(context)
        if (isCallRedirectionHeld && !TelecomHelper.hasOverlayPermission(context)) {
            overlayLauncher.launch(TelecomHelper.createOverlayPermissionIntent(context))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                TelecomHelper.refreshDefaultDialer(context)
                isCallRedirectionHeld = TelecomHelper.isCallRedirectionRoleHeld(context)
                hasOverlay = TelecomHelper.hasOverlayPermission(context)
                batteryOk = com.noxsip.app.util.BatteryOptimization.isIgnoring(context)
                checklistTick++
                routingMode = TelecomHelper.getNativeCallRoutingMode(context)
                autoRecordCalls = TelecomHelper.isAutoRecordEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // যে অ্যাকাউন্টটি ফর্মে এডিট হচ্ছে (null = নতুন অ্যাকাউন্ট যোগ)
    var formTarget by remember(currentAccount?.id) { mutableStateOf(currentAccount) }
    var showForm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SipAccountData?>(null) }
    var accountName by remember(formTarget) { mutableStateOf(formTarget?.name ?: "") }
    var username by remember(formTarget) { mutableStateOf(formTarget?.username ?: "") }
    var password by remember(formTarget) { mutableStateOf(formTarget?.password ?: "") }
    var domain by remember(formTarget) { mutableStateOf(formTarget?.domain ?: "") }
    var port by remember(formTarget) { mutableStateOf(formTarget?.port?.toString() ?: "5060") }
    var transport by remember(formTarget) { mutableStateOf(formTarget?.transport ?: "UDP") }
    var proxy by remember(formTarget) { mutableStateOf(formTarget?.proxy ?: "") }
    var stunServer by remember(formTarget) { mutableStateOf(formTarget?.stunServer ?: "") }
    var turnUsername by remember(formTarget) { mutableStateOf(formTarget?.turnUsername ?: "") }
    var turnPassword by remember(formTarget) { mutableStateOf(formTarget?.turnPassword ?: "") }
    var displayName by remember(formTarget) { mutableStateOf(formTarget?.displayName ?: "") }
    var autoStart by remember(formTarget) { mutableStateOf(formTarget?.autoStart ?: false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    // -1 = settings home list, 0 = SIP Account, 1 = Audio & Codec, 2 = Dialer & Permission, 3 = User Account, 4 = Power Mode
    var settingsTab by rememberSaveable { mutableIntStateOf(-1) }

    val transportOptions = listOf("UDP", "TCP", "TLS")
    val isRegistered = registrationState == SipRegistrationState.REGISTERED
    val isRegistering = registrationState == SipRegistrationState.REGISTERING
    // ফর্ম লক শুধু তখনই যখন এডিট হওয়া অ্যাকাউন্টটিই বর্তমানে সংযুক্ত/সংযোগ হচ্ছে
    val formLocked = false
    // এডিট করা অ্যাকাউন্টটিই কি এখন সক্রিয় লাইন?
    val isThisActive = formTarget != null && formTarget?.id == currentAccount?.id
    // ফর্মে খোলা অ্যাকাউন্টের নিজস্ব সংযোগ-অবস্থা
    val formState = formTarget?.let { accountStates[it.id] } ?: SipRegistrationState.UNREGISTERED
    val trimmedName = accountName.trim()
    val nameTaken = accounts.any { it.id != formTarget?.id && it.name.equals(trimmedName, ignoreCase = true) }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Settings home: list of sections (tap a row to open it); back arrow / back button returns here
        androidx.activity.compose.BackHandler(enabled = settingsTab != -1) { settingsTab = -1 }
        if (settingsTab == -1) {
            val backupSubtitle = "Back up accounts, calls & contacts to a file"
            SettingsRow(Icons.Filled.Dns, "SIP Account", "SIP account related settings", Color(0xFF2563EB)) { settingsTab = 0 }
            SettingsRow(Icons.Filled.GraphicEq, "Audio & Codec", "Audio & codec related settings", Color(0xFF7C3AED)) { settingsTab = 1 }
            // কল সেটিং: অটো রেকর্ড অন/অফ সুইচ (সরাসরি প্রধান তালিকায়)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        autoRecordCalls = !autoRecordCalls
                        TelecomHelper.setAutoRecordEnabled(context, autoRecordCalls)
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFFDC2626).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Call Recording", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (autoRecordCalls) "On: every call is recorded" else "Off: calls are not recorded",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoRecordCalls,
                    onCheckedChange = {
                        autoRecordCalls = it
                        TelecomHelper.setAutoRecordEnabled(context, it)
                    }
                )
            }
            SettingsRow(Icons.Filled.PhoneInTalk, "Dialer & Permission", "Phone dialer integration", Color(0xFF16A34A)) { settingsTab = 2 }
            SettingsRow(Icons.Filled.BatteryChargingFull, "Power Mode", "Battery mode", Color(0xFFEA8A00)) { settingsTab = 4 }
            SettingsRow(Icons.Filled.Block, "Blocked numbers", "Calls from these numbers are rejected", Color(0xFFDC2626)) { settingsTab = 5 }
            SettingsRow(Icons.Filled.ManageAccounts, "User Account", backupSubtitle, Color(0xFFDB2777)) { settingsTab = 3 }
            SettingsRow(Icons.Filled.SystemUpdate, "App Update", "Check for a new version", Color(0xFF0EA5E9)) { settingsTab = 6 }
            SettingsRow(
                Icons.Filled.DarkMode, "Appearance",
                when (com.noxsip.app.ui.theme.ThemeMode.mode) {
                    com.noxsip.app.ui.theme.ThemeMode.DARK -> "Dark theme"
                    com.noxsip.app.ui.theme.ThemeMode.SYSTEM -> "Follows phone setting"
                    else -> "Light theme"
                },
                Color(0xFF475569)
            ) { settingsTab = 7 }
            // নিচের ঐচ্ছিক ফ্লোটিং উইজেট (SupportSlot) যেন শেষ সারির উপর না পড়ে
            Spacer(modifier = Modifier.height(72.dp))
        } else {
            val pageTitle = when (settingsTab) {
                0 -> "SIP Account"
                1 -> "Audio & Codec"
                2 -> "Dialer & Permission"
                4 -> "Power Mode"
                5 -> "Blocked numbers"
                6 -> "App Update"
                7 -> "Appearance"
                else -> "User Account"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { settingsTab = -1 }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                val (pageIcon, pageColor) = when (settingsTab) {
                    0 -> Icons.Filled.Dns to Color(0xFF2563EB)
                    1 -> Icons.Filled.GraphicEq to Color(0xFF7C3AED)
                    2 -> Icons.Filled.PhoneInTalk to Color(0xFF16A34A)
                    4 -> Icons.Filled.BatteryChargingFull to Color(0xFFEA8A00)
                    5 -> Icons.Filled.Block to Color(0xFFDC2626)
                    6 -> Icons.Filled.SystemUpdate to Color(0xFF0EA5E9)
                    7 -> Icons.Filled.DarkMode to Color(0xFF475569)
                    else -> Icons.Filled.ManageAccounts to Color(0xFFDB2777)
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(pageColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(pageIcon, contentDescription = null, tint = pageColor, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = pageTitle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Error Banner (only visible when there is an error)
        if (registrationError != null && (settingsTab == 0 || settingsTab == -1)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Red500.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Red500.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = Red500,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = registrationError,
                        fontSize = 12.sp,
                        color = Red500,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // CARD 1 (Audio tab + Dialer tab): Native Dialer Integration (SIM 1 / SIM 2 / SIP) and audio settings
        if (settingsTab == 1 || settingsTab == 2) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, Sky500.copy(alpha = 0.28f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (settingsTab == 1) {
                    Text(
                        text = "Audio & Codecs",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (settingsTab == 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(Sky500, Indigo600))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.SimCard,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Phone Dialer Integration",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "SIM 1 / SIM 2 / SIP Chooser Popup",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 2 Side-by-Side Colorful Permission Tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactPermissionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.PhoneForwarded,
                        title = "Call Redirect",
                        statusText = if (isCallRedirectionHeld) "Enabled ✓" else "Tap to Enable",
                        isEnabled = isCallRedirectionHeld,
                        accentColor = Indigo600,
                        onClick = {
                            val roleIntent = TelecomHelper.createCallRedirectionRoleIntent(context)
                            if (roleIntent != null) {
                                roleLauncher.launch(roleIntent)
                            }
                        }
                    )

                    CompactPermissionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Layers,
                        title = "Popup Overlay",
                        statusText = if (hasOverlay) "Enabled ✓" else "Tap to Enable",
                        isEnabled = hasOverlay,
                        accentColor = Amber500,
                        onClick = {
                            overlayLauncher.launch(TelecomHelper.createOverlayPermissionIntent(context))
                        }
                    )
                }

                // Beta: ফোনের ডিফল্ট ডায়ালার হওয়া (SIM কল এই অ্যাপের কল স্ক্রিনে আসবে)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactPermissionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Call,
                        title = "Default Dialer (Beta)",
                        statusText = if (isDefaultDialer) "Enabled ✓" else "Tap to make default phone app",
                        isEnabled = isDefaultDialer,
                        accentColor = Amber500, // ডিফল্ট না থাকলে সবুজ নয় (কমলা); ডিফল্ট হলে টাইল নিজে সবুজ ✓ হয়
                        onClick = {
                            val dialerIntent = TelecomHelper.createDefaultDialerRoleIntent(context)
                            if (dialerIntent != null) {
                                dialerLaunchAt = System.currentTimeMillis()
                                dialerRoleLauncher.launch(dialerIntent)
                            }
                        }
                    )

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Violet500.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Violet500.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .wrapContentWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    if (NativeDialerOverlay.canShowOverlay(context)) {
                                        NativeDialerOverlay.showAccountChooser(
                                            context = context,
                                            number = "",
                                            onSimSelected = {},
                                            onSipSelected = {},
                                            onCancel = {}
                                        )
                                    } else {
                                        val intent = Intent(context, CallChoiceActivity::class.java).apply {
                                            putExtra("phone_number", "")
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Violet500,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Test Popup",
                                    color = Violet500,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                }

                // Beta: কল ফরোয়ার্ডিং (SIM + SIP)
                var showForwarding by remember { mutableStateOf(false) }
                CompactPermissionTile(
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Call,
                    title = "Call Forwarding (Beta)",
                    statusText = if (com.noxsip.app.beta.CallForward.mode(context) != com.noxsip.app.beta.CallForward.OFF) "SIP forwarding on (Experimental)" else "SIM and SIP (Experimental)",
                    isEnabled = com.noxsip.app.beta.CallForward.mode(context) != com.noxsip.app.beta.CallForward.OFF,
                    accentColor = Indigo600,
                    onClick = { showForwarding = true }
                )
                if (showForwarding) {
                    com.noxsip.app.beta.CallForwardingDialog(onDismiss = { showForwarding = false })
                }

                // Compact 3-Way Segmented Routing Mode Bar
                val modes = listOf(
                    TelecomHelper.MODE_ASK_EVERY_TIME to "Ask (SIM/SIP)",
                    TelecomHelper.MODE_ALWAYS_SIP to "Always SIP",
                    TelecomHelper.MODE_SIM_ONLY to "SIM Only"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modes.forEach { (modeValue, label) ->
                        val selected = routingMode == modeValue
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) Indigo600 else Color.Transparent
                                )
                                .clickable {
                                    routingMode = modeValue
                                    TelecomHelper.setNativeCallRoutingMode(context, modeValue)
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                } // end of Dialer tab: header, permission tiles, routing mode

                if (settingsTab == 1) {
                // Silent Auto Call Recording Toggle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = if (autoRecordCalls) Green500 else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Auto Call Recording",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Saved to phone storage: Recordings/SipDialer",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = autoRecordCalls,
                        onCheckedChange = { enabled ->
                            autoRecordCalls = enabled
                            TelecomHelper.setAutoRecordEnabled(context, enabled)
                        },
                        modifier = Modifier.scale(0.75f)
                    )
                }

                // HD Audio Codecs Toggle Row (Opus / G.722)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = if (hdCodecs) Green500 else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "HD Audio (Opus / G.722)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Server must support it, otherwise G.711 is used automatically",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = hdCodecs,
                        onCheckedChange = { enabled ->
                            hdCodecs = enabled
                            com.noxsip.app.util.AudioQualityPrefs.setHdCodecsEnabled(context, enabled)
                            // HD সুইচ বদলালে নিজের সাজানো কোডেক তালিকা বাতিল, ডিফল্ট লজিকে ফেরে
                            com.noxsip.app.util.AudioQualityPrefs.setCodecConfig(context, null)
                            // নতুন কোডেক তালিকা পরের কল থেকেই কার্যকর হবে
                            com.noxsip.app.SipDialerApplication.configureVoiceAudio(
                                com.noxsip.app.SipDialerApplication.core
                            )
                        },
                        modifier = Modifier.scale(0.75f)
                    )
                }

                CodecManagerCard()
                SecuritySettingsCard()

                } // end of Audio tab: auto-record + HD codec rows

                if (settingsTab == 2) {
                // Setup Checklist: লক-স্ক্রিন/স্ক্রিন-অফে ইনকামিং কল পেতে যা যা লাগে (ট্যাপ করে সরাসরি সেটিংসে যান)
                val setupItems = remember(checklistTick, batteryOk) {
                    com.noxsip.app.util.SetupChecklist.evaluate(context)
                }
                val problems = setupItems.count { it.ok == false }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (problems > 0) Red500.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (problems > 0) "Setup checklist: $problems item(s) need attention"
                        else "Setup checklist: all good",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (problems > 0) Red500 else Green500
                    )
                    setupItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = item.ok != true) { item.fix() }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = when (item.ok) {
                                    true -> "✓"
                                    false -> "✗"
                                    null -> "?"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (item.ok) {
                                    true -> Green500
                                    false -> Red500
                                    null -> Amber500
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.hint,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.ok != true) {
                                Text(
                                    text = if (item.ok == false) "Fix" else "Open",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Indigo600
                                )
                            }
                        }
                    }
                }

                } // end of Dialer tab: setup checklist

                if (settingsTab == 1) {
                // Mic Boost slider: অপরপ্রান্ত আপনার কথা আস্তে শুনলে বাড়ান, বিকৃত শুনলে কমান
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mic Boost",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "+$micBoost dB",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Green500
                        )
                    }
                    Slider(
                        value = micBoost.toFloat(),
                        onValueChange = { v ->
                            micBoost = v.toInt()
                            com.noxsip.app.util.AudioQualityPrefs.setMicBoostDb(context, micBoost)
                            // চলমান কলেও সাথে সাথে কার্যকর
                            try {
                                com.noxsip.app.SipDialerApplication.core.micGainDb = micBoost.toFloat()
                            } catch (_: Exception) {
                            }
                        },
                        valueRange = 0f..com.noxsip.app.util.AudioQualityPrefs.MAX_MIC_BOOST_DB.toFloat(),
                        steps = com.noxsip.app.util.AudioQualityPrefs.MAX_MIC_BOOST_DB - 1
                    )
                    Text(
                        text = "Increase if the other side hears you too quietly",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                } // end of Audio tab: mic boost
            }
        }

        // Power Mode page
        if (settingsTab == 4) {
            PowerModeCard()
        }

        // Blocked numbers page
        if (settingsTab == 5) {
            BlockedNumbersCard()
        }

        // CARD 1b (Account tab): সেভ করা SIP অ্যাকাউন্টের তালিকা
        if (settingsTab == 0) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, Indigo600.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "My SIP Accounts",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { formTarget = null; showForm = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 13.sp, maxLines = 1)
                    }
                }

                if (accounts.isEmpty()) {
                    Text(
                        text = "No account saved yet. Fill in the form below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                accounts.forEach { acc ->
                    val isSelected = acc.id == currentAccount?.id
                    val accState = accountStates[acc.id] ?: SipRegistrationState.UNREGISTERED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) Indigo600.copy(alpha = 0.10f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                if (!isSelected || (accState != SipRegistrationState.REGISTERED && accState != SipRegistrationState.REGISTERING)) {
                                    onSelectAccount(acc.id)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Select",
                            tint = if (isSelected) Green500 else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = acc.name.ifBlank { acc.username },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${acc.username}@${acc.domain}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (!acc.enabled) "Turned off" else when (accState) {
                                    SipRegistrationState.REGISTERED ->
                                        if (isSelected) "Connected • used for outgoing calls" else "Connected • receiving calls"
                                    SipRegistrationState.REGISTERING -> "Connecting..."
                                    SipRegistrationState.ERROR -> "Connection failed - tap to retry"
                                    else -> "Offline - tap to connect"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when (accState) {
                                    SipRegistrationState.REGISTERED -> Green500
                                    SipRegistrationState.ERROR -> Red500
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Switch(
                            checked = acc.enabled,
                            onCheckedChange = { onToggleEnabled(acc.id, it) },
                            modifier = Modifier.scale(0.8f)
                        )
                        IconButton(onClick = { formTarget = acc; showForm = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp), tint = Indigo600)
                        }
                        IconButton(onClick = { confirmDelete = acc }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = Red500)
                        }
                    }
                }
            }
        }

        confirmDelete?.let { acc ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete account?") },
                text = { Text("\"${acc.name.ifBlank { acc.username }}\" will be removed from this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        if (formTarget?.id == acc.id) formTarget = null
                        onDeleteAccount(acc.id)
                    }) { Text("Delete", color = Red500) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
                }
            )
        }

        // CARD 2 (Account tab): SIP Account Credentials (with slim 38dp input boxes!)
        if (settingsTab == 3) {
            BackupRestoreCard()
        }
        if (settingsTab == 7) {
            val themeMode = com.noxsip.app.ui.theme.ThemeMode.mode
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    listOf(
                        com.noxsip.app.ui.theme.ThemeMode.LIGHT to "Light",
                        com.noxsip.app.ui.theme.ThemeMode.DARK to "Dark",
                        com.noxsip.app.ui.theme.ThemeMode.SYSTEM to "System default"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { com.noxsip.app.ui.theme.ThemeMode.set(context, value) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == value, onClick = { com.noxsip.app.ui.theme.ThemeMode.set(context, value) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        if (settingsTab == 6) {
            if (com.noxsip.app.BuildConfig.VERSION_URL.isNotBlank()) UpdateCard()
            else Text(
                "This edition is updated manually: install the new APK over the old one.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }

        if (settingsTab == 0 && showForm) androidx.compose.ui.window.Dialog(
            onDismissRequest = { showForm = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showForm = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                        Text(
                            text = if (formTarget == null) "Add SIP Account" else "Edit SIP Account",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, Indigo600.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Row + Auto-Start Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(Indigo600, Violet500))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Dns,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (formTarget == null) "New SIP Account" else "Edit SIP Account",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Extension, Domain & Protocol",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Auto-Boot",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { autoStart = it },
                            modifier = Modifier.scale(0.75f)
                        )
                    }
                }

                // অ্যাকাউন্টের নাম (বাধ্যতামূলক)
                SlimInputField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = "Account Name *",
                    placeholder = "e.g. Office, Home",
                    enabled = !formLocked,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameTaken) {
                    Text(
                        text = "This name is already used by another account.",
                        fontSize = 11.sp,
                        color = Red500
                    )
                }

                // Row 1 (2 Columns): Slim Username & Password side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlimInputField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username *",
                        placeholder = "1001",
                        enabled = !formLocked,
                        modifier = Modifier.weight(1f)
                    )

                    SlimInputField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password *",
                        placeholder = "••••••",
                        enabled = !formLocked,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingContent = {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Show Password",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { passwordVisible = !passwordVisible }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2 (2 Columns): Slim SIP Domain/IP & Port side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlimInputField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = "SIP Domain / IP *",
                        placeholder = "sip.server.com",
                        enabled = !formLocked,
                        modifier = Modifier.weight(1.75f)
                    )

                    SlimInputField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = "Port",
                        placeholder = "5060",
                        keyboardType = KeyboardType.Number,
                        enabled = !formLocked,
                        modifier = Modifier.weight(0.85f)
                    )
                }

                // Row 3: Protocol Segmented Pills (UDP / TCP / TLS) + Advanced Options Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        transportOptions.forEach { option ->
                            val selected = transport.equals(option, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) Sky500 else Color.Transparent)
                                    .clickable(enabled = !formLocked) {
                                        transport = option
                                        if (option == "TLS" && port == "5060") {
                                            port = "5061"
                                        } else if (option != "TLS" && port == "5061") {
                                            port = "5060"
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 30.dp)
                    ) {
                        Text(
                            text = if (showAdvanced) "Hide Advanced ▲" else "Proxy / NAT ▼",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Indigo600
                        )
                    }
                }

                // Collapsible Advanced Fields (Display Name & Outbound Proxy)
                AnimatedVisibility(visible = showAdvanced) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SlimInputField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = "Display Name",
                            placeholder = "Optional",
                            enabled = !formLocked,
                            modifier = Modifier.weight(1f)
                        )

                        SlimInputField(
                            value = proxy,
                            onValueChange = { proxy = it },
                            label = "Outbound Proxy",
                            placeholder = "Optional",
                            enabled = !formLocked,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // NAT (STUN/TURN): ফাঁকা রাখলে বন্ধ। কল যায় কিন্তু কথা/ইনকামিং আসে না হলে সার্ভারের দেওয়া মান বসান
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SlimInputField(
                            value = stunServer,
                            onValueChange = { stunServer = it },
                            label = "STUN / TURN Server",
                            placeholder = "host:3478 (optional)",
                            enabled = !formLocked,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SlimInputField(
                                value = turnUsername,
                                onValueChange = { turnUsername = it },
                                label = "TURN Username",
                                placeholder = "Optional",
                                enabled = !formLocked,
                                modifier = Modifier.weight(1f)
                            )
                            SlimInputField(
                                value = turnPassword,
                                onValueChange = { turnPassword = it },
                                label = "TURN Password",
                                placeholder = "Optional",
                                enabled = !formLocked,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Primary Connect / Disconnect Button
                if (formState == SipRegistrationState.REGISTERED) {
                    Button(
                        onClick = { formTarget?.let { onUnregister(it.id) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red500)
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect SIP Line", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                if (formState == SipRegistrationState.REGISTERING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp),
                            enabled = false
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting...", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { formTarget?.let { onUnregister(it.id) } },
                            modifier = Modifier.heightIn(min = 42.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel", fontSize = 13.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val account = SipAccountData(
                                id = formTarget?.id ?: "",
                                name = trimmedName,
                                username = username.trim(),
                                password = password,
                                domain = domain.trim(),
                                port = port.toIntOrNull() ?: 5060,
                                transport = transport,
                                proxy = proxy.trim(),
                                stunServer = stunServer.trim(),
                                turnUsername = turnUsername.trim(),
                                turnPassword = turnPassword,
                                displayName = displayName.trim(),
                                autoStart = autoStart
                            )
                            onSaveAndRegister(account)
                            showForm = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green500),
                        enabled = trimmedName.isNotBlank() && !nameTaken && username.isNotBlank() && password.isNotBlank() && domain.isNotBlank()
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when {
                                formState == SipRegistrationState.REGISTERED -> "Save & Reconnect SIP"
                                formState == SipRegistrationState.ERROR -> "Retry SIP Connection"
                                else -> "Save & Connect SIP"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
                }
            }
        }

    }
        SupportSlot(settingsTab == -1)
    }
}

/** One row of the settings home list: icon, title, subtitle, chevron */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // রঙিন গোল ব্যাকগ্রাউন্ডে আইকন
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Slim 38dp-height input box (replaces bulky 56dp+ OutlinedTextField)
 */
@Composable
private fun SlimInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            textStyle = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(Indigo600),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (enabled) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        trailingContent()
                    }
                }
            }
        )
    }
}

@Composable
private fun CompactPermissionTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    statusText: String,
    isEnabled: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val tileColor = if (isEnabled) Green500 else accentColor

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = tileColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tileColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(tileColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Filled.Check else icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = tileColor,
                    maxLines = 1
                )
            }
        }
    }
}
