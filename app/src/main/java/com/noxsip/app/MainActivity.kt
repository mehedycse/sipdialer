package com.noxsip.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.noxsip.app.data.AccountRepository
import com.noxsip.app.data.AppDatabase
import com.noxsip.app.service.SipForegroundService
import com.noxsip.app.sip.SipCallState
import com.noxsip.app.sip.SipEngine
import com.noxsip.app.sip.SipRegistrationState
import com.noxsip.app.telecom.TelecomHelper
import com.noxsip.app.ui.navigation.Screen
import com.noxsip.app.ui.screens.*
import com.noxsip.app.ui.theme.Amber500
import com.noxsip.app.ui.theme.Red500
import com.noxsip.app.ui.theme.SipDialerTheme
import com.noxsip.app.util.ContactsHelper
import com.noxsip.app.util.DeviceContact
import com.noxsip.app.util.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var sipEngine: SipEngine
    private lateinit var accountRepository: AccountRepository
    private lateinit var database: AppDatabase

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        TelecomHelper.registerPhoneAccount(this)
    }

    // Beta: ডিফল্ট ডায়ালার না থাকলে অ্যাপ খোলার সময় রোল চাওয়ার পপ-আপ (৬০ সেকেন্ডের মধ্যে বারবার নয়)
    private var lastDialerPromptMs = 0L
    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        TelecomHelper.refreshDefaultDialer(this)
    }

    private fun promptDefaultDialerIfNeeded() {
        if (TelecomHelper.refreshDefaultDialer(this)) return
        val now = System.currentTimeMillis()
        if (now - lastDialerPromptMs < 60_000L) return
        lastDialerPromptMs = now
        TelecomHelper.createDefaultDialerRoleIntent(this)?.let {
            try {
                dialerRoleLauncher.launch(it)
            } catch (_: Exception) {
            }
        }
    }

    /** ফোন সাপোর্ট করলে সর্বোচ্চ রিফ্রেশ রেট (যেমন 120Hz) চায় */
    private fun requestHighRefreshRate() {
        try {
            val disp = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val best = disp?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Exception) {
        }
    }

    /** হোম/রিসেন্টসে যাওয়ার মুহূর্তেই (onPause-এর আগে) কল-বারকে জানাই */
    override fun onUserLeaveHint() {
        com.noxsip.app.telecom.CallBarOverlay.appForeground.value = false
        super.onUserLeaveHint()
    }

    override fun onResume() {
        super.onResume()
        com.noxsip.app.telecom.CallBarOverlay.appForeground.value = true
        promptDefaultDialerIfNeeded()
    }

    override fun onPause() {
        com.noxsip.app.telecom.CallBarOverlay.appForeground.value = false
        super.onPause()
    }

    override fun onDestroy() {
        com.noxsip.app.telecom.CallBarOverlay.appForeground.value = false
        com.noxsip.app.telecom.CallBarOverlay.onCallRoute.value = false
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ইনকামিং কলের নোটিফিকেশন/পপআপ থেকে এলে পূর্ণ-স্ক্রিন কল পেজ খুলি
        if (intent.getBooleanExtra("incoming_call", false)) {
            com.noxsip.app.telecom.IncomingUiRequest.request()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        if (intent?.getBooleanExtra("incoming_call", false) == true) {
            com.noxsip.app.telecom.IncomingUiRequest.request()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        sipEngine = SipEngine.getInstance(applicationContext)
        accountRepository = AccountRepository(applicationContext)
        database = AppDatabase.getInstance(applicationContext)

        TelecomHelper.registerPhoneAccount(this)

        if (!PermissionHelper.hasAllPermissions(this)) {
            permissionLauncher.launch(PermissionHelper.getMissingPermissions(this))
        }

        lifecycleScope.launch {
            val savedAccount = accountRepository.getAccount().first()
            if (savedAccount != null && sipEngine.registrationState.value == SipRegistrationState.UNREGISTERED) {
                // সব সংরক্ষিত অ্যাকাউন্ট একসাথে রেজিস্টার (নির্বাচিতটি আউটগোয়িংয়ের জন্য)
                sipEngine.registerAllSaved()
                SipForegroundService.start(this@MainActivity)
            }
        }

        setContent {
            SipDialerTheme {
                SipDialerApp(
                    sipEngine = sipEngine,
                    accountRepository = accountRepository,
                    database = database,
                    onStartService = {
                        TelecomHelper.registerPhoneAccount(this)
                        SipForegroundService.start(this)
                    },
                    onStopService = {
                        SipForegroundService.stop(this)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SipDialerApp(
    sipEngine: SipEngine,
    accountRepository: AccountRepository,
    database: AppDatabase,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val registrationState by sipEngine.registrationState.collectAsStateWithLifecycle()
    val registrationError by sipEngine.registrationError.collectAsStateWithLifecycle()
    val accountStates by sipEngine.accountStates.collectAsStateWithLifecycle()
    val callAccountUser by sipEngine.callAccountUser.collectAsStateWithLifecycle()
    val callState by sipEngine.callState.collectAsStateWithLifecycle()
    val isMuted by sipEngine.isMuted.collectAsStateWithLifecycle()
    val isSpeaker by sipEngine.isSpeaker.collectAsStateWithLifecycle()
    val isRecording by sipEngine.isRecording.collectAsStateWithLifecycle()
    val callQuality by sipEngine.callQuality.collectAsStateWithLifecycle()
    val callRecords by database.callHistoryDao().getRecentCallRecords()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedContacts by database.savedContactDao().getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var dialPrefill by remember { mutableStateOf<String?>(null) }
    var pendingAdd by remember { mutableStateOf<com.noxsip.app.ui.screens.AddContactRequest?>(null) }
    val currentAccount by accountRepository.getAccount()
        .collectAsStateWithLifecycle(initialValue = null)
    val savedAccounts by accountRepository.getAccounts()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var hasContactsPermission by remember {
        mutableStateOf(ContactsHelper.hasContactsPermission(context))
    }
    var contacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    // নাম খোঁজার জন্য: ফোনবুক + Client/Office (এগুলো ডায়ালার, কল স্ক্রিন ও মেসেজে নাম দেখায়)
    val allContacts = remember(contacts, savedContacts) {
        contacts + savedContacts.map {
            DeviceContact(
                id = "saved_${it.id}",
                name = it.name,
                number = it.number,
                normalizedDigits = it.number.filter { c -> c.isDigit() || c == '+' }
            )
        }
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactsPermission = granted
        if (granted) {
            scope.launch {
                contacts = ContactsHelper.loadContacts(context)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (com.noxsip.app.util.CallLogSync.hasPermission(context)) {
                    scope.launch { com.noxsip.app.util.CallLogSync.syncFromSystem(context) }
                }
                val granted = ContactsHelper.hasContactsPermission(context)
                hasContactsPermission = granted
                if (granted) {
                    scope.launch {
                        contacts = ContactsHelper.loadContacts(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // কল-বার পপ-আপের জন্য: কল স্ক্রিন খোলা কি না। নেভিগেশন শুরু হওয়ার মুহূর্তেই জানাই (রিকম্পোজের অপেক্ষা না করে)
    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            com.noxsip.app.telecom.CallBarOverlay.onCallRoute.value = destination.route == Screen.InCall.route
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    val incomingTick by com.noxsip.app.telecom.IncomingUiRequest.tick.collectAsStateWithLifecycle()
    LaunchedEffect(callState, incomingTick) {
        when (callState) {
            is SipCallState.Incoming -> {
                // লক স্ক্রিন/স্ক্রিন বন্ধ থাকলে, অথবা পপআপে ট্যাপ করলে, পূর্ণ-স্ক্রিন কল পেজ
                val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                val locked = km?.isKeyguardLocked == true || pm?.isInteractive == false
                if ((com.noxsip.app.telecom.IncomingUiRequest.pending || locked) &&
                    currentRoute != Screen.InCall.route
                ) {
                    navController.navigate(Screen.InCall.route) {
                        launchSingleTop = true
                    }
                }
            }
            is SipCallState.Outgoing,
            is SipCallState.Connected,
            is SipCallState.OnHold -> {
                if (currentRoute != Screen.InCall.route) {
                    navController.navigate(Screen.InCall.route) {
                        launchSingleTop = true
                    }
                }
            }
            is SipCallState.Ended,
            is SipCallState.Idle -> {
                com.noxsip.app.telecom.IncomingUiRequest.clear()
                if (currentRoute == Screen.InCall.route) {
                    // ব্যর্থ কলের কারণ পড়ার জন্য একটু বেশি সময় দেখাই
                    val failedReason = (callState as? SipCallState.Ended)?.reason.orEmpty().startsWith("Call failed")
                    kotlinx.coroutines.delay(if (failedReason) 3500 else 500)
                    if (navController.currentBackStackEntry?.destination?.route == Screen.InCall.route) {
                        navController.popBackStack()
                    }
                    // ফোনের ডায়ালার থেকে শুরু হওয়া কল শেষ হলে আগের অ্যাপ/ডায়ালারে ফিরে যাই
                    if (com.noxsip.app.telecom.NativeDialerOverlay.returnToPreviousAfterCall) {
                        com.noxsip.app.telecom.NativeDialerOverlay.returnToPreviousAfterCall = false
                        (context as? android.app.Activity)?.moveTaskToBack(true)
                    }
                }
            }
            else -> {}
        }
    }

    // ব্যাজ: না-দেখা মিসড কল
    val uiPrefs = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    com.noxsip.app.ui.screens.UpdatePrompt()
    var missedSeenTs by remember { mutableLongStateOf(uiPrefs.getLong("missed_seen_ts", 0L)) }
    LaunchedEffect(currentRoute, callRecords) {
        if (currentRoute == Screen.Dialer.route) {
            val now = System.currentTimeMillis()
            missedSeenTs = now
            uiPrefs.edit().putLong("missed_seen_ts", now).apply()
        }
    }
    val newMissed = callRecords.count { it.isIncoming && !it.isAnswered && it.timestamp > missedSeenTs }

    val bottomNavItems = listOf(
        Triple(Screen.Dialer.route, "Keypad", Icons.Filled.Dialpad),
        Triple(Screen.Contacts.route, "Contacts", Icons.Filled.Contacts),
        Triple(Screen.Account.route, "Settings", Icons.Filled.Settings),
    )

    // নির্বাচিত (আউটগোয়িং) অ্যাকাউন্ট বদল: হেডারের মেনু ও Settings দুই জায়গা থেকেই
    val doSelectAccount: (String) -> Unit = { id ->
        scope.launch {
            accountRepository.selectAccount(id)
            var acc = savedAccounts.firstOrNull { it.id == id }
            if (acc != null) {
                if (!acc.enabled) {
                    accountRepository.setEnabled(id, true)
                    acc = acc.copy(enabled = true)
                }
                sipEngine.register(acc)
                onStartService()
            }
        }
    }
    var showAccountMenu by remember { mutableStateOf(false) }

    // ব্লক তালিকা, ডিটেইলস পেজ থেকে চ্যাট খোলা, WhatsApp/Telegram লিঙ্ক
    LaunchedEffect(Unit) { com.noxsip.app.util.BlockList.init(context) }
    val blockedNumbers by com.noxsip.app.util.BlockList.numbers.collectAsState()
    val openMessengerChat: (String, String) -> Unit = { app, number ->
        val digits = ContactsHelper.chatDigits(number)
        val url = if (app == "telegram") "https://t.me/+$digits" else "https://wa.me/$digits"
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "App not installed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
            if (currentRoute != Screen.InCall.route) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF1E3FA0),
                                    Color(0xFF2563EB),
                                    Color(0xFF3B8BEB)
                                )
                            )
                        )
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.WifiCalling3,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = savedAccounts.size > 1) { showAccountMenu = true }
                                ) {
                                    Text(
                                        text = "Nox SIP",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = (if (currentAccount != null && currentAccount!!.username.isNotBlank()) {
                                            currentAccount!!.name.ifBlank { currentAccount!!.username }
                                        } else {
                                            "Tap Settings to configure SIP"
                                        }) + (if (savedAccounts.size > 1) "  ▾" else ""),
                                        color = Color(0xFFE0ECFF),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DropdownMenu(
                                    expanded = showAccountMenu,
                                    onDismissRequest = { showAccountMenu = false }
                                ) {
                                    savedAccounts.forEach { acc ->
                                        val st = accountStates[acc.id]
                                        val dot = when {
                                            !acc.enabled -> Color(0xFF94A3B8)
                                            st == SipRegistrationState.REGISTERED -> Color(0xFF34D399)
                                            st == SipRegistrationState.REGISTERING -> Amber500
                                            st == SipRegistrationState.ERROR -> Red500
                                            else -> Color(0xFF94A3B8)
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(dot)
                                                    )
                                                    Text(
                                                        acc.name.ifBlank { acc.username },
                                                        fontWeight = if (acc.id == currentAccount?.id) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                if (acc.id == currentAccount?.id) {
                                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = com.noxsip.app.ui.theme.Green500, modifier = Modifier.size(18.dp))
                                                }
                                            },
                                            onClick = {
                                                showAccountMenu = false
                                                doSelectAccount(acc.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        val pillColor = when (registrationState) {
                            SipRegistrationState.REGISTERED -> Color(0xFF34D399)
                            SipRegistrationState.REGISTERING -> Amber500
                            SipRegistrationState.ERROR -> Red500
                            SipRegistrationState.UNREGISTERED -> Color(0xFF94A3B8)
                        }
                        val pillLabel = when (registrationState) {
                            SipRegistrationState.REGISTERED -> "Online"
                            SipRegistrationState.REGISTERING -> "Connecting"
                            SipRegistrationState.ERROR -> "Error"
                            SipRegistrationState.UNREGISTERED -> "Offline"
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .border(1.dp, pillColor.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                                .clickable {
                                    navController.navigate(Screen.Account.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(pillColor)
                            )
                            Text(
                                text = pillLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // কিবোর্ড খোলা থাকলে নিচের মেনু লুকাই, যাতে মেসেজ বক্স কিবোর্ডের ঠিক ওপরে বসে
            val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            if (currentRoute != Screen.InCall.route && !imeOpen) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        bottomNavItems.forEach { (route, label, icon) ->
                            val selected = currentRoute == route
                            // রং সঙ্গে সঙ্গে বদলায় (কোনো ফেড/রিপল নেই)
                            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        navController.navigate(route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                val badgeCount = when (route) {
                                    Screen.Dialer.route -> if (selected) 0 else newMissed
                                    else -> 0
                                }
                                if (badgeCount > 0) {
                                    BadgedBox(badge = {
                                        Badge(containerColor = Red500) {
                                            Text(if (badgeCount > 99) "99+" else badgeCount.toString(), color = Color.White)
                                        }
                                    }) { Icon(icon, contentDescription = label, tint = tint) }
                                } else {
                                    Icon(icon, contentDescription = label, tint = tint)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = tint,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dialer.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dialer.route) {
                DialerScreen(
                    registrationState = registrationState,
                    contacts = allContacts,
                    prefillNumber = dialPrefill,
                    onPrefillConsumed = { dialPrefill = null },
                    recentCalls = callRecords,
                    onAddToCategory = { cat, num, name ->
                        pendingAdd = com.noxsip.app.ui.screens.AddContactRequest(cat, num, name)
                        navController.navigate(Screen.Contacts.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            com.noxsip.app.util.CallLogSync.markAllCleared(context)
                            database.callHistoryDao().deleteAllCallRecords()
                        }
                    },
                    onDeleteCalls = { recs ->
                        scope.launch {
                            recs.forEach { rec ->
                                com.noxsip.app.util.CallLogSync.markDeleted(context, rec)
                                database.callHistoryDao().deleteCallRecord(rec)
                            }
                        }
                    },
                    blockedNumbers = blockedNumbers,
                    onBlock = { num ->
                        com.noxsip.app.util.BlockList.add(context, num)
                        android.widget.Toast.makeText(context, "Added to block list", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onUnblock = { num ->
                        com.noxsip.app.util.BlockList.remove(context, num)
                        android.widget.Toast.makeText(context, "Removed from block list", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onMessage = { num ->
                        // ফোনের নিজস্ব মেসেজ অ্যাপে ওই নম্বরের চ্যাট খোলে
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_SENDTO,
                                    android.net.Uri.parse("smsto:" + android.net.Uri.encode(num))
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "No messaging app found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenChat = openMessengerChat,
                    detectMessengers = { number -> ContactsHelper.detectMessengers(context, number) },
                    onCall = { number ->
                        if (!TelecomHelper.placeOutgoingCall(context, number)) {
                            com.noxsip.app.telecom.NativeDialerOverlay.returnToPreviousAfterCall = false
                            sipEngine.makeCall(number)
                        }
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.Account.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Screen.Contacts.route) {
                ContactsScreen(
                    contacts = contacts,
                    hasContactsPermission = hasContactsPermission,
                    onRequestPermission = {
                        contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                    // বেটা: কন্টাক্টে টাচ করলে কল নয়, নম্বর ডায়ালপ্যাডে বসে (SIM/SIP সেখানে বাছা যায়)
                    onCallContact = { number ->
                        dialPrefill = number
                        navController.navigate(Screen.Dialer.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    savedContacts = savedContacts,
                    onSaveContact = { c -> scope.launch { database.savedContactDao().insert(c) } },
                    onDeleteSavedContact = { c -> scope.launch { database.savedContactDao().delete(c) } },
                    onOpenChat = openMessengerChat,
                    detectMessengers = { number -> ContactsHelper.detectMessengers(context, number) },
                    pendingAdd = pendingAdd,
                    onPendingConsumed = { pendingAdd = null }
                )
            }

            composable(Screen.Account.route) {
                AccountSetupScreen(
                    currentAccount = currentAccount,
                    accounts = savedAccounts,
                    onSelectAccount = doSelectAccount,
                    onToggleEnabled = { id, enable ->
                        scope.launch {
                            accountRepository.setEnabled(id, enable)
                            val acc = accountRepository.getAccounts().first().firstOrNull { it.id == id }
                            if (acc != null) {
                                if (enable) {
                                    sipEngine.register(acc, select = acc.id == currentAccount?.id)
                                    onStartService()
                                } else {
                                    sipEngine.unregisterAccount(id)
                                    if (sipEngine.accountStates.value.values.none { it != SipRegistrationState.UNREGISTERED }) {
                                        onStopService()
                                    }
                                }
                            }
                        }
                    },
                    onDeleteAccount = { id ->
                        scope.launch {
                            val wasSelected = currentAccount?.id == id
                            accountRepository.deleteAccount(id)
                            sipEngine.unregisterAccount(id)
                            if (wasSelected) {
                                val next = accountRepository.getAccount().first()
                                if (next != null) {
                                    sipEngine.register(next)
                                    onStartService()
                                } else {
                                    onStopService()
                                }
                            }
                        }
                    },
                    registrationState = registrationState,
                    registrationError = registrationError,
                    accountStates = accountStates,
                    onSaveAndRegister = { account ->
                        scope.launch {
                            val saved = accountRepository.saveAccount(account)
                            sipEngine.register(saved)
                            onStartService()
                        }
                    },
                    onUnregister = { id ->
                        sipEngine.unregisterAccount(id)
                        // কোনো অ্যাকাউন্টই চালু না থাকলে তবেই সার্ভিস বন্ধ
                        if (sipEngine.accountStates.value.values.none { it != SipRegistrationState.UNREGISTERED }) {
                            onStopService()
                        }
                    }
                )
            }

            composable(Screen.InCall.route) {
                InCallScreen(
                    callState = callState,
                    isMuted = isMuted,
                    isSpeaker = isSpeaker,
                    callDurationProvider = { sipEngine.getCallDuration() },
                    quality = callQuality,
                    contacts = allContacts,
                    accountUser = callAccountUser,
                    onAnswer = { sipEngine.answerCall() },
                    onDecline = { sipEngine.declineCall() },
                    onEndCall = { sipEngine.endCall() },
                    onMuteToggle = { sipEngine.toggleMute() },
                    onSpeakerToggle = { sipEngine.toggleSpeaker() },
                    onHoldToggle = { sipEngine.toggleHold() },
                    onDtmf = { sipEngine.sendDtmf(it) },
                    isRecording = isRecording,
                    onRecordToggle = { sipEngine.toggleRecording() }
                )
            }
        }
    }

        // Top Incoming Call Popup inside MainActivity if system overlay permission is not enabled
        val incomingState = callState as? SipCallState.Incoming
        if (incomingState != null && currentRoute != Screen.InCall.route &&
            !com.noxsip.app.telecom.NativeDialerOverlay.canShowOverlay(context)
        ) {
            val cleanCaller = incomingState.remoteAddress
                .removePrefix("sip:")
                .substringBefore("@")
                .ifEmpty { incomingState.remoteAddress }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { com.noxsip.app.telecom.IncomingUiRequest.request() },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF14253E),
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B6998))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(
                            text = "Incoming call via ${callAccountUser ?: "SIP"}",
                            color = Color(0xFF93C5FD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = com.noxsip.app.util.ContactsHelper.nameFor(cleanCaller, allContacts) ?: cleanCaller,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { sipEngine.declineCall() },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red500)
                        ) {
                            Text("Decline", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Button(
                            onClick = { sipEngine.answerCall() },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = com.noxsip.app.ui.theme.Green500)
                        ) {
                            Text("Answer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
