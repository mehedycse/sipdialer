package com.noxsip.app.ui.screens

import com.noxsip.app.ui.theme.themed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxsip.app.sip.SipRegistrationState
import com.noxsip.app.ui.components.DialPad
import com.noxsip.app.ui.theme.Green500
import com.noxsip.app.ui.theme.Indigo600
import com.noxsip.app.util.DeviceContact

/** পেস্ট করা নম্বরের বাংলা (০-৯), আরবি-ভারতীয় ইত্যাদি যেকোনো ইউনিকোড অঙ্ক ইংরেজি 0-9 করে; শুধু অঙ্ক + * # রাখে */
internal fun normalizeDialInput(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        when {
            ch == '+' || ch == '*' || ch == '#' -> sb.append(ch)
            ch in '0'..'9' -> sb.append(ch)
            Character.isDigit(ch) -> {
                val v = Character.digit(ch, 10)
                if (v in 0..9) sb.append(('0' + v))
            }
        }
    }
    return sb.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    registrationState: SipRegistrationState,
    contacts: List<DeviceContact>,
    recentCalls: List<com.noxsip.app.data.models.CallRecord> = emptyList(),
    onClearHistory: () -> Unit = {},
    onDeleteCalls: (List<com.noxsip.app.data.models.CallRecord>) -> Unit = {},
    onAddToCategory: (category: String, number: String, name: String) -> Unit = { _, _, _ -> },
    blockedNumbers: Set<String> = emptySet(),
    onBlock: (String) -> Unit = {},
    onUnblock: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
    onOpenChat: (app: String, number: String) -> Unit = { _, _ -> },
    detectMessengers: suspend (String) -> Pair<Boolean, Boolean> = { false to false },
    onCall: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    prefillNumber: String? = null,
    onPrefillConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var phoneNumber by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showKeypad by remember { mutableStateOf(true) }
    var filterTab by remember { mutableIntStateOf(0) } // 0 All, 1 Outgoing, 2 Received, 3 Missed
    var searchMode by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var sourceTab by remember { mutableIntStateOf(0) } // 0 All, 1 SIP, 2 SIM
    var pendingDelete by remember { mutableStateOf<CallGroup?>(null) }
    var openSwipeKey by remember { mutableStateOf<String?>(null) }
    var detailsNumber by rememberSaveable { mutableStateOf<String?>(null) }
    val nameByTail = remember(contacts) {
        HashMap<String, String>(contacts.size * 2).also { m ->
            contacts.forEach { c -> m.putIfAbsent(c.normalizedDigits.takeLast(10), c.name) }
        }
    }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchMode) { if (searchMode) searchFocus.requestFocus() }
    val recentsListState = rememberLazyListState()
    // লিস্ট স্ক্রল করলে কীপ্যাড নেমে যায় (ফোনের ডায়ালারের মতো); নম্বর টাইপ শুরু হলে আবার আসে
    LaunchedEffect(recentsListState) {
        snapshotFlow { recentsListState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && phoneNumber.isEmpty()) showKeypad = false
        }
    }
    LaunchedEffect(phoneNumber) { if (phoneNumber.isNotEmpty()) showKeypad = true }
    // বেটা: কন্টাক্ট লিস্ট থেকে আসা নম্বর ডায়ালপ্যাডে বসানো
    LaunchedEffect(prefillNumber) {
        if (!prefillNumber.isNullOrBlank()) {
            phoneNumber = prefillNumber
            searchMode = false
            searchQuery = ""
            showKeypad = true
            onPrefillConsumed()
        }
    }
    val isRegistered = registrationState == SipRegistrationState.REGISTERED
    val clipboardManager = LocalClipboardManager.current
    // Beta: SIM কল (Telecom দিয়ে); জরুরি নম্বর সবসময় সরাসরি SIM/Telecom দিয়ে, SIP-এ নয়
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sims = remember { com.noxsip.app.telecom.TelecomHelper.getAvailableSimAccounts(ctx) }
    var dialCodeMsg by remember { mutableStateOf<String?>(null) }
    var showCallChooser by remember { mutableStateOf(false) }
    val placeSimCall = { slot: Int ->
        if (phoneNumber.isNotBlank()) {
            if (!com.noxsip.app.beta.DialCodes.handle(ctx, phoneNumber, sims.getOrNull(slot)?.handle) { dialCodeMsg = it }) {
                com.noxsip.app.telecom.TelecomHelper.placeSimCallWithHandle(ctx, phoneNumber, sims.getOrNull(slot)?.handle)
            }
            phoneNumber = ""
        }
    }
    // ফোনের ডায়ালারের মতো: *#06# বা *#*#...#*#* টাইপ শেষ হতেই নিজে চলে, SIM/SIP বোতাম লাগে না
    LaunchedEffect(phoneNumber) {
        if (com.noxsip.app.beta.DialCodes.isAutoCode(phoneNumber)) {
            val code = phoneNumber
            phoneNumber = ""
            com.noxsip.app.beta.DialCodes.handle(ctx, code, sims.firstOrNull()?.handle) { dialCodeMsg = it }
        }
    }
    dialCodeMsg?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialCodeMsg = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { dialCodeMsg = null }) { Text("OK") }
            },
            text = { Text(msg) }
        )
    }
    if (showCallChooser) {
        // ফোনের ডায়ালার থেকে আসা পপ-আপের (NativeDialerOverlay) হুবহু একই চেহারা ও জায়গা: নিচে সাদা কার্ড
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCallChooser = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showCallChooser = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 56.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(22.dp),
                    color = themed(Color.White, Color(0xFF0E0E0E)),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, themed(Color(0xFFE2E8F0), Color(0xFF333333)))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Select Calling Account", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = themed(Color(0xFF1E293B), Color(0xFFF8FAFC)))
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val simLabels = if (sims.size > 1) listOf("SIM 1", "SIM 2") else listOf("SIM 1")
                            simLabels.forEachIndexed { idx, label ->
                                Box(
                                    modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(24.dp))
                                        .background(if (idx == 0) Color(0xFF2E8B6E) else Color(0xFF3E7FA6))
                                        .clickable { showCallChooser = false; placeSimCall(idx) },
                                    contentAlignment = Alignment.Center
                                ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                            Box(
                                modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(24.dp))
                                    .background(Color(0xFF3B6998))
                                    .clickable {
                                        showCallChooser = false
                                        if (phoneNumber.isNotBlank()) {
                                            if (isRegistered) {
                                                onCall(phoneNumber)
                                                phoneNumber = ""
                                            } else {
                                                onOpenSettings?.invoke()
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text("SIP", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(24.dp))
                                .background(themed(Color(0xFFF1F5F9), Color(0xFF1A1A1A))).clickable { showCallChooser = false },
                            contentAlignment = Alignment.Center
                        ) { Text("Cancel", color = themed(Color(0xFF475569), Color(0xFFD4D4D8)), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
    var numberMenu by remember { mutableStateOf(false) }
    val pasteFromClipboard = {
        val clip = clipboardManager.getText()?.text?.trim()
        val cleaned = clip?.let { normalizeDialInput(it) }
        if (!cleaned.isNullOrEmpty()) {
            phoneNumber = cleaned
            showKeypad = true
        }
    }

    // Filter contacts by search query OR typed keypad digits
    val matchingContacts = remember(contacts, searchQuery, phoneNumber) {
        val q = searchQuery.trim().ifEmpty { phoneNumber.trim() }
        if (q.isEmpty()) {
            emptyList()
        } else {
            val digits = q.filter { it.isDigit() || it == '+' }
            contacts.filter { c ->
                c.name.contains(q, ignoreCase = true) ||
                        c.number.contains(q, ignoreCase = true) ||
                        (digits.isNotEmpty() && c.normalizedDigits.contains(digits))
            }.take(20)
        }
    }

    pendingDelete?.let { g ->
        val gName = nameByTail[g.key] ?: g.records.first().displayName.ifBlank { g.number }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete calls?") },
            text = { Text("Removes ${g.records.size} call record(s) with $gName from the call list.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCalls(g.records)
                    pendingDelete = null
                    openSwipeKey = null
                }) { Text("Delete", color = com.noxsip.app.ui.theme.Red500) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    // নম্বরের বিস্তারিত পেজ (ফুল সাইজ)
    val dn = detailsNumber
    if (dn != null) {
        val dnKey = numKey(dn)
        val recs = remember(recentCalls, dnKey) {
            recentCalls.filter { numKey(it.remoteAddress) == dnKey }.sortedByDescending { it.timestamp }
        }
        val isBlockedNow = com.noxsip.app.util.BlockList.isBlocked(dn, blockedNumbers)
        CallDetailsScreen(
            number = dn,
            name = nameByTail[dnKey] ?: recs.firstOrNull { it.displayName.isNotBlank() }?.displayName,
            records = recs,
            isBlocked = isBlockedNow,
            detectMessengers = detectMessengers,
            onBack = { detailsNumber = null },
            onCall = { if (isRegistered) onCall(dn) else onOpenSettings?.invoke() },
            onMessage = { onMessage(dn) },
            onOpenChat = onOpenChat,
            onAddToCategory = { cat ->
                onAddToCategory(cat, dn, nameByTail[dnKey] ?: recs.firstOrNull { it.displayName.isNotBlank() }?.displayName.orEmpty())
            },
            onToggleBlock = { if (isBlockedNow) onUnblock(dn) else onBlock(dn) }
        )
        return
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear call history?") },
            text = { Text("All call records will be deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearHistory() }) {
                    Text("Clear", color = com.noxsip.app.ui.theme.Red500)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Slim Contact Search Bar at top of Keypad
        if (searchMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        BasicTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocus),
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(Indigo600),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(themed(Color(0xFFF0F3FA), Color(0xFF1A1A1A)))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search Contacts",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search contact name or number...",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }
        )

        TextButton(onClick = {
            searchQuery = ""
            searchMode = false
            showKeypad = true
        }) { Text("Cancel", color = Green500, fontWeight = FontWeight.SemiBold) }
        }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Dial", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val clip = clipboardManager.getText()?.text?.trim()
                        val cleaned = clip?.let { normalizeDialInput(it) }
                        if (!cleaned.isNullOrEmpty()) {
                            phoneNumber = cleaned
                            showKeypad = true
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste number", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    if (recentCalls.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear call history", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    IconButton(onClick = {
                        searchMode = true
                        showKeypad = false
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search contacts", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Matching Contacts List OR Dialed Number Display Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (matchingContacts.isNotEmpty() && (searchQuery.isNotBlank() || phoneNumber.isNotEmpty())) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(matchingContacts) { index, contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        phoneNumber = contact.normalizedDigits
                                        searchQuery = ""
                                    }
                                    .padding(horizontal = 4.dp, vertical = 7.dp),
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
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Indigo600.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Person,
                                            contentDescription = null,
                                            tint = Indigo600,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = contact.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = contact.number,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (isRegistered) {
                                            onCall(contact.normalizedDigits)
                                            phoneNumber = ""
                                            searchQuery = ""
                                        } else {
                                            onOpenSettings?.invoke()
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Call,
                                        contentDescription = "Call",
                                        tint = Green500,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (index < matchingContacts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 42.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            } else if (phoneNumber.isNotEmpty() || searchQuery.isNotBlank()) {
                // কোনো কন্টাক্ট মেলেনি: ফাঁকা (নম্বর কীপ্যাডের উপরের বক্সে দেখায়)
            } else if (phoneNumber.isEmpty() && recentCalls.isNotEmpty()) {
                val recents = remember(recentCalls, filterTab, sourceTab) {
                    recentCalls
                        .filter {
                            when (sourceTab) {
                                1 -> it.accountUser != "SIM"
                                2 -> it.accountUser == "SIM"
                                else -> true
                            }
                        }
                        .filter {
                            when (filterTab) {
                                1 -> !it.isIncoming
                                2 -> it.isIncoming && it.isAnswered
                                3 -> it.isIncoming && !it.isAnswered
                                else -> true
                            }
                        }
                        .take(300)
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    // All | Missed ফিল্টার
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(themed(Color(0xFFF2F3F5), Color(0xFF1A1A1A)))
                            .border(1.dp, themed(Color(0xFFE3E5EA), Color(0xFF333333)), RoundedCornerShape(20.dp))
                            .padding(3.dp)
                    ) {
                        listOf("All", "Outgoing", "Received", "Missed").forEachIndexed { tabIndex, label ->
                            val selected = filterTab == tabIndex
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(if (selected) themed(Color.White, Color(0xFF0E0E0E)) else Color.Transparent)
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null
                                    ) { filterTab = tabIndex }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) themed(Color(0xFF111111), Color(0xFFF8FAFC)) else themed(Color(0xFF444B55), Color(0xFFD4D4D8))
                                )
                            }
                        }
                    }
                    // উৎস ফিল্টার: সব / SIP / SIM
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All sources", "SIP", "SIM").forEachIndexed { i, label ->
                            val sel = sourceTab == i
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (sel) Color.White else themed(Color(0xFF444B55), Color(0xFFD4D4D8)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) Color(0xFF2563EB) else themed(Color(0xFFF2F3F5), Color(0xFF1A1A1A)))
                                    .clickable { sourceTab = i }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                    if (recents.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Filled.CallMissed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No calls here", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                Text("Try another filter above", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            }
                        }
                    } else {
                        val groups = remember(recents) {
                            // একই নম্বরের সব কল (ডায়াল/রিসিভ/মিসড) একটি সারিতে; সবচেয়ে নতুন ওপরে
                            val map = LinkedHashMap<String, MutableList<com.noxsip.app.data.models.CallRecord>>()
                            recents.forEach { r -> map.getOrPut(numKey(r.remoteAddress)) { mutableListOf() }.add(r) }
                            map.map { (k, v) -> CallGroup(k, cleanAddress(v.first().remoteAddress), v) }
                        }
                        LazyColumn(
                            state = recentsListState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(bottom = 90.dp)
                        ) {
                            itemsIndexed(groups, key = { _, g -> g.key.ifEmpty { g.number } }, contentType = { _, _ -> "call" }) { index, g ->
                                val blocked = com.noxsip.app.util.BlockList.isBlocked(g.number, blockedNumbers)
                                SwipeRow(
                                    isOpen = openSwipeKey == g.key,
                                    onOpenChange = { open -> openSwipeKey = if (open) g.key else if (openSwipeKey == g.key) null else openSwipeKey },
                                    actions = {
                                        SwipeAction("Delete", com.noxsip.app.ui.theme.Red500, Icons.Filled.DeleteOutline) {
                                            pendingDelete = g
                                        }
                                        SwipeAction(
                                            if (blocked) "Unblock" else "Block",
                                            Color(0xFF6B7280),
                                            Icons.Filled.Block
                                        ) {
                                            if (blocked) onUnblock(g.number) else onBlock(g.number)
                                            openSwipeKey = null
                                        }
                                    }
                                ) {
                                    CallGroupRow(
                                        group = g,
                                        nameByTail = nameByTail,
                                        blocked = blocked,
                                        // ট্যাপ করলে কল যায় না: নম্বর ডায়ালপ্যাডে লোড হয়
                                        onClick = {
                                            if (openSwipeKey != null) openSwipeKey = null
                                            else {
                                                phoneNumber = g.number
                                                showKeypad = true
                                            }
                                        },
                                        onDetails = { openSwipeKey = null; detailsNumber = g.number }
                                    )
                                }
                                if (index < groups.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (phoneNumber.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Dialpad,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter a number to call",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isRegistered) "Connected \u2022 ready to call" else "Not connected",
                        fontSize = 12.sp,
                        color = if (isRegistered) Green500 else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // কীপ্যাড প্যানেল: ওপরের কোণ গোল, সমতল সংখ্যা (ফোনের নিজস্ব ডায়ালারের মতো)
        // নম্বর বক্স কীপ্যাডের সাদা প্যানেলের ভেতরেই (ফোনের ডায়ালারের মতো); কীপ্যাড লুকালেও নম্বর প্যানেলে থাকে
        if (showKeypad || phoneNumber.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = themed(Color.White, Color(0xFF0E0E0E)),
            shadowElevation = 12.dp
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // নম্বর না থাকলে প্যানেলে শুধু কীপ্যাড; টাইপ/লোড/পেস্ট করলে উপরে নম্বরের সারি যোগ হয়ে প্যানেল উপরের দিকে বড় হয়, মুছলে আবার ছোট
        androidx.compose.animation.AnimatedVisibility(visible = phoneNumber.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().background(themed(Color.White, Color(0xFF0E0E0E))), contentAlignment = Alignment.Center) {
                Text(
                    text = formatDialNumber(phoneNumber),
                    fontSize = if (phoneNumber.length > 14) 22.sp else 28.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = { numberMenu = true }
                        )
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)
                )
                // Copy | Paste: নম্বরের ঠিক উপরে ছোট পিল (ফোনের ডায়ালারের মতো), কীপ্যাডের উপর নয়
                if (numberMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize
                            ): androidx.compose.ui.unit.IntOffset {
                                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        },
                        onDismissRequest = { numberMenu = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = themed(Color.White, Color(0xFF0E0E0E)),
                            shadowElevation = 8.dp
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (phoneNumber.isNotEmpty()) {
                                    Text(
                                        "Copy",
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .clickable {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(phoneNumber))
                                                numberMenu = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                                Text(
                                    "Paste",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clickable {
                                            pasteFromClipboard()
                                            numberMenu = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = showKeypad) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { pasteFromClipboard() }
                )
                .padding(top = 6.dp, bottom = 4.dp)
        ) {
        DialPad(
            onDigitPressed = { digit ->
                phoneNumber += digit
            },
            onLongPressZero = {
                phoneNumber += "+"
            },
            flat = true,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Bottom Action Row (Paste / Call / Backspace)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showKeypad = false },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Dialpad,
                    contentDescription = "Hide keypad",
                    tint = themed(Color(0xFF222222), Color(0xFFE2E8F0)),
                    modifier = Modifier.size(26.dp)
                )
            }

            // একটাই গোল কল বোতাম: ট্যাপে SIM 1 / SIM 2 / SIP বাছাইয়ের পপ-আপ
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Green500)
                    .clickable {
                        if (phoneNumber.isNotBlank()) {
                            if (com.noxsip.app.beta.DialCodes.isCode(phoneNumber) || isEmergencyNumber(phoneNumber)) {
                                placeSimCall(-1)
                            } else {
                                // ফোনের ডায়ালার থেকে আসা পপ-আপেরই হুবহু একই সিস্টেম ওভারলে (ওভারলে অনুমতি থাকলে)
                                if (com.noxsip.app.telecom.NativeDialerOverlay.canShowOverlay(ctx)) {
                                    val dialed = phoneNumber
                                    com.noxsip.app.telecom.NativeDialerOverlay.showAccountChooser(
                                        context = ctx,
                                        number = dialed,
                                        onSimSelected = { h ->
                                            com.noxsip.app.telecom.TelecomHelper.placeSimCallWithHandle(ctx, dialed, h)
                                            phoneNumber = ""
                                        },
                                        onSipSelected = {
                                            if (isRegistered) {
                                                // নম্বর মোছার আগেই "Calling…" পর্দা, যেন হিস্ট্রি তালিকা ঝলকে না ওঠে
                                                com.noxsip.app.telecom.NativeDialerOverlay.pendingSipCall.value = dialed
                                                onCall(dialed)
                                                phoneNumber = ""
                                            } else {
                                                onOpenSettings?.invoke()
                                            }
                                        },
                                        onCancel = {}
                                    )
                                } else {
                                    showCallChooser = true
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "Call",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            run {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                if (phoneNumber.isNotEmpty()) {
                                    phoneNumber = phoneNumber.dropLast(1)
                                }
                            },
                            onLongClick = {
                                phoneNumber = ""
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = if (phoneNumber.isEmpty()) Color(0xFFB4B9C2) else themed(Color(0xFF444B55), Color(0xFFD4D4D8)),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        }
        }
        }
        }
        }
    }

    // কীপ্যাড লুকানো থাকলে নিচে-বামে সবুজ গ্রিড বাটন; ট্যাপ করলে কীপ্যাড ফিরে আসে
    if (!showKeypad) {
        FloatingActionButton(
            onClick = { showKeypad = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = if (phoneNumber.isNotEmpty()) 90.dp else 24.dp),
            containerColor = themed(Color.White, Color(0xFF0E0E0E)),
            contentColor = themed(Color(0xFF222222), Color(0xFFE2E8F0)),
            shape = CircleShape
        ) {
            Icon(Icons.Filled.Dialpad, contentDescription = "Show keypad")
        }
    }
    }
}

/** জরুরি কল নম্বর: সবসময় সরাসরি Telecom দিয়ে যায় (কোনো SIP/রাউটিং লজিক ছাড়া) */
private fun isEmergencyNumber(n: String): Boolean =
    n.filter { it.isDigit() } in setOf("112", "999", "911", "000", "110", "119")

private val TIME_FORMAT = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
private val DAY_FORMAT = java.text.SimpleDateFormat("MMM dd", java.util.Locale.US)

/** নম্বর দেখানোর জন্য শুধু ফরম্যাট (স্পেস): 01711 666637, +880 1711 666637 */
private fun formatDialNumber(n: String): String = when {
    n.startsWith("+880") && n.length > 8 -> "+880 ${n.substring(4, 8)} ${n.substring(8)}"
    n.startsWith("+880") && n.length > 4 -> "+880 ${n.substring(4)}"
    n.startsWith("01") && n.length > 5 -> "${n.substring(0, 5)} ${n.substring(5)}"
    else -> n
}

private fun cleanAddress(addr: String): String = addr.removePrefix("sip:").substringBefore("@")

/** নম্বর মেলানোর কী: শেষ ১০ ডিজিট (+880 / 0 ইত্যাদি প্রিফিক্স নির্বিশেষে) */
private fun numKey(addr: String): String {
    val d = cleanAddress(addr).filter { it.isDigit() }
    return if (d.length >= 10) d.takeLast(10) else d
}

private data class CallGroup(
    val key: String,
    val number: String,
    val records: List<com.noxsip.app.data.models.CallRecord>
)

private fun shortTime(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) TIME_FORMAT.format(java.util.Date(ts)) else DAY_FORMAT.format(java.util.Date(ts))
}

/** ফোনের ডায়ালারের মতো খোলামেলা সারি: ধরনের আইকন, নাম, নিচে নম্বর, ডানে সময় ও > বাটন */
@Composable
private fun CallGroupRow(
    group: CallGroup,
    nameByTail: Map<String, String>,
    blocked: Boolean,
    onClick: () -> Unit,
    onDetails: () -> Unit
) {
    val latest = group.records.first()
    val title = remember(group.key, nameByTail) {
        nameByTail[group.key] ?: latest.displayName.ifBlank { group.number }
    }
    val isMissed = latest.isIncoming && !latest.isAnswered
    val subtitle = buildString {
        if (title != group.number) append(group.number)
        else if (latest.accountUser.isNotBlank()) append(latest.accountUser)
        if (blocked) { if (isNotEmpty()) append(" • "); append("Blocked") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    isMissed -> Icons.Filled.CallMissed
                    latest.isIncoming -> Icons.Filled.CallReceived
                    else -> Icons.Filled.CallMade
                },
                contentDescription = null,
                tint = if (isMissed) com.noxsip.app.ui.theme.Red500 else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isMissed) com.noxsip.app.ui.theme.Red500 else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (group.records.size > 1) {
                    Text(
                        text = " (${group.records.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = shortTime(latest.timestamp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(themed(Color(0xFFEDEFF3), Color(0xFF262626)))
                .clickable(onClick = onDetails),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Details",
                tint = themed(Color(0xFF333333), Color(0xFFE2E8F0)),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** বামে স্লাইড করলে পেছনের অ্যাকশন বাটন (Delete / Block) বেরিয়ে আসে */
@Composable
internal fun SwipeRow(
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxPx = with(density) { 132.dp.toPx() }
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isOpen) { anim.animateTo(if (isOpen) -maxPx else 0f) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.matchParentSize().padding(end = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(anim.value.toInt(), 0) }
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        scope.launch { anim.snapTo((anim.value + delta).coerceIn(-maxPx, 0f)) }
                    },
                    onDragStopped = {
                        val open = anim.value < -maxPx / 2
                        onOpenChange(open)
                        anim.animateTo(if (open) -maxPx else 0f)
                    }
                )
        ) { content() }
    }
}

@Composable
internal fun RowScope.SwipeAction(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    // ফোনের ডায়ালারের মতো: হালকা ধূসর গোল বাটন, গাঢ় আইকন (color প্যারামিটার আর ব্যবহার হয় না)
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(themed(Color(0xFFE9EBF0), Color(0xFF262626)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = themed(Color(0xFF222222), Color(0xFFE2E8F0)), modifier = Modifier.size(22.dp))
    }
}
