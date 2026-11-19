package com.noxsip.app.ui.screens

import com.noxsip.app.ui.theme.themed
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxsip.app.data.models.CallRecord
import com.noxsip.app.ui.theme.Green500
import com.noxsip.app.ui.theme.Red500
import com.noxsip.app.util.RecordingStorage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val detailAvatarPalette = listOf(
    Color(0xFFEF6C5B), Color(0xFFF472B6), Color(0xFFFBBF24), Color(0xFFA855F7),
    Color(0xFFFB923C), Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFF60A5FA)
)

private fun detailDate(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(ts))
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Today $time"
        sameYear && dayDiff == 1 -> "Yesterday $time"
        else -> SimpleDateFormat("MMM dd, h:mm a", Locale.US).format(Date(ts))
    }
}

private fun durationText(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m > 0 && s > 0 -> "$m min $s sec"
        m > 0 -> "$m min"
        else -> "$s sec"
    }
}

private fun statusText(r: CallRecord): String = when {
    r.isIncoming && !r.isAnswered -> "Missed"
    !r.isIncoming && r.duration <= 0 -> "Not connected"
    r.isIncoming -> "Received ${durationText(r.duration)}"
    else -> "Outgoing ${durationText(r.duration)}"
}

private fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

/**
 * একটি নম্বরের বিস্তারিত পেজ: ওই নম্বরের সব কল, কল/মেসেজ বাটন, রেকর্ডিং প্লে ও অন্যান্য অপশন।
 */
@Composable
fun CallDetailsScreen(
    number: String,
    name: String?,
    records: List<CallRecord>,
    isBlocked: Boolean,
    detectMessengers: suspend (String) -> Pair<Boolean, Boolean>,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onOpenChat: (app: String, number: String) -> Unit,
    onAddToCategory: (category: String) -> Unit,
    onToggleBlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var messengers by remember(number) { mutableStateOf(false to false) }
    LaunchedEffect(number) { messengers = try { detectMessengers(number) } catch (_: Exception) { false to false } }

    // রেকর্ডিং প্লেয়ার
    var player by remember { mutableStateOf<com.noxsip.app.util.RecPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }
    LaunchedEffect(playingId) {
        while (playingId != null) {
            try { player?.let { positionMs = it.positionMs() } } catch (_: Exception) {}
            delay(200)
        }
    }
    fun stopPlayback() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playingId = null
        positionMs = 0
    }
    fun togglePlay(r: CallRecord) {
        val wasThis = playingId == r.id
        stopPlayback()
        if (wasThis) return
        val mp = RecordingStorage.startPlayer(
            ctx, r.recordingPath,
            onCompletion = { stopPlayback() },
            onError = {
                stopPlayback()
                if (!RecordingStorage.openExternally(ctx, r.recordingPath)) {
                    android.widget.Toast.makeText(ctx, "Could not play this recording", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        if (mp != null) {
            player = mp
            durationMs = try { mp.durationMs() } catch (_: Exception) { 0 }
            playingId = r.id
        } else if (!RecordingStorage.openExternally(ctx, r.recordingPath)) {
            android.widget.Toast.makeText(ctx, "Could not play this recording", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val shownName = name?.takeIf { it.isNotBlank() } ?: number
    val avatarColor = detailAvatarPalette[(shownName.hashCode() and 0x7fffffff) % detailAvatarPalette.size]

    Column(modifier = modifier.fillMaxSize().background(themed(Color(0xFFF4F5F8), Color(0xFF000000)))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Add to Client") }, onClick = { menuOpen = false; onAddToCategory("client") })
                    DropdownMenuItem(text = { Text("Add to Office") }, onClick = { menuOpen = false; onAddToCategory("office") })
                    DropdownMenuItem(
                        text = { Text(if (isBlocked) "Remove from block list" else "Add to block list") },
                        onClick = { menuOpen = false; onToggleBlock() }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(88.dp).clip(CircleShape).background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            shownName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                            fontSize = 36.sp, color = Color.White, fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(shownName, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isBlocked) {
                        Text("Blocked", fontSize = 12.sp, color = Red500, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // নম্বর কার্ড
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = themed(Color.White, Color(0xFF0E0E0E)), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(number, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            RoundAction(Icons.Filled.Call, "Call", onCall)
                            Spacer(modifier = Modifier.width(10.dp))
                            RoundAction(Icons.Filled.Sms, "Message", onMessage)
                        }
                        if (messengers.first) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                            ChatRow("Message on WhatsApp", Color(0xFF25D366)) { onOpenChat("whatsapp", number) }
                        }
                        if (messengers.second) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                            ChatRow("Message on Telegram", Color(0xFF229ED9)) { onOpenChat("telegram", number) }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    "Call logs",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }

            item {
                Surface(shape = RoundedCornerShape(16.dp), color = themed(Color.White, Color(0xFF0E0E0E)), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        if (records.isEmpty()) {
                            Text(
                                "No calls",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        records.forEachIndexed { i, r ->
                            val missed = r.isIncoming && !r.isAnswered
                            val hasRec = remember(r.recordingPath) { RecordingStorage.exists(ctx, r.recordingPath) }
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(detailDate(r.timestamp), fontSize = 16.sp, color = if (missed) Red500 else MaterialTheme.colorScheme.onSurface)
                                        if (r.accountUser.isNotBlank()) {
                                            Text(r.accountUser, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(
                                        statusText(r),
                                        fontSize = 13.sp,
                                        color = if (missed) Red500 else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (hasRec) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(themed(Color(0xFFEDEFF3), Color(0xFF262626)))
                                                .clickable { togglePlay(r) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (playingId == r.id) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                contentDescription = "Play recording",
                                                tint = themed(Color(0xFF222222), Color(0xFFE2E8F0)),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                                if (hasRec && playingId == r.id) {
                                    PlaybackBar(
                                        progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                                        onSeek = { f ->
                                            val p = (f * durationMs).toInt()
                                            positionMs = p
                                            try { player?.seekTo(p) } catch (_: Exception) {}
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Recorded ${durationText(r.duration)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(clock(positionMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (i < records.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

/** পাতলা প্লেব্যাক লাইন: ৩dp ট্র্যাক, ছোট গোল থাম্ব; ট্যাপ বা টেনে সিক করা যায় */
@Composable
private fun PlaybackBar(progress: Float, onSeek: (Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var widthPx by remember { mutableIntStateOf(1) }
    val p = progress.coerceIn(0f, 1f)
    val thumbPx = with(density) { 22.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onSeek((o.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // হালকা ধূসর পাতলা ট্র্যাক, তার ওপর ফাঁপা সবুজ রিং থাম্ব (ফোনের প্লেয়ারের মতো)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(themed(Color(0xFFE4E6EA), Color(0xFF333333))))
        Box(
            Modifier
                .offset { IntOffset((p * (widthPx - thumbPx)).toInt(), 0) }
                .size(22.dp)
                .clip(CircleShape)
                .background(themed(Color.White, Color(0xFF0E0E0E)))
                .border(3.dp, Green500, CircleShape)
        )
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(42.dp).clip(CircleShape).background(themed(Color(0xFFEDEFF3), Color(0xFF262626))).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = themed(Color(0xFF222222), Color(0xFFE2E8F0)), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ChatRow(label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Sms, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(label, fontSize = 15.sp)
    }
}
