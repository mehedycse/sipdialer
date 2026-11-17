package com.noxsip.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.app.NotificationCompat
import com.noxsip.app.CHANNEL_SERVICE_ID
import com.noxsip.app.MainActivity
import com.noxsip.app.R
import com.noxsip.app.data.AccountRepository
import com.noxsip.app.sip.SipEngine
import com.noxsip.app.sip.SipRegistrationState
import com.noxsip.app.telecom.TelecomHelper
import com.noxsip.app.util.CallNetworkLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SIP ফোরগ্রাউন্ড সার্ভিস - ব্যাকগ্রাউন্ডে SIP রেজিস্ট্রেশন ধরে রাখে
 */
class SipForegroundService : Service() {

    companion object {
        private const val TAG = "SipForegroundService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.noxsip.app.action.START"
        const val ACTION_STOP = "com.noxsip.app.action.STOP"
        @Volatile
        private var isRunning = false

        const val ACTION_POWER_REFRESH = "com.noxsip.app.action.POWER_REFRESH"

        fun isActive(): Boolean = isRunning

        /** ব্যাটারি মোড বদলালে চলমান সার্ভিসে নতুন মোড প্রয়োগ */
        fun refreshPower(context: Context) {
            try {
                if (isRunning) {
                    context.startService(Intent(context, SipForegroundService::class.java).apply { action = ACTION_POWER_REFRESH })
                } else {
                    start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshPower failed: ${e.message}")
            }
        }

        const val ACTION_CALL_ACTIVE ="com.noxsip.app.action.CALL_ACTIVE"
        const val ACTION_CALL_ENDED = "com.noxsip.app.action.CALL_ENDED"

        /**
         * কল শুরু/রিসিভের সময় সার্ভিসকে phoneCall|microphone টাইপে আপগ্রেড করে।
         * Android 11+ এ ব্যাকগ্রাউন্ড অ্যাপের মাইক্রোফোন ক্যাপচার সাইলেন্ট করে দেওয়া হয়
         * (অপরপ্রান্ত আপনার কথা শোনে না), যদি না microphone-টাইপ ফোরগ্রাউন্ড সার্ভিস চালু থাকে।
         */
        fun setCallActive(context: Context, active: Boolean) {
            // সার্ভিস বন্ধ থাকলে (অ্যাকাউন্ট আনরেজিস্টার্ড) কল শেষে নতুন করে চালু করে নোটিফিকেশন আটকে রাখব না
            if (!isRunning) return
            try {
                val intent = Intent(context, SipForegroundService::class.java).apply {
                    action = if (active) ACTION_CALL_ACTIVE else ACTION_CALL_ENDED
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "setCallActive($active) failed: ${e.message}")
            }
        }

        fun start(context: Context) {
            KeepAliveState.setStoppedByUser(context, false)
            try {
                val intent = Intent(context, SipForegroundService::class.java).apply {
                    action = ACTION_START
                }
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SipForegroundService", e)
            }
        }

        fun stop(context: Context) {
            KeepAliveState.setStoppedByUser(context, true)
            try {
                val intent = Intent(context, SipForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop SipForegroundService", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sipEngine: SipEngine
    private var cpuWakeLock: PowerManager.WakeLock? = null

    /**
     * স্ক্রিন বন্ধ হলে CPU ঘুমিয়ে গেলে SIP টাইমার (REGISTER রিফ্রেশ, keep-alive) চলে না, সার্ভারে রেজিস্ট্রেশন
     * মেয়াদোত্তীর্ণ হয় আর ইনকামিং কল আসে না। তাই সার্ভিস চলাকালীন CPU ও Wi-Fi জাগিয়ে রাখা হয়।
     */
    private fun acquireStandbyLocks() {
        if (com.noxsip.app.util.PowerPrefs.mode(this) == com.noxsip.app.util.PowerPrefs.RELIABLE) {
            acquireCpuLock()
            // রিলায়েবল মোডেও প্রহরী অ্যালার্ম: সার্ভিস মরে গেলে সে-ই আবার চালু করে
            KeepAliveScheduler.schedule(this)
        } else {
            KeepAliveScheduler.schedule(this)
        }
        CallNetworkLock.acquireStandby(applicationContext)
    }

    private fun acquireCpuLock() {
        try {
            if (cpuWakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SipDialer:Registration").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "CPU wake lock acquired for SIP registration")
            }
        } catch (e: Exception) {
            Log.w(TAG, "CPU wake lock failed: ${e.message}")
        }
    }

    private fun releaseCpuLock() {
        try {
            cpuWakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        cpuWakeLock = null
    }

    private fun releaseStandbyLocks() {
        releaseCpuLock()
        KeepAliveScheduler.cancel(this)
        CallNetworkLock.releaseStandby()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sipEngine = SipEngine.getInstance(applicationContext)

        // ইনকামিং কল হলে নেটিভ ডায়ালারে দেখান
        sipEngine.onIncomingCall = { callerAddress ->
            TelecomHelper.reportIncomingCall(applicationContext, callerAddress)
        }

        registerNetworkWatch()
        Log.i(TAG, "SipForegroundService created")
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private var networkLost = false
    private var lastVpn: Boolean? = null

    private fun isVpn(cm: ConnectivityManager, network: Network): Boolean =
        try { cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true } catch (_: Exception) { false }

    /** Wi-Fi ↔ মোবাইল ডেটা বদলালে বা নেটওয়ার্ক ফিরলে দ্রুত রি-রেজিস্টার */
    private fun registerNetworkWatch() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = lastNetwork != null && lastNetwork != network
                    if (changed || networkLost) {
                        Log.i(TAG, "Network changed/restored (vpn=" + isVpn(cm, network) + "), resetting SIP sockets")
                        sipEngine.networkChanged()
                    }
                    lastNetwork = network
                    networkLost = false
                }

                override fun onLost(network: Network) {
                    if (lastNetwork == network) networkLost = true
                }

                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                    // VPN চালু হলে ডিফল্ট নেটওয়ার্কের ক্ষমতা বদলায় (TRANSPORT_VPN যোগ হয়)
                    val vpn = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    if (lastVpn != null && lastVpn != vpn) {
                        Log.i(TAG, "VPN state changed (vpn=" + vpn + "), resetting SIP sockets")
                        sipEngine.networkChanged()
                    }
                    lastVpn = vpn
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network watch failed: ${e.message}")
        }
    }

    private fun unregisterNetworkWatch() {
        try {
            netCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }
        netCallback = null
    }

    /** রিসেন্টস থেকে সোয়াইপ: কিছু ফোন সার্ভিস মেরে দেয়, তাই অল্প পরে ফেরানোর অ্যালার্ম */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!KeepAliveState.stoppedByUser(this)) {
            Log.i(TAG, "Task removed, scheduling revive")
            KeepAliveScheduler.schedule(this, 2_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                KeepAliveState.setStoppedByUser(this, false)
                startForegroundSafely()
                acquireStandbyLocks()
                registerSipAccount()
            }
            ACTION_CALL_ACTIVE -> {
                startForegroundSafely(callActive = true)
                // কল চলাকালীন CPU জাগিয়ে রাখি (Balanced মোডেও)
                acquireCpuLock()
            }
            ACTION_CALL_ENDED -> {
                startForegroundSafely(callActive = false)
                if (com.noxsip.app.util.PowerPrefs.mode(this) != com.noxsip.app.util.PowerPrefs.RELIABLE) releaseCpuLock()
            }
            ACTION_POWER_REFRESH -> {
                releaseCpuLock()
                KeepAliveScheduler.cancel(this)
                acquireStandbyLocks()
            }
            ACTION_STOP -> {
                KeepAliveState.setStoppedByUser(this, true)
                releaseStandbyLocks()
                sipEngine.unregister()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafely(callActive: Boolean = false) {
        val notification = createNotification()
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (callActive && !micGranted) {
            Log.e(TAG, "RECORD_AUDIO not granted: the remote party will NOT hear you. Grant Microphone permission.")
        }
        try {
            if (callActive && micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, types)
                    Log.i(TAG, "Foreground service promoted for call (microphone|phoneCall)")
                    return
                } catch (e: Exception) {
                    // ব্যাকগ্রাউন্ড থেকে microphone টাইপ শুরু করা নিষিদ্ধ হলে সাধারণ মোডে ফিরে যাই
                    Log.w(TAG, "Call-mode foreground promotion refused: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback startForeground due to exception", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not startForeground", e2)
            }
        }
    }

    private fun registerSipAccount() {
        serviceScope.launch {
            try {
                TelecomHelper.registerPhoneAccount(applicationContext)
                // শুধুমাত্র যদি এখনো রেজিস্ট্রেশন শুরু না হয়ে থাকে (যেমন বুট বা ব্যাকগ্রাউন্ড রিস্টার্ট) তখনই রেজিস্টার করুন
                if (sipEngine.registrationState.value == SipRegistrationState.UNREGISTERED) {
                    val repository = AccountRepository(applicationContext)
                    val account = repository.getAccount().first()
                    if (account != null) {
                        sipEngine.registerAllSaved()
                        Log.i(TAG, "SIP accounts registered from service")
                    } else {
                        Log.w(TAG, "No SIP account configured")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register SIP account", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkWatch()
        releaseStandbyLocks()
        isRunning = false
        serviceScope.cancel()
        Log.i(TAG, "SipForegroundService destroyed")
        super.onDestroy()
    }
}
