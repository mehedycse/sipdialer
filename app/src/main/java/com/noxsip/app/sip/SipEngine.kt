package com.noxsip.app.sip

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.noxsip.app.SipDialerApplication
import com.noxsip.app.data.AppDatabase
import com.noxsip.app.data.models.CallRecord
import com.noxsip.app.data.models.SipAccountData
import com.noxsip.app.telecom.NativeDialerOverlay
import com.noxsip.app.telecom.TelecomHelper
import com.noxsip.app.service.SipForegroundService
import com.noxsip.app.util.CallNetworkLock
import com.noxsip.app.util.CallRinger
import com.noxsip.app.util.RecordingStorage
import com.noxsip.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.noxsip.app.data.AccountRepository
import org.linphone.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SIP ইঞ্জিন - LinPhone SDK ব্যবহার করে SIP কল ম্যানেজ করে
 */
class SipEngine(private val context: Context) {

    companion object {
        private const val TAG = "SipEngine"
        private const val REGISTRATION_TIMEOUT_MS = 15_000L
        private const val MAX_FAILURES = 2

        @Volatile
        private var INSTANCE: SipEngine? = null

        fun getInstance(context: Context): SipEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SipEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val core: Core get() = SipDialerApplication.core

    /** Balanced মোডের Alarm থেকে ডাকা: ঘুম থেকে জেগে রেজিস্ট্রেশন/NAT তাজা করে (কল চলাকালীন কিছু করে না) */
    fun keepAliveTick() {
        mainHandler.post {
            try {
                if (core.callsNb == 0 && core.accountList.isNotEmpty()) core.refreshRegisters()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * নেটওয়ার্ক বদলালে (Wi-Fi / মোবাইল ডেটা / VPN চালু-বন্ধ) পুরনো সকেট মরে যায়।
     * কল না থাকলে নেটওয়ার্ককে একটু "বন্ধ" করে আবার "চালু" ধরে সকেট নতুন করে বাঁধি এবং রি-রেজিস্টার করি।
     */
    fun networkChanged() {
        mainHandler.post {
            try {
                if (core.accountList.isEmpty() || core.callsNb > 0) return@post
                core.setNetworkReachable(false)
                mainHandler.postDelayed({
                    try {
                        core.setNetworkReachable(true)
                        core.refreshRegisters()
                    } catch (_: Exception) {
                    }
                }, 800)
            } catch (_: Exception) {
            }
        }
    }

    /** নির্বাচিত (সবুজ টিক) অ্যাকাউন্টের অবস্থা; পুরনো UI এটাই দেখে */
    private val _registrationState = MutableStateFlow(SipRegistrationState.UNREGISTERED)
    val registrationState: StateFlow<SipRegistrationState> = _registrationState.asStateFlow()

    private val _registrationError = MutableStateFlow<String?>(null)
    val registrationError: StateFlow<String?> = _registrationError.asStateFlow()

    /** প্রতিটি অ্যাকাউন্টের নিজস্ব অবস্থা ও এরর (অ্যাকাউন্ট id অনুযায়ী) */
    private val _accountStates = MutableStateFlow<Map<String, SipRegistrationState>>(emptyMap())
    val accountStates: StateFlow<Map<String, SipRegistrationState>> = _accountStates.asStateFlow()

    private val _accountErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val accountErrors: StateFlow<Map<String, String>> = _accountErrors.asStateFlow()

    /** চলতি কলটি কোন অ্যাকাউন্টের (একাধিক অ্যাকাউন্ট থাকলেই শুধু নাম থাকে) */
    private val _callAccountUser = MutableStateFlow<String?>(null)
    val callAccountUser: StateFlow<String?> = _callAccountUser.asStateFlow()

    private val _callState = MutableStateFlow<SipCallState>(SipCallState.Idle)
    val callState: StateFlow<SipCallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _callQuality = MutableStateFlow<CallQualityStats?>(null)
    val callQuality: StateFlow<CallQualityStats?> = _callQuality.asStateFlow()

    var onIncomingCall: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** চলমান কলের কোডেক, RTT, জিটার, প্যাকেট লস প্রতি ২ সেকেন্ডে পড়ে */
    private val qualityPoller = object : Runnable {
        override fun run() {
            try {
                val call = core.currentCall
                if (call == null || (call.state != Call.State.StreamsRunning && call.state != Call.State.Connected)) {
                    _callQuality.value = null
                    return
                }
                val pt = call.currentParams.usedAudioPayloadType
                val codec = pt?.let {
                    val khz = if (it.mimeType.equals("G722", ignoreCase = true)) 16 else it.clockRate / 1000
                    "${it.mimeType.uppercase()} ${khz}k"
                }.orEmpty()
                // RTP জিটার RTP-টাইমস্ট্যাম্প ইউনিটে আসে, তাই কোডেকের clock rate দিয়ে ms-এ রূপান্তর
                val clockKhz = (pt?.clockRate ?: 8000).coerceAtLeast(8000) / 1000f
                val stats = call.audioStats
                if (stats != null) {
                    _callQuality.value = CallQualityStats(
                        codec = codec,
                        rttMs = (stats.roundTripDelay * 1000f).toInt().coerceAtLeast(0),
                        jitterMs = (stats.receiverInterarrivalJitter / clockKhz).toInt().coerceAtLeast(0),
                        lossPercent = maxOf(stats.localLossRate, stats.receiverLossRate).coerceAtLeast(0f),
                        downKbps = stats.downloadBandwidth.toInt(),
                        upKbps = stats.uploadBandwidth.toInt(),
                        rating = call.currentQuality,
                        micDb = call.recordVolume,
                        inputDevice = core.inputAudioDevice?.deviceName.orEmpty(),
                        micEnabled = core.isMicEnabled
                    )
                }
                mainHandler.postDelayed(this, 1000L)
            } catch (e: Exception) {
                Log.w(TAG, "Quality poll failed: ${e.message}")
            }
        }
    }

    private fun startQualityPolling() {
        mainHandler.removeCallbacks(qualityPoller)
        mainHandler.postDelayed(qualityPoller, 1000L)
    }

    private fun stopQualityPolling() {
        mainHandler.removeCallbacks(qualityPoller)
        _callQuality.value = null
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** নির্বাচিত অ্যাকাউন্ট: আউটগোয়িং কল এটা দিয়েই যায় */
    private var currentAccount: SipAccountData? = null

    // ---- প্রতি অ্যাকাউন্টের রেজিস্ট্রেশন তথ্য (সব অ্যাকাউন্ট একসাথে রেজিস্টার থাকে) ----
    private val registeredData = HashMap<String, SipAccountData>()
    private val authInfos = HashMap<String, MutableList<AuthInfo>>()
    private val failureCounts = HashMap<String, Int>()
    private val authChallenges = HashMap<String, Int>()
    private val stoppedByError = HashSet<String>()
    private val regTimeouts = HashMap<String, Runnable>()

    private fun cleanUser(a: SipAccountData) = a.username.trim().substringBefore("@")
    private fun cleanDomain(a: SipAccountData) = parseHostAndPort(a.domain, a.port).first

    private fun setAccState(id: String, st: SipRegistrationState, error: String? = null, clearError: Boolean = false) {
        _accountStates.value = _accountStates.value + (id to st)
        if (error != null) _accountErrors.value = _accountErrors.value + (id to error)
        else if (clearError || st == SipRegistrationState.REGISTERED || st == SipRegistrationState.REGISTERING) {
            // কানেক্টেড/কানেক্ট হচ্ছে অবস্থায় পুরনো এরর থাকবে না
            _accountErrors.value = _accountErrors.value - id
        }
        publishSelected()
        try {
            if (st == SipRegistrationState.ERROR && error != null) {
                val now = System.currentTimeMillis()
                if (now - (lastProblemNotifyAt[id] ?: 0L) > 10 * 60 * 1000L) {
                    lastProblemNotifyAt[id] = now
                    val name = registeredData[id]?.let { it.name.ifBlank { it.username } } ?: "SIP account"
                    NotificationHelper.showAccountProblem(context, id, name, error)
                }
            } else if (st == SipRegistrationState.REGISTERED) {
                lastProblemNotifyAt.remove(id)
                NotificationHelper.clearAccountProblem(context, id)
            }
        } catch (_: Exception) {
        }
    }

    private val lastProblemNotifyAt = HashMap<String, Long>()

    private fun publishSelected() {
        val id = currentAccount?.id
        _registrationState.value = if (id == null) SipRegistrationState.UNREGISTERED
        else _accountStates.value[id] ?: SipRegistrationState.UNREGISTERED
        _registrationError.value = id?.let { _accountErrors.value[it] }
    }

    private var activeCallConnected = false
    private var activeCallRecorded = false
    private var activeRecordingPath: String = ""

    /** এই কলে এখন রেকর্ড হচ্ছে কি না (কল স্ক্রিনের Rec বাটন এটা দেখায়) */
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** ব্যবহারকারী এই কলে রেকর্ডিং নিজে বন্ধ করলে true; পরের StreamsRunning ইভেন্টে অটো-চালু হবে না */
    @Volatile private var recordingUserOff = false

    /** কল চলাকালীন রেকর্ডিং চালু/বন্ধ (সেটিংসে অটো-রেকর্ড বন্ধ থাকলেও এই কলে চালু করা যায়) */
    fun toggleRecording() {
        mainHandler.post {
            try {
                val call = core.currentCall ?: return@post
                if (_isRecording.value) {
                    call.stopRecording()
                    recordingUserOff = true
                    _isRecording.value = false
                } else {
                    if (activeRecordingPath.isEmpty()) {
                        val remote = try { call.remoteAddress.asStringUriOnly() } catch (_: Exception) { "call" }
                        activeRecordingPath = prepareRecordingPath(remote, force = true)
                    }
                    if (activeRecordingPath.isNotEmpty()) {
                        try {
                            val p = call.params
                            if (p.recordFile.isNullOrEmpty()) {
                                p.recordFile = activeRecordingPath
                                call.params = p
                            }
                        } catch (_: Exception) {
                        }
                        call.startRecording()
                        recordingUserOff = false
                        _isRecording.value = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleRecording failed: ${e.message}")
            }
        }
    }

    // ---- ইনকামিং কল "কলার কেটে দিয়েছে" শনাক্তকরণ (CANCEL না পাঠানো গেটওয়ের জন্য) ----
    // কিছু PSTN গেটওয়ে (যেমন SemuxGW) কলার কাটলে CANCEL পাঠায় না; শুধু প্রতি ~৩ সেকেন্ডে INVITE
    // পুনঃপ্রেরণা বন্ধ করে দেয়। তাই পুনঃপ্রেরণা থেমে গেলে ধরে নিই কলার কেটে দিয়েছে।
    @Volatile private var inviteArrivals = 0
    @Volatile private var lastInviteAtMs = 0L
    @Volatile private var lastInviteGapMs = 0L
    // CPU ঘুমিয়ে থাকলে জমে থাকা INVITE কপি একসাথে (কয়েক মিলিসেকেন্ডে) আসে; তখন কলটা পুরনো হওয়ার সম্ভাবনা বেশি
    @Volatile private var inviteBacklogBurst = false

    private fun onInviteArrived() {
        val now = SystemClock.elapsedRealtime()
        if (inviteArrivals > 0) {
            lastInviteGapMs = now - lastInviteAtMs
            if (inviteArrivals == 1 && lastInviteGapMs < 800L) inviteBacklogBurst = true
        }
        lastInviteAtMs = now
        inviteArrivals++
    }

    private fun resetInviteTracking() {
        inviteArrivals = 0
        lastInviteAtMs = 0L
        lastInviteGapMs = 0L
        inviteBacklogBurst = false
    }

    private val sipLogListener = object : LoggingServiceListener {
        override fun onLogMessageWritten(service: LoggingService, domain: String, level: LogLevel, message: String) {
            try {
                // সার্ভার থেকে আসা INVITE (নতুন বা পুনঃপ্রেরিত); আমাদের পাঠানো বার্তা বাদ
                val isInboundInvite = (message.contains("new bytes from") && message.contains("INVITE sip:")) ||
                        message.startsWith("INVITE sip:")
                if (isInboundInvite && _callState.value is SipCallState.Incoming) {
                    onInviteArrived()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** শুধুমাত্র যখন সার্ভার INVITE পুনঃপ্রেরণ করছিল (>=২ বার দেখা গেছে) এবং তা থেমে গেছে */
    private fun inviteRetransmitsStopped(): Boolean {
        if (inviteArrivals < 2 || lastInviteGapMs < 0L) return false
        // জমে থাকা (backlog) INVITE হলে গেটওয়ের ~৩ সেকেন্ড পুনঃপ্রেরণা-ব্যবধান ধরে ৫ সেকেন্ডেই সিদ্ধান্ত নিই
        val threshold = if (inviteBacklogBurst) 5000L else maxOf(6000L, (lastInviteGapMs * 22) / 10)
        return SystemClock.elapsedRealtime() - lastInviteAtMs > threshold
    }

    private val ringingWatchdog = object : Runnable {
        override fun run() {
            try {
                core.iterate()
                val current = _callState.value
                if (current is SipCallState.Incoming) {
                    if (inviteRetransmitsStopped()) {
                        Log.i(TAG, "RingingWatchdog: INVITE retransmits stopped -> caller hung up, declining call")
                        val ringingCall = core.calls.firstOrNull {
                            it.state == Call.State.IncomingReceived || it.state == Call.State.IncomingEarlyMedia
                        }
                        resetInviteTracking()
                        try {
                            ringingCall?.decline(Reason.NotAnswered)
                        } catch (_: Exception) {
                            try { ringingCall?.terminate() } catch (_: Exception) {}
                        }
                        // Linphone-এর End ইভেন্ট এসে রিংটোন/ওভারলে বন্ধ করবে; দেরি হলে পরের লুপও সামলাবে
                    }
                    val stillRinging = core.calls.any {
                        it.state == Call.State.IncomingReceived ||
                                it.state == Call.State.IncomingEarlyMedia
                    }
                    if (!stillRinging) {
                        Log.i(TAG, "RingingWatchdog: caller hung up, stopping ringer and dismissing popup")
                        CallRinger.stop()
                        try {
                            core.stopRinging()
                        } catch (_: Exception) {
                        }
                        NotificationHelper.cancelIncomingCallNotification(context)
                        NativeDialerOverlay.dismiss()
                        _callState.value = SipCallState.Ended(current.remoteAddress, "Caller hung up")
                        return
                    }
                    mainHandler.postDelayed(this, 200L)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun prepareRecordingPath(remote: String, force: Boolean = false): String {
        if (!force && !TelecomHelper.isAutoRecordEnabled(context)) return ""
        return try {
            val baseDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "CallRecordings")
            if (!baseDir.exists()) baseDir.mkdirs()
            val cleanNum = remote.replace("sip:", "")
                .substringBefore("@")
                .filter { it.isLetterOrDigit() || it == '+' }
                .ifEmpty { "call" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(baseDir, "SIP_${cleanNum}_$stamp.wav").absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare recording path", e)
            ""
        }
    }

    private fun makeRegistrationTimeout(id: String): Runnable = Runnable {
        if (_accountStates.value[id] == SipRegistrationState.REGISTERING) {
            val data = registeredData[id]
            val transport = data?.transport ?: "UDP"
            val port = data?.port ?: 5060
            stopWithError(
                id,
                "No response from SIP server ($transport port $port). " +
                        "Please check the Domain/IP, Port, and internet connection."
            )
        }
    }

    private fun stopWithError(id: String, message: String) {
        stoppedByError.add(id)
        regTimeouts.remove(id)?.let { mainHandler.removeCallbacks(it) }
        try {
            core.getAccountByIdkey(id)?.let { acc ->
                val params = acc.params.clone()
                params.isRegisterEnabled = false
                acc.params = params
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop registration retries", e)
        }
        setAccState(id, SipRegistrationState.ERROR, error = message)
    }

    /** Linphone অ্যাকাউন্ট ও তার AuthInfo সরায় (অন্য অ্যাকাউন্ট অক্ষত থাকে) */
    private fun removeLinphoneAccount(id: String) {
        try {
            core.getAccountByIdkey(id)?.let { acc ->
                val params = acc.params.clone()
                params.isRegisterEnabled = false
                acc.params = params
                core.removeAccount(acc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeLinphoneAccount failed: ${e.message}")
        }
        authInfos.remove(id)?.forEach {
            try { core.removeAuthInfo(it) } catch (_: Exception) {}
        }
    }

    private fun describeFailure(errorInfo: ErrorInfo?, reason: Reason?, message: String): Pair<String, Boolean> {
        val code = errorInfo?.protocolCode ?: 0
        val phrase = errorInfo?.phrase?.takeIf { it.isNotBlank() } ?: message
        val lower = phrase.lowercase()

        return when {
            code == 401 || code == 407 || reason == Reason.Unauthorized ->
                "Invalid username or password (Code $code: $phrase)." to true
            code == 403 || reason == Reason.Forbidden ->
                "Access denied by server (403 Forbidden: $phrase). Check your password or IP whitelist." to true
            code == 404 || reason == Reason.NotFound ->
                "Account/extension not found on server (404 Not Found)." to true
            lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls") ->
                "TLS connection failed ($phrase). Try switching Transport to UDP and Port to 5060." to true
            code == 408 || code == 503 || reason == Reason.NoResponse ||
                    reason == Reason.ServerTimeout || reason == Reason.IOError ->
                "Cannot reach server (${if (code > 0) "Code $code" else phrase}). Check Domain/IP, Port, and Transport (UDP/TCP)." to false
            code > 0 -> "Registration failed (Code $code: $phrase)" to false
            else -> "Registration failed: ${phrase.ifBlank { "Unknown error" }}" to false
        }
    }

    /**
     * ডোমেইন/হোস্ট ইনপুট থেকে "sip:", স্পেস এবং ":port" আলাদা করার হেল্পার
     */
    private fun parseHostAndPort(rawInput: String, defaultPort: Int): Pair<String, Int> {
        val cleaned = rawInput.trim()
            .removePrefix("sip:")
            .removePrefix("sips:")
            .substringBefore(";")
            .substringBefore("/")
            .trim()

        if (cleaned.contains(":")) {
            val hostPart = cleaned.substringBefore(":").trim()
            val portPart = cleaned.substringAfter(":").trim().toIntOrNull()
            if (hostPart.isNotEmpty() && portPart != null && portPart in 1..65535) {
                return hostPart to portPart
            }
        }
        return cleaned to defaultPort
    }

    private val coreListener = object : CoreListenerStub() {

        // ব্লুটুথ/হেডসেট যুক্ত বা খুলে গেলে রুট আবার ঠিক করি
        override fun onAudioDevicesListUpdated(core: Core) {
            mainHandler.post { applyAudioRoute() }
        }

        override fun onAuthenticationRequested(
            core: Core,
            authInfo: AuthInfo,
            method: AuthMethod
        ) {
            // যে অ্যাকাউন্টের জন্য চ্যালেঞ্জ এসেছে সেটি খুঁজি (ইউজারনেম + ডোমেইন)
            val challengeUser = authInfo.username.orEmpty()
            val challengeDomain = authInfo.domain.orEmpty()
            val account = registeredData.values.firstOrNull {
                cleanUser(it) == challengeUser &&
                        (challengeDomain.isEmpty() || cleanDomain(it).equals(challengeDomain, true))
            } ?: registeredData.values.firstOrNull { cleanUser(it) == challengeUser }
            // ডিসকানেক্ট/আনরেজিস্টার চলাকালীন সার্ভারের চ্যালেঞ্জ উপেক্ষা করি; নইলে ভুল "Invalid username or password" এরর আসে
            if (account == null) {
                Log.d(TAG, "Auth challenge ignored: no matching active account (unregistering)")
                return
            }
            val id = account.id
            val count = (authChallenges[id] ?: 0) + 1
            authChallenges[id] = count
            Log.w(
                TAG,
                "onAuthenticationRequested #$count: realm=${authInfo.realm}, username=${authInfo.username}, method=$method"
            )

            // ১ম বার চ্যালেঞ্জে সার্ভারের নির্দিষ্ট realm (যেমন "asterisk") অনুযায়ী পাসওয়ার্ড দিন
            if (count <= 1 && account.password.isNotEmpty()) {
                val cleanDom = cleanDomain(account)
                val cleanUsr = cleanUser(account)
                val realmAuth = Factory.instance().createAuthInfo(
                    authInfo.username?.ifEmpty { cleanUsr } ?: cleanUsr,
                    cleanUsr,
                    account.password,
                    null,
                    authInfo.realm,
                    authInfo.domain?.ifEmpty { cleanDom } ?: cleanDom
                )
                core.addAuthInfo(realmAuth)
                authInfos.getOrPut(id) { mutableListOf() }.add(realmAuth)
                core.refreshRegisters()
                return
            }

            // Second identical auth challenge means credentials were rejected
            mainHandler.post {
                stopWithError(id, "Invalid username or password (401 Unauthorized). Please verify your credentials.")
            }
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            val id = try { account.params.idkey } catch (_: Exception) { null }
            Log.d(TAG, "AccountRegistrationState[$id]: $state - $message")
            if (id.isNullOrEmpty()) return
            handleRegistrationState(id, state, account.errorInfo, account.error, message)
        }

        private fun handleRegistrationState(
            id: String,
            state: RegistrationState,
            errorInfo: ErrorInfo?,
            reason: Reason?,
            message: String
        ) {
            when (state) {
                RegistrationState.Ok, RegistrationState.Refreshing -> {
                    regTimeouts.remove(id)?.let { mainHandler.removeCallbacks(it) }
                    failureCounts[id] = 0
                    authChallenges[id] = 0
                    stoppedByError.remove(id)
                    setAccState(id, SipRegistrationState.REGISTERED, clearError = true)
                }
                RegistrationState.Progress -> {
                    if (id !in stoppedByError && registeredData.containsKey(id)) {
                        setAccState(id, SipRegistrationState.REGISTERING)
                    }
                }
                RegistrationState.Failed -> {
                    if (!registeredData.containsKey(id)) return // ডিসকানেক্ট করা অবস্থায় ব্যর্থতা এরর নয়
                    val n = (failureCounts[id] ?: 0) + 1
                    failureCounts[id] = n
                    val (msg, fatal) = describeFailure(errorInfo, reason, message)
                    Log.w(TAG, "Registration failed [$id] (#$n, fatal=$fatal): $msg")
                    if (fatal || n >= MAX_FAILURES) {
                        mainHandler.post { stopWithError(id, msg) }
                    } else {
                        // সাময়িক ব্যর্থতা: আর "Connected" নয়, পুনঃচেষ্টা চলছে বলে দেখাই (ঠিক হলে Ok এসে এরর মুছবে)
                        _accountErrors.value = _accountErrors.value + (id to msg)
                        _accountStates.value = _accountStates.value + (id to SipRegistrationState.REGISTERING)
                        publishSelected()
                    }
                }
                RegistrationState.Cleared, RegistrationState.None -> {
                    if (id !in stoppedByError && registeredData.containsKey(id)) {
                        setAccState(id, SipRegistrationState.UNREGISTERED)
                    }
                }
            }
        }

        /** বেটা: ইনকামিং SIP কল অন্য নম্বরে redirect (302)। ব্যর্থ হলে ডিক্লাইন */
        private fun forwardSipCall(call: Call, number: String) {
            try {
                val domain = call.toAddress?.domain
                val target = if (number.contains("@")) "sip:$number" else "sip:$number@$domain"
                Log.i(TAG, "Forwarding incoming call")
                val targetAddr = Factory.instance().createAddress(target)
                if (targetAddr == null) {
                    call.decline(Reason.Declined)
                } else {
                    val rc = call.redirectTo(targetAddr)
                    Log.i(TAG, "redirectTo result=$rc")
                }
            } catch (e: Exception) {
                Log.w(TAG, "forward failed", e)
                try { call.decline(Reason.Declined) } catch (_: Exception) {}
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Log.d(TAG, "Call state: $state - $message")
            val remoteAddress = call.remoteAddress.asStringUriOnly()

            when (state) {
                Call.State.IncomingReceived -> {
                    // Ignore duplicate incoming calls if already in a call
                    if (core.callsNb > 1) {
                        // বেটা: ব্যস্ত অবস্থায় ফরোয়ার্ড চালু থাকলে অন্য নম্বরে পাঠাই
                        val fwBusy = com.noxsip.app.beta.CallForward.activeNumber(context)
                        val fwMode = com.noxsip.app.beta.CallForward.mode(context)
                        if (fwBusy != null && (fwMode == com.noxsip.app.beta.CallForward.BUSY ||
                                fwMode == com.noxsip.app.beta.CallForward.ALL)
                        ) {
                            forwardSipCall(call, fwBusy)
                            return
                        }
                        try {
                            call.decline(Reason.Busy)
                        } catch (_: Exception) {
                        }
                        return
                    }
                    activeCallConnected = false
                    activeCallRecorded = false
                    // ব্লক করা নম্বর: রিং না করে রিজেক্ট; End-এ মিসড কল হিসেবে তালিকায় সংরক্ষিত হয়
                    if (com.noxsip.app.util.BlockList.isBlocked(context, remoteAddress)) {
                        activeRecordingPath = ""
                        _callAccountUser.value = labelForCall(call)
                        try {
                            call.decline(Reason.Declined)
                        } catch (_: Exception) {
                        }
                        return
                    }
                    // বেটা: সব কল ফরোয়ার্ড চালু থাকলে রিং না করে সরাসরি অন্য নম্বরে
                    val fwNum = com.noxsip.app.beta.CallForward.activeNumber(context)
                    val fwType = com.noxsip.app.beta.CallForward.mode(context)
                    if (fwNum != null && fwType == com.noxsip.app.beta.CallForward.ALL) {
                        forwardSipCall(call, fwNum)
                        return
                    }
                    activeRecordingPath = prepareRecordingPath(remoteAddress)
                    try {
                        call.notifyRinging()
                    } catch (_: Exception) {
                    }
                    resetInviteTracking()
                    onInviteArrived() // প্রথম INVITE এইমাত্র এসেছে
                    _callAccountUser.value = labelForCall(call)
                    _callState.value = SipCallState.Incoming(remoteAddress, call)
                    CallRinger.start(context)
                    TelecomHelper.reportIncomingCall(context, remoteAddress)
                    mainHandler.removeCallbacks(ringingWatchdog)
                    mainHandler.postDelayed(ringingWatchdog, 200L)
                    // বেটা: না ধরলে নির্দিষ্ট সেকেন্ড পর ফরোয়ার্ড
                    if (fwNum != null && fwType == com.noxsip.app.beta.CallForward.NO_ANSWER) {
                        mainHandler.postDelayed({
                            val still = call.state == Call.State.IncomingReceived ||
                                call.state == Call.State.IncomingEarlyMedia
                            if (still) {
                                CallRinger.stop()
                                forwardSipCall(call, fwNum)
                            }
                        }, com.noxsip.app.beta.CallForward.seconds(context) * 1000L)
                    }
                }
                Call.State.IncomingEarlyMedia -> {
                    _callState.value = SipCallState.Incoming(remoteAddress, call)
                }
                Call.State.OutgoingInit -> {
                    mainHandler.removeCallbacks(ringingWatchdog)
                    activeCallConnected = false
                    activeCallRecorded = false
                    CallRinger.stop()
                    CallNetworkLock.acquire(context)
                    SipForegroundService.setCallActive(context, true)
                    _callAccountUser.value = labelForCall(call)
                    _callState.value = SipCallState.Outgoing(remoteAddress, call)
                }
                Call.State.OutgoingProgress,
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    _callState.value = SipCallState.Outgoing(remoteAddress, call)
                    applyAudioRoute()
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    mainHandler.removeCallbacks(ringingWatchdog)
                    applyAudioRoute()
                    // ব্লুটুথ লাইন (SCO) তৈরি হতে সময় লাগে; আউটগোয়িং কলে প্রথমবারের সেট কাজ করে না, তাই একটু পরে আবার সেট
                    mainHandler.postDelayed({ if (core.currentCall != null) forceBluetoothSwitch() }, 400)
                    mainHandler.postDelayed({ if (core.currentCall != null) forceBluetoothSwitch() }, 1500)
                    mainHandler.postDelayed({ if (core.currentCall != null) applyAudioRoute() }, 3000)
                    activeCallConnected = true
                    CallNetworkLock.acquire(context)
                    SipForegroundService.setCallActive(context, true)
                    startQualityPolling()
                    CallRinger.stop()
                    try {
                        core.stopRinging()
                    } catch (_: Exception) {
                    }
                    NotificationHelper.cancelIncomingCallNotification(context)
                    NativeDialerOverlay.dismissIncomingIfVisible()
                    try {
                        core.isMicEnabled = !_isMuted.value
                        if (!recordingUserOff && (TelecomHelper.isAutoRecordEnabled(context) || _isRecording.value)) {
                            if (activeRecordingPath.isEmpty()) {
                                activeRecordingPath = prepareRecordingPath(remoteAddress)
                            }
                            if (activeRecordingPath.isNotEmpty()) {
                                try {
                                    val p = call.params
                                    if (p.recordFile.isNullOrEmpty()) {
                                        p.recordFile = activeRecordingPath
                                        call.params = p
                                    }
                                } catch (_: Exception) {
                                }
                                call.startRecording()
                                _isRecording.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Silent auto-record start note: ${e.message}")
                    }
                    _callState.value = SipCallState.Connected(remoteAddress, call)
                }
                Call.State.Paused, Call.State.PausedByRemote -> {
                    _callState.value = SipCallState.OnHold(remoteAddress, call)
                }
                Call.State.End, Call.State.Error, Call.State.Released -> {
                    mainHandler.removeCallbacks(ringingWatchdog)
                    stopQualityPolling()
                    resetInviteTracking()
                    if (core.callsNb <= 1) {
                        CallNetworkLock.release()
                        SipForegroundService.setCallActive(context, false)
                    }
                    CallRinger.stop()
                    try {
                        core.stopRinging()
                    } catch (_: Exception) {
                    }
                    NotificationHelper.cancelIncomingCallNotification(context)
                    NotificationHelper.cancelOngoingCallNotification(context)
                    NativeDialerOverlay.dismiss()
                    try {
                        call.stopRecording()
                    } catch (_: Exception) {
                    }
                    _isRecording.value = false
                    recordingUserOff = false
                    if (!activeCallRecorded) {
                        activeCallRecorded = true
                        val durationSec = try {
                            call.duration.coerceAtLeast(0)
                        } catch (_: Exception) {
                            0
                        }
                        val isIncoming = try {
                            call.dir == Call.Dir.Incoming
                        } catch (_: Exception) {
                            false
                        }
                        val wasAnswered = activeCallConnected || durationSec > 0
                        val displayName = try {
                            call.remoteAddress?.displayName.orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        val accountUserForRecord = _callAccountUser.value.orEmpty()
                        val candidateRecPath = activeRecordingPath
                        activeRecordingPath = ""
                        ioScope.launch {
                            try {
                                val validRecPath = if (wasAnswered && candidateRecPath.isNotEmpty()) {
                                    val f = File(candidateRecPath)
                                    if (f.exists() && f.length() > 44L) {
                                        // ফোনের শেয়ার্ড স্টোরেজে (Recordings/SipDialer) সরান; ব্যর্থ হলে অ্যাপের ভেতরের কপি রাখুন
                                        RecordingStorage.exportToPublicStorage(context, f) ?: candidateRecPath
                                    } else {
                                        ""
                                    }
                                } else {
                                    if (candidateRecPath.isNotEmpty()) {
                                        try { File(candidateRecPath).delete() } catch (_: Exception) {}
                                    }
                                    ""
                                }
                                AppDatabase.getInstance(context).callHistoryDao().insertCallRecord(
                                    CallRecord(
                                        remoteAddress = remoteAddress,
                                        displayName = displayName,
                                        isIncoming = isIncoming,
                                        isAnswered = wasAnswered,
                                        duration = if (wasAnswered) durationSec else 0,
                                        recordingPath = validRecPath,
                                        accountUser = accountUserForRecord
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save call record", e)
                            }
                        }
                    }
                    // কল কেন ব্যর্থ হলো (সার্ভারের কোড, যেমন 403/404/503) জানাতে
                    @Suppress("SENSELESS_COMPARISON")
                    val failure = try {
                        val ei = call.errorInfo
                        if (state == Call.State.Error || (ei != null && ei.protocolCode >= 300)) {
                            val code = ei?.protocolCode ?: 0
                            val phrase = ei?.phrase.orEmpty().ifBlank { call.reason.toString() }
                            if (code > 0) "Call failed: $code $phrase" else "Call failed: ${message.ifBlank { phrase }}"
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                    _callState.value = SipCallState.Ended(remoteAddress, failure ?: message)
                    _isMuted.value = false
                    _isSpeaker.value = false
                    speakerWanted = false
                }
                else -> {}
            }
        }
    }

    init {
        core.addListener(coreListener)
        com.noxsip.app.telecom.CallBarOverlay.attach(context, this)
        try {
            Factory.instance().loggingService.addListener(sipLogListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach SIP log listener: ${e.message}")
        }
    }

    /** কলটি যে অ্যাকাউন্টের (ইনকামিং: যেটায় এসেছে; আউটগোয়িং: যেটা দিয়ে যাচ্ছে) তার ইউজারনেম */
    private fun labelForCall(call: Call): String? {
        return try {
            val local = call.callLog?.localAddress ?: call.toAddress
            val user = local?.username.orEmpty()
            val dom = local?.domain.orEmpty()
            val acc = registeredData.values.firstOrNull { cleanUser(it) == user && cleanDomain(it).equals(dom, true) }
                ?: registeredData.values.firstOrNull { cleanUser(it) == user }
                ?: currentAccount
            acc?.let { cleanUser(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * সংরক্ষিত সব অ্যাকাউন্ট একসাথে রেজিস্টার করে; নির্বাচিতটি সবশেষে (আউটগোয়িংয়ের ডিফল্ট)
     */
    suspend fun registerAllSaved() {
        val repo = AccountRepository(context)
        val all = repo.getAccounts().first()
        val selected = repo.getAccount().first()
        withContext(Dispatchers.Main) {
            all.filter { it.enabled && it.id != selected?.id }.forEach { register(it, select = false) }
            selected?.takeIf { it.enabled }?.let { register(it, select = true) }
        }
    }

    /**
     * একটি SIP অ্যাকাউন্ট রেজিস্টার করুন। অন্য অ্যাকাউন্টের রেজিস্ট্রেশন অক্ষত থাকে।
     * select = true হলে এটি নির্বাচিত (আউটগোয়িং কলের) অ্যাকাউন্ট হয়।
     */
    fun register(account: SipAccountData, select: Boolean = true) {
        val id = account.id
        if (select) currentAccount = account
        val st = _accountStates.value[id]
        if (registeredData[id] == account &&
            (st == SipRegistrationState.REGISTERED || st == SipRegistrationState.REGISTERING)
        ) {
            Log.d(TAG, "Account $id already registering/registered, skipping")
            if (select) {
                try { core.getAccountByIdkey(id)?.let { core.defaultAccount = it } } catch (_: Exception) {}
            }
            publishSelected()
            return
        }

        // শুধু এই অ্যাকাউন্টের আগের অবস্থা সাফ
        regTimeouts.remove(id)?.let { mainHandler.removeCallbacks(it) }
        failureCounts[id] = 0
        authChallenges[id] = 0
        stoppedByError.remove(id)
        _accountErrors.value = _accountErrors.value - id
        registeredData.remove(id)
        removeLinphoneAccount(id)
        publishSelected()

        try {
            if (core.globalState != GlobalState.On) {
                core.start()
            }

            // Standard SIP server + NAT traversal settings for reliable Incoming & Outgoing calls
            val verifyTls = com.noxsip.app.util.SecurityPrefs.verifyTls(SipDialerApplication.instance)
            core.verifyServerCertificates(verifyTls)
            core.verifyServerCn(verifyTls)
            core.mediaEncryption = if (com.noxsip.app.util.SecurityPrefs.srtp(SipDialerApplication.instance)) {
                MediaEncryption.SRTP
            } else {
                MediaEncryption.None
            }
            core.isMediaEncryptionMandatory = false
            core.isIpv6Enabled = false
            core.isKeepAliveEnabled = true
            core.guessHostname = true
            core.isPushNotificationEnabled = false
            core.isAutoSendRingingEnabled = true
            core.incTimeout = 35

            try {
                core.config.setInt("sip", "keepalive_period", 15000)
                core.config.setInt("sip", "use_rport", 1)
                core.config.setInt("sip", "contact_autorewrite", 1)
                core.config.setInt("sip", "guess_hostname", 1)
                if (core.callsNb == 0) SipDialerApplication.configureVoiceAudio(core)
            } catch (_: Exception) {
            }

            // Use random local port (-1) so NAT mapping is fresh; only when no other account is active
            // (changing transports would drop the other accounts' connections)
            if (core.accountList.isEmpty()) {
                core.transports = core.transports.apply {
                    udpPort = -1
                    tcpPort = -1
                    tlsPort = -1
                }
            }

            val cleanUsername = cleanUser(account)
            val (cleanDomain, extractedDomainPort) = parseHostAndPort(account.domain, account.port)
            val (cleanServerHost, effectivePort) = if (account.proxy.isNotBlank()) {
                parseHostAndPort(account.proxy, extractedDomainPort)
            } else {
                cleanDomain to extractedDomainPort
            }

            if (cleanUsername.isEmpty() || cleanDomain.isEmpty()) {
                setAccState(id, SipRegistrationState.ERROR, error = "Please enter a valid username and domain.")
                return
            }

            // 1. Domain-specific AuthInfo
            val domainAuth = Factory.instance().createAuthInfo(
                cleanUsername,
                cleanUsername,
                account.password,
                null,
                null,
                cleanDomain
            )
            core.addAuthInfo(domainAuth)

            // 2. Wildcard AuthInfo for any realm (e.g., "asterisk")
            val wildcardAuth = Factory.instance().createAuthInfo(
                cleanUsername,
                cleanUsername,
                account.password,
                null,
                null,
                null
            )
            core.addAuthInfo(wildcardAuth)
            authInfos[id] = mutableListOf(domainAuth, wildcardAuth)

            val transportType = when (account.transport.uppercase()) {
                "TCP" -> TransportType.Tcp
                "TLS" -> TransportType.Tls
                else -> TransportType.Udp
            }

            // Create Linphone 5.x AccountParams
            val accountParams = core.createAccountParams()
            accountParams.idkey = id

            val identityAddr = Factory.instance().createAddress("sip:$cleanUsername@$cleanDomain")
            if (identityAddr == null) {
                setAccState(id, SipRegistrationState.ERROR, error = "Invalid username or domain format: $cleanUsername@$cleanDomain")
                return
            }
            identityAddr.displayName = account.displayName.trim().ifEmpty { cleanUsername }
            accountParams.identityAddress = identityAddr

            val serverAddr = Factory.instance().createAddress("sip:$cleanServerHost:$effectivePort")
            if (serverAddr == null) {
                setAccState(id, SipRegistrationState.ERROR, error = "Invalid server address format: $cleanServerHost:$effectivePort")
                return
            }
            serverAddr.transport = transportType
            accountParams.serverAddress = serverAddr
            accountParams.transport = transportType
            accountParams.isRegisterEnabled = true
            accountParams.isPublishEnabled = false
            // Short 60s expiry + outbound proxy route ensures UDP NAT hole & Contact IP:port stay fresh for incoming calls
            accountParams.expires = 60
            accountParams.pushNotificationAllowed = false
            accountParams.remotePushNotificationAllowed = false
            accountParams.isOutboundProxyEnabled = true
            accountParams.setRoutesAddresses(arrayOf(serverAddr))

            val natPolicy = core.createNatPolicy().apply {
                isStunEnabled = false
                isIceEnabled = false
                isTurnEnabled = false
                isUpnpEnabled = false
            }
            accountParams.natPolicy = natPolicy

            val linphoneAccount = core.createAccount(accountParams)
            registeredData[id] = account
            core.addAccount(linphoneAccount)
            if (select || core.defaultAccount == null) {
                core.defaultAccount = linphoneAccount
            }

            setAccState(id, SipRegistrationState.REGISTERING)
            val timeout = makeRegistrationTimeout(id)
            regTimeouts[id] = timeout
            mainHandler.postDelayed(timeout, REGISTRATION_TIMEOUT_MS)
            Log.i(
                TAG,
                "SIP registration started: id=$id user=$cleanUsername, domain=$cleanDomain, server=$cleanServerHost:$effectivePort, transport=$transportType"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            setAccState(id, SipRegistrationState.ERROR, error = "Configuration error: ${e.message ?: "Unknown error"}")
        }
    }

    /** শুধু একটি অ্যাকাউন্ট আনরেজিস্টার করে; বাকিগুলো চালু থাকে */
    fun unregisterAccount(id: String) {
        regTimeouts.remove(id)?.let { mainHandler.removeCallbacks(it) }
        failureCounts.remove(id)
        authChallenges.remove(id)
        stoppedByError.remove(id)
        registeredData.remove(id)
        removeLinphoneAccount(id)
        _accountStates.value = _accountStates.value - id
        _accountErrors.value = _accountErrors.value - id
        if (currentAccount?.id == id) currentAccount = null
        publishSelected()
        Log.i(TAG, "SIP account unregistered: $id")
    }

    /**
     * সব SIP অ্যাকাউন্ট আনরেজিস্টার করুন
     */
    fun unregister() {
        registeredData.keys.toList().forEach { unregisterAccount(it) }
        currentAccount = null
        try {
            core.clearAccounts()
            core.clearProxyConfig()
            core.clearAllAuthInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Unregister failed", e)
        }
        _accountStates.value = emptyMap()
        _accountErrors.value = emptyMap()
        publishSelected()
        Log.i(TAG, "SIP unregistered (all)")
    }

    /**
     * SIP কল করুন
     */
    fun makeCall(rawTarget: String): Boolean {
        return try {
            val selectedLinphoneAccount = currentAccount?.let { core.getAccountByIdkey(it.id) }
            val decoded = Uri.decode(rawTarget)
                .removePrefix("sip:")
                .removePrefix("tel:")
                .replace(" ", "")
                .replace("-", "")
                .trim()

            if (decoded.isEmpty()) return false

            val address = if (decoded.contains("@")) {
                "sip:$decoded"
            } else {
                val domain = selectedLinphoneAccount?.params?.identityAddress?.domain
                if (domain.isNullOrEmpty()) {
                    Log.e(TAG, "Cannot make call: selected SIP account is not registered")
                    return false
                }
                "sip:$decoded@$domain"
            }

            val addr = Factory.instance().createAddress(address) ?: return false
            val params = core.createCallParams(null)
            params?.mediaEncryption = MediaEncryption.None
            // আউটগোয়িং কল সবসময় নির্বাচিত অ্যাকাউন্ট দিয়ে
            if (selectedLinphoneAccount != null) params?.account = selectedLinphoneAccount
            params?.isAudioEnabled = true
            params?.isVideoEnabled = false
            activeRecordingPath = prepareRecordingPath(address)
            if (activeRecordingPath.isNotEmpty()) {
                params?.recordFile = activeRecordingPath
            }

            val call = core.inviteAddressWithParams(addr, params!!)
            Log.i(TAG, "Calling remote party")
            call != null
        } catch (e: Exception) {
            Log.e(TAG, "Make call failed", e)
            false
        }
    }

    /**
     * Answer incoming SIP call
     */
    fun answerCall() {
        // মাইক্রোফোন-টাইপ ফোরগ্রাউন্ড সার্ভিস আগে চালু করি, নইলে ব্যাকগ্রাউন্ডে অপরপ্রান্ত আমাদের কথা শোনে না
        SipForegroundService.setCallActive(context, true)
        mainHandler.removeCallbacks(ringingWatchdog)
        CallRinger.stop()
        try {
            core.stopRinging()
        } catch (_: Exception) {
        }
        NotificationHelper.cancelIncomingCallNotification(context)
        NativeDialerOverlay.dismiss()
        try {
            val targetCall = core.currentCall ?: core.calls.firstOrNull()
            targetCall?.let { call ->
                val remoteAddr = call.remoteAddress.asStringUriOnly()
                val params = core.createCallParams(call)
                params?.isAudioEnabled = true
                params?.isVideoEnabled = false
                if (activeRecordingPath.isEmpty()) {
                    activeRecordingPath = prepareRecordingPath(remoteAddr)
                }
                if (activeRecordingPath.isNotEmpty()) {
                    params?.recordFile = activeRecordingPath
                }
                call.acceptWithParams(params!!)
                _callState.value = SipCallState.Connected(remoteAddr, call)
                Log.i(TAG, "Call answered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Answer call failed", e)
        }
    }

    /**
     * End active or outgoing SIP call
     */
    fun endCall() {
        mainHandler.removeCallbacks(ringingWatchdog)
        CallRinger.stop()
        try {
            core.stopRinging()
        } catch (_: Exception) {
        }
        NotificationHelper.cancelIncomingCallNotification(context)
        NativeDialerOverlay.dismiss()
        try {
            val allCalls = core.calls
            if (allCalls.isNotEmpty()) {
                for (c in allCalls) {
                    try {
                        if (c.state == Call.State.IncomingReceived || c.state == Call.State.IncomingEarlyMedia) {
                            c.decline(Reason.Declined)
                        } else {
                            c.terminate()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            core.terminateAllCalls()
            Log.i(TAG, "Call ended")
        } catch (e: Exception) {
            Log.e(TAG, "End call failed", e)
        }
        val currentRemote = when (val s = _callState.value) {
            is SipCallState.Incoming -> s.remoteAddress
            is SipCallState.Outgoing -> s.remoteAddress
            is SipCallState.Connected -> s.remoteAddress
            is SipCallState.OnHold -> s.remoteAddress
            else -> ""
        }
        if (currentRemote.isNotEmpty()) {
            _callState.value = SipCallState.Ended(currentRemote, "Call ended")
        }
    }

    /**
     * Decline incoming SIP call
     */
    fun declineCall() {
        mainHandler.removeCallbacks(ringingWatchdog)
        CallRinger.stop()
        try {
            core.stopRinging()
        } catch (_: Exception) {
        }
        NotificationHelper.cancelIncomingCallNotification(context)
        NativeDialerOverlay.dismiss()
        try {
            val allCalls = core.calls
            for (c in allCalls) {
                try {
                    c.decline(Reason.Declined)
                } catch (_: Exception) {
                    try {
                        c.terminate()
                    } catch (_: Exception) {
                    }
                }
            }
            core.terminateAllCalls()
            Log.i(TAG, "Call declined")
        } catch (e: Exception) {
            Log.e(TAG, "Decline call failed", e)
        }
        val currentRemote = (_callState.value as? SipCallState.Incoming)?.remoteAddress ?: ""
        _callState.value = SipCallState.Ended(currentRemote, "Declined")
    }

    /**
     * মিউট সেট বা টগল
     */
    fun setMute(muted: Boolean) {
        core.isMicEnabled = !muted
        _isMuted.value = muted
    }

    fun toggleMute() {
        setMute(!_isMuted.value)
    }

    /**
     * স্পিকার সেট বা টগল
     */
    fun setSpeaker(enableSpeaker: Boolean) {
        speakerWanted = enableSpeaker
        applyAudioRoute()
        _isSpeaker.value = enableSpeaker
    }

    /** স্পিকার চাওয়া হয়েছে কি না; না হলে ব্লুটুথ > তারের হেডসেট > কানের স্পিকার */
    private var speakerWanted = false

    private fun pickOutputDevice(): AudioDevice? {
        val devices = core.audioDevices
        if (speakerWanted) return devices.find { it.type == AudioDevice.Type.Speaker }
        return devices.firstOrNull {
            it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: devices.firstOrNull {
            it.type == AudioDevice.Type.Headset || it.type == AudioDevice.Type.Headphones
        } ?: devices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
    }

    /** কলের শব্দ ও মাইক বেছে নেওয়া ডিভাইসে পাঠায় (ব্লুটুথে দুটোই ব্লুটুথ) */
    fun applyAudioRoute() {
        try {
            // Beta: SIP কল না থাকলে (যেমন SIM কলের সময় Telecom অডিও ডিভাইস বদলালে) Linphone-এর ডিভাইস ছোঁয়া হয় না,
            // নইলে SIM কলের মাইক/রুটে হস্তক্ষেপ হতে পারে
            if (core.callsNb == 0) return
            val out = pickOutputDevice() ?: return
            routeTo(out)
        } catch (e: Exception) {
            android.util.Log.w("SipEngine", "applyAudioRoute failed", e)
        }
    }

    private fun routeTo(out: AudioDevice) {
        core.outputAudioDevice = out
        core.currentCall?.outputAudioDevice = out
        // মাইক: ব্লুটুথ/হেডসেট হলে একই ডিভাইস (রেকর্ড ক্ষমতা থাকলে), নইলে ফোনের মাইক
        val input = if (out.hasCapability(AudioDevice.Capabilities.CapabilityRecord)) out
        else core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Microphone }
        if (input != null) {
            core.inputAudioDevice = input
            core.currentCall?.inputAudioDevice = input
        }
    }

    /**
     * আউটগোয়িং কলে একই ডিভাইস "আবার সেট" করলে কিছু হয় না, কিন্তু স্পিকার অন-অফ (ডিভাইস বদল) করলে ব্লুটুথ চালু হয়।
     * তাই ব্লুটুথ থাকলে আগে কানের স্পিকারে নিয়ে (শব্দহীন) একটু পরে ব্লুটুথে সরাই।
     */
    private fun forceBluetoothSwitch() {
        try {
            android.util.Log.d(
                "SipEngine",
                "audio devices: " + core.audioDevices.joinToString { "${it.type}:${it.deviceName}" } +
                    " | output=" + core.outputAudioDevice?.type
            )
            val target = pickOutputDevice() ?: return
            if (speakerWanted || target.type != AudioDevice.Type.Bluetooth) {
                routeTo(target)
                return
            }
            val ear = core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
            if (ear != null) routeTo(ear)
            mainHandler.postDelayed({ if (core.currentCall != null) applyAudioRoute() }, 200)
        } catch (e: Exception) {
            android.util.Log.w("SipEngine", "forceBluetoothSwitch failed", e)
        }
    }

    fun toggleSpeaker() {
        setSpeaker(!_isSpeaker.value)
    }

    /**
     * হোল্ড টগল
     */
    fun toggleHold() {
        // Linphone: হোল্ড অবস্থায় core.currentCall null হয়ে যায়, তাই resume-এর জন্য calls তালিকা থেকেও খুঁজি
        val call = core.currentCall
            ?: core.calls.firstOrNull { it.state == Call.State.Paused || it.state == Call.State.Pausing }
            ?: return
        if (call.state == Call.State.Paused || call.state == Call.State.Pausing) {
            call.resume()
        } else {
            call.pause()
        }
    }

    /**
     * DTMF পাঠান
     */
    fun sendDtmf(digit: Char) {
        core.currentCall?.sendDtmf(digit)
    }

    /**
     * কল ডিউরেশন (সেকেন্ডে)
     */
    fun getCallDuration(): Int {
        return core.currentCall?.duration ?: 0
    }

    fun destroy() {
        regTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
        regTimeouts.clear()
        core.removeListener(coreListener)
        try {
            Factory.instance().loggingService.removeListener(sipLogListener)
        } catch (_: Exception) {
        }
        core.stop()
        INSTANCE = null
    }
}
