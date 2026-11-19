package com.noxsip.app.telecom

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.noxsip.app.sip.SipCallState
import com.noxsip.app.sip.SipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Compact White Pill-Shaped Account Chooser Overlay (SIM 1 | SIM 2 | SIP in a single row)
 * and VoIP In-Call controls over the native dialer.
 */
object NativeDialerOverlay {

    private const val TAG = "NativeDialerOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentOverlayView: View? = null
    private var isShowingIncomingOverlay: Boolean = false
    private var callMonitorJob: Job? = null
    private var timerJob: Job? = null

    fun isIncomingOverlayShowing(): Boolean = isShowingIncomingOverlay

    fun canShowOverlay(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun dismissIncomingIfVisible() {
        if (isShowingIncomingOverlay) {
            dismiss()
        }
    }

    /**
     * Compact White Popup at the bottom with 3 Pill-Shaped buttons in a single row: SIM 1 | SIM 2 | SIP
     */
    fun showAccountChooser(
        context: Context,
        number: String,
        onSimSelected: (PhoneAccountHandle?) -> Unit,
        onSipSelected: () -> Unit,
        onCancel: () -> Unit,
        // ফোনের ডায়ালারে যে SIM চাপা হয়েছে তার handle; অন্য SIM ধূসর ও বন্ধ থাকবে
        lockedHandle: PhoneAccountHandle? = null
    ) {
        mainHandler.post {
            dismiss()

            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val simAccounts = TelecomHelper.getAvailableSimAccounts(appContext)

            // Subtle dim backdrop (tap outside to cancel)
            val rootLayout = FrameLayout(appContext).apply {
                setBackgroundColor(Color.parseColor("#66000000"))
                setOnClickListener {
                    dismiss()
                    onCancel()
                }
            }

            // Compact White Card at bottom
            val card = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRectWithBorder(
                    fillColor = Color.WHITE,
                    strokeColor = Color.parseColor("#E2E8F0"),
                    radiusPx = dp(appContext, 22),
                    strokePx = dp(appContext, 1)
                )
                setPadding(dp(appContext, 16), dp(appContext, 14), dp(appContext, 16), dp(appContext, 16))
                isClickable = true
                elevation = dp(appContext, 8).toFloat()
            }

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                val sideMargin = dp(appContext, 16)
                val bottomMargin = dp(appContext, 28)
                setMargins(sideMargin, sideMargin, sideMargin, bottomMargin)
            }

            // Compact Title Header
            val titleView = TextView(appContext).apply {
                text = "Select Calling Account"
                setTextColor(Color.parseColor("#1E293B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(appContext, 12))
            }
            card.addView(titleView)

            // Single horizontal row for Pill Buttons: SIM 1 | SIM 2 | SIP
            val pillsRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            if (simAccounts.isNotEmpty()) {
                simAccounts.forEachIndexed { idx, sim ->
                    val pillColor = if (sim.slotIndex == 1) Color.parseColor("#2E8B6E") else Color.parseColor("#3E7FA6")
                    val isLockedOut = lockedHandle != null &&
                        simAccounts.any { it.handle == lockedHandle } && sim.handle != lockedHandle
                    val pillBtn = createPillButton(
                        context = appContext,
                        label = "SIM ${sim.slotIndex}",
                        bgColor = if (isLockedOut) Color.parseColor("#CBD5E1") else pillColor,
                        enabled = !isLockedOut
                    ) {
                        dismiss()
                        onSimSelected(sim.handle)
                    }
                    pillsRow.addView(
                        pillBtn,
                        LinearLayout.LayoutParams(0, dp(appContext, 42), 1f).apply {
                            if (idx > 0) leftMargin = dp(appContext, 8)
                        }
                    )
                }
            } else {
                // Fallback SIM 1 & SIM 2 pills if Telecom accounts list is empty
                val sim1Btn = createPillButton(
                    context = appContext,
                    label = "SIM 1",
                    bgColor = Color.parseColor("#2E8B6E")
                ) {
                    dismiss()
                    onSimSelected(null)
                }
                pillsRow.addView(
                    sim1Btn,
                    LinearLayout.LayoutParams(0, dp(appContext, 42), 1f)
                )

                val sim2Btn = createPillButton(
                    context = appContext,
                    label = "SIM 2",
                    bgColor = Color.parseColor("#3E7FA6")
                ) {
                    dismiss()
                    onSimSelected(null)
                }
                pillsRow.addView(
                    sim2Btn,
                    LinearLayout.LayoutParams(0, dp(appContext, 42), 1f).apply {
                        leftMargin = dp(appContext, 8)
                    }
                )
            }

            // SIP Pill Button
            val sipBtn = createPillButton(
                context = appContext,
                label = "SIP",
                bgColor = Color.parseColor("#3B6998")
            ) {
                dismiss()
                if (number.isNotBlank()) {
                    onSipSelected()
                    openAppCallScreen(appContext, number = number)
                }
            }
            pillsRow.addView(
                sipBtn,
                LinearLayout.LayoutParams(0, dp(appContext, 42), 1f).apply {
                    leftMargin = dp(appContext, 8)
                }
            )

            card.addView(
                pillsRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            // Cancel button (পপআপ বন্ধ, কিছু ডায়াল হবে না)
            val cancelBtn = TextView(appContext).apply {
                text = "Cancel"
                setTextColor(Color.parseColor("#475569"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedRectWithBorder(
                    fillColor = Color.parseColor("#F1F5F9"),
                    strokeColor = Color.parseColor("#E2E8F0"),
                    radiusPx = dp(appContext, 24),
                    strokePx = dp(appContext, 1)
                )
                setOnClickListener {
                    dismiss()
                    onCancel()
                }
            }
            card.addView(
                cancelBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(appContext, 42)
                ).apply { topMargin = dp(appContext, 10) }
            )

            rootLayout.addView(card, cardParams)

            try {
                wm.addView(rootLayout, createWindowParams())
                currentOverlayView = rootLayout
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show chooser overlay", e)
            }
        }
    }

    private fun createPillButton(
        context: Context,
        label: String,
        bgColor: Int,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): View {
        return TextView(context).apply {
            text = label
            isEnabled = enabled
            setTextColor(if (enabled) Color.WHITE else Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect(bgColor, dp(context, 24))
            if (enabled) setOnClickListener { onClick() }
        }
    }

    /**
     * Compact Top Heads-Up Incoming Call Popup (at the very top of the screen).
     * Tapping Answer opens the full-screen InCallScreen!
     */
    fun showIncomingCallOverlay(context: Context, callerAddress: String) {
        mainHandler.post {
            dismiss()

            val appContext = context.applicationContext
            if (!canShowOverlay(appContext)) return@post

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sipEngine = SipEngine.getInstance(appContext)
            val cleanCaller = callerAddress.removePrefix("sip:").substringBefore("@").ifEmpty { callerAddress }

            val rootLayout = FrameLayout(appContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            // Sleek Top Banner Card
            val card = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRectWithBorder(
                    fillColor = Color.parseColor("#14253E"),
                    strokeColor = Color.parseColor("#3B6998"),
                    radiusPx = dp(appContext, 20),
                    strokePx = dp(appContext, 1)
                )
                setPadding(dp(appContext, 18), dp(appContext, 14), dp(appContext, 18), dp(appContext, 14))
                elevation = dp(appContext, 12).toFloat()
            }

            // Top row: Caller info on left
            val badgeView = TextView(appContext).apply {
                text = "Incoming call via ${sipEngine.callAccountUser.value ?: "SIP"}"
                setTextColor(Color.parseColor("#93C5FD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(badgeView)

            val callerView = TextView(appContext).apply {
                text = cleanCaller
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(appContext, 2), 0, dp(appContext, 12))
            }
            card.addView(callerView)

            val actionsRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val declineBtn = createPillButton(
                context = appContext,
                label = "Decline",
                bgColor = Color.parseColor("#C85A5A")
            ) {
                sipEngine.declineCall()
                dismiss()
            }

            val answerBtn = createPillButton(
                context = appContext,
                label = "Answer",
                bgColor = Color.parseColor("#2E8B6E")
            ) {
                dismiss()
                sipEngine.answerCall()
                try {
                    val intent = android.content.Intent(appContext, com.noxsip.app.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("incoming_call", true)
                        putExtra("caller_address", callerAddress)
                    }
                    appContext.startActivity(intent)
                } catch (_: Exception) {
                }
                mainHandler.postDelayed({
                    if (!com.noxsip.app.SipDialerApplication.isMainActivityVisible) {
                        showInCallOverlay(appContext, cleanCaller)
                    }
                }, 250L)
            }

            actionsRow.addView(
                declineBtn,
                LinearLayout.LayoutParams(0, dp(appContext, 40), 1f)
            )
            actionsRow.addView(
                answerBtn,
                LinearLayout.LayoutParams(0, dp(appContext, 40), 1f).apply {
                    leftMargin = dp(appContext, 10)
                }
            )

            card.addView(
                actionsRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            // বাটন-ছাড়া অংশে ট্যাপ করলে পূর্ণ-স্ক্রিন কল পেজ খোলে
            card.setOnClickListener {
                dismiss()
                IncomingUiRequest.request()
                try {
                    val intent = android.content.Intent(appContext, com.noxsip.app.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("incoming_call", true)
                        putExtra("caller_address", callerAddress)
                    }
                    appContext.startActivity(intent)
                } catch (_: Exception) {
                }
            }

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val sideMargin = dp(appContext, 12)
                // স্ট্যাটাস বারের ঠিক নিচে (যেকোনো স্ক্রিনের একদম উপরে)
                val topMargin = statusBarHeightPx(appContext) + dp(appContext, 4)
                setMargins(sideMargin, topMargin, sideMargin, dp(appContext, 8))
            }

            rootLayout.addView(card, cardParams)

            try {
                wm.addView(rootLayout, createTopBannerWindowParams())
                currentOverlayView = rootLayout
                isShowingIncomingOverlay = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show incoming call top overlay", e)
                return@post
            }

            // Auto-dismiss immediately if caller hangs up or call state leaves Incoming
            callMonitorJob = scope.launch {
                sipEngine.callState.collectLatest { state ->
                    if (state !is SipCallState.Incoming) {
                        dismiss()
                    }
                }
            }
        }
    }

    /**
     * Full-screen VoIP In-Call overlay when dialing from the native dialer
     */
    /** ফোনের ডায়ালার থেকে SIP কল শুরু হলে কল শেষে আগের অ্যাপে ফিরে যেতে MainActivity-কে জানায় */
    @Volatile
    var returnToPreviousAfterCall = false

    /** ফোনের ডায়ালার থেকে SIP বেছে নেওয়ার পর কল স্ক্রিন না আসা পর্যন্ত দেখানোর নম্বর (নেই = কিছু দেখাবে না) */
    val pendingSipCall = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * নেটিভ ডায়ালার থেকে SIP কলের জন্য আলাদা ওভারলে না দেখিয়ে অ্যাপের নিজস্ব কল স্ক্রিন (InCallScreen) খোলে।
     * কল স্টেট Outgoing হলেই MainActivity নিজে InCall স্ক্রিনে চলে যায়।
     */
    fun openAppCallScreen(context: Context, returnToPrevious: Boolean = true, number: String? = null) {
        try {
            if (returnToPrevious) returnToPreviousAfterCall = true
            // কল স্ক্রিন আসা পর্যন্ত অ্যাপের হোম স্ক্রিন ঢেকে রাখার জন্য
            if (!number.isNullOrBlank()) pendingSipCall.value = number
            val intent = android.content.Intent(context.applicationContext, com.noxsip.app.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.applicationContext.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("NativeDialerOverlay", "Could not open app call screen", e)
        }
    }

    fun showInCallOverlay(context: Context, number: String) {
        mainHandler.post {
            dismiss()

            val appContext = context.applicationContext
            if (!canShowOverlay(appContext)) return@post

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sipEngine = SipEngine.getInstance(appContext)

            val rootLayout = FrameLayout(appContext).apply {
                setBackgroundColor(Color.parseColor("#0B1320"))
            }

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(appContext, 28), dp(appContext, 64), dp(appContext, 28), dp(appContext, 44))
            }

            val badgeView = TextView(appContext).apply {
                text = "SIP VOIP AUDIO CALL"
                setTextColor(Color.parseColor("#93C5FD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                background = roundedRect(Color.parseColor("#1A2B45"), dp(appContext, 12))
                setPadding(dp(appContext, 14), dp(appContext, 6), dp(appContext, 14), dp(appContext, 6))
            }
            container.addView(
                badgeView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(appContext, 24) }
            )

            val numberView = TextView(appContext).apply {
                text = number
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(appContext, 6))
            }
            container.addView(numberView)

            val statusView = TextView(appContext).apply {
                text = "Dialing..."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(appContext, 48))
            }
            container.addView(statusView)

            val controlsRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            var isMuted = false
            val muteBtn = TextView(appContext).apply {
                text = "Mute: OFF"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedRectWithBorder(
                    fillColor = Color.parseColor("#1A2B45"),
                    strokeColor = Color.parseColor("#2A4374"),
                    radiusPx = dp(appContext, 20),
                    strokePx = dp(appContext, 1)
                )
                setPadding(dp(appContext, 22), dp(appContext, 12), dp(appContext, 22), dp(appContext, 12))
                setOnClickListener {
                    isMuted = !isMuted
                    sipEngine.setMute(isMuted)
                    text = if (isMuted) "Mute: ON" else "Mute: OFF"
                    background = roundedRect(
                        if (isMuted) Color.parseColor("#C98A3A") else Color.parseColor("#1A2B45"),
                        dp(appContext, 20)
                    )
                }
            }

            var isSpeaker = false
            val speakerBtn = TextView(appContext).apply {
                text = "Speaker: OFF"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedRectWithBorder(
                    fillColor = Color.parseColor("#1A2B45"),
                    strokeColor = Color.parseColor("#2A4374"),
                    radiusPx = dp(appContext, 20),
                    strokePx = dp(appContext, 1)
                )
                setPadding(dp(appContext, 22), dp(appContext, 12), dp(appContext, 22), dp(appContext, 12))
                setOnClickListener {
                    isSpeaker = !isSpeaker
                    sipEngine.setSpeaker(isSpeaker)
                    text = if (isSpeaker) "Speaker: ON" else "Speaker: OFF"
                    background = roundedRect(
                        if (isSpeaker) Color.parseColor("#3B6998") else Color.parseColor("#1A2B45"),
                        dp(appContext, 20)
                    )
                }
            }

            controlsRow.addView(
                muteBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(appContext, 16) }
            )

            controlsRow.addView(
                speakerBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            container.addView(
                controlsRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(appContext, 36) }
            )

            val endCallBtn = TextView(appContext).apply {
                text = "End Call"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedRect(Color.parseColor("#C85A5A"), dp(appContext, 28))
                setPadding(dp(appContext, 40), dp(appContext, 14), dp(appContext, 40), dp(appContext, 14))
                setOnClickListener {
                    sipEngine.endCall()
                    dismiss()
                }
            }
            container.addView(
                endCallBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            rootLayout.addView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

            try {
                wm.addView(rootLayout, createWindowParams())
                currentOverlayView = rootLayout
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show in-call overlay", e)
                return@post
            }

            callMonitorJob = scope.launch {
                var sawActiveOrOutgoing = false
                sipEngine.callState.collectLatest { state ->
                    when (state) {
                        is SipCallState.Outgoing -> {
                            sawActiveOrOutgoing = true
                            statusView.text = "Calling..."
                        }
                        is SipCallState.Connected -> {
                            sawActiveOrOutgoing = true
                            statusView.setTextColor(Color.parseColor("#22C55E"))
                            timerJob?.cancel()
                            timerJob = scope.launch {
                                while (true) {
                                    val sec = sipEngine.getCallDuration()
                                    statusView.text = "Connected • %02d:%02d".format(sec / 60, sec % 60)
                                    delay(1000)
                                }
                            }
                        }
                        is SipCallState.OnHold -> {
                            statusView.text = "Call On Hold"
                            statusView.setTextColor(Color.parseColor("#94A3B8"))
                        }
                        is SipCallState.Ended -> {
                            if (sawActiveOrOutgoing) {
                                statusView.text = "Call Ended"
                                delay(900)
                                dismiss()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun dismiss() {
        callMonitorJob?.cancel()
        callMonitorJob = null
        timerJob?.cancel()
        timerJob = null
        isShowingIncomingOverlay = false
        val view = currentOverlayView ?: return
        currentOverlayView = null
        try {
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun createTopBannerWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        @Suppress("DEPRECATION")
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // পাঞ্চ-হোল/নচ থাকলেও স্ক্রিনের একদম উপর থেকে শুরু
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun statusBarHeightPx(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 24)
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun roundedRect(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun roundedRectWithBorder(
        fillColor: Int,
        strokeColor: Int,
        radiusPx: Int,
        strokePx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(strokePx, strokeColor)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
