package com.noxsip.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.noxsip.app.telecom.NativeDialerOverlay
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.core.LogLevel
import org.linphone.core.PayloadType

class SipDialerApplication : Application() {

    companion object {
        lateinit var instance: SipDialerApplication
            private set
        lateinit var core: Core
            private set

        @Volatile
        var isMainActivityVisible: Boolean = false

        /**
         * Configures crystal-clear 8kHz G.711 (PCMA/PCMU) PSTN-grade audio with fixed 20ms RTP ptime
         * and disables Adaptive Rate Control / Echo Limiter that cause choppy/garbled outgoing voice.
         */
        fun configureVoiceAudio(core: Core) {
            try {
                val hd = com.noxsip.app.util.AudioQualityPrefs.isHdCodecsEnabled(instance)

                // G.711: Adaptive Rate Control বন্ধ, নইলে Linphone মাঝকলে upload ptime (20ms -> 60ms) বদলে দেয়।
                // Opus (HD মোড): ARC চালু, নেটওয়ার্ক খারাপ হলে বিটরেট নামিয়ে কল টিকিয়ে রাখে।
                core.isAdaptiveRateControlEnabled = hd

                // RTP প্যাকেটে DSCP মার্কিং (EF=46 অডিও, CS3=24 SIP)। রাউটার/ISP QoS মানলে সুবিধা পাওয়া যায়।
                core.audioDscp = 46
                core.sipDscp = 24
                core.uploadPtime = 20
                core.downloadPtime = 20
                core.audioJittcomp = 60
                core.isAudioAdaptiveJittcompEnabled = true

                // Disable software Echo Limiter (which gates/chops the user's microphone)
                core.isEchoLimiterEnabled = false
                if (core.hasBuiltinEchoCanceller()) {
                    core.isEchoCancellationEnabled = false
                }
                // মাইক বুস্ট: Vivo-তে ফোন একটু দূরে ধরলে ইনপুট খুব দুর্বল (-40 dB) আসে, তাই ডিফল্ট +10 dB
                core.micGainDb = com.noxsip.app.util.AudioQualityPrefs.getMicBoostDb(instance).toFloat()
                core.playbackGainDb = 0.0f

                core.config.setInt("rtp", "RTP_no_xmit_on_audio_mute", 0)
                core.config.setInt("sound", "echolimiter", 0)

                val allPayloads = core.audioPayloadTypes.toList()

                // ইউজারের নিজের কোডেক তালিকা থাকলে সেটাই ব্যবহার (ক্রম + চালু/বন্ধ)
                val custom = com.noxsip.app.util.AudioQualityPrefs.getCodecConfig(instance)
                if (custom != null) {
                    fun keyOf(pt: PayloadType) = pt.mimeType.uppercase() + "/" + pt.clockRate
                    val byKey = allPayloads.associateBy { keyOf(it) }
                    val ordered = ArrayList<PayloadType>()
                    custom.forEach { c ->
                        val pt = byKey[c.key] ?: return@forEach
                        pt.enable(c.enabled)
                        ordered.add(pt)
                    }
                    // তালিকায় না থাকা কোডেক বন্ধ রেখে শেষে
                    allPayloads.filter { it !in ordered }.forEach { it.enable(false); ordered.add(it) }
                    // Adaptive Rate Control: প্রথম চালু কোডেক Opus হলে চালু
                    core.isAdaptiveRateControlEnabled =
                        custom.firstOrNull { it.enabled }?.key?.startsWith("OPUS") == true
                    core.setAudioPayloadTypes(ordered.toTypedArray())
                    return
                }

                // Prioritize PCMA (G.711 A-law 8000Hz) and PCMU (G.711 u-law 8000Hz) for PSTN/IPTSP gateways
                val pcma = allPayloads.filter { it.mimeType.equals("PCMA", ignoreCase = true) && it.clockRate == 8000 }
                val pcmu = allPayloads.filter { it.mimeType.equals("PCMU", ignoreCase = true) && it.clockRate == 8000 }
                val g729 = allPayloads.filter { it.mimeType.equals("G729", ignoreCase = true) && it.clockRate == 8000 }
                val gsm = allPayloads.filter { it.mimeType.equals("GSM", ignoreCase = true) && it.clockRate == 8000 }
                // HD কোডেক: Opus (সাধারণত 48000Hz) ও G.722 (SDP clock rate 8000)
                val opus = allPayloads.filter { it.mimeType.equals("opus", ignoreCase = true) }
                val g722 = allPayloads.filter { it.mimeType.equals("G722", ignoreCase = true) }
                val others = allPayloads.filter { pt ->
                    pt !in pcma && pt !in pcmu && pt !in g729 && pt !in gsm && pt !in opus && pt !in g722
                }

                val ordered = ArrayList<PayloadType>(allPayloads.size).apply {
                    if (hd) {
                        addAll(opus)
                        addAll(g722)
                    }
                    addAll(pcma)
                    addAll(pcmu)
                    addAll(g729)
                    addAll(gsm)
                    if (!hd) {
                        addAll(opus)
                        addAll(g722)
                    }
                    addAll(others)
                }

                // HD বন্ধ থাকলে শুধু 8000Hz PSTN-সামঞ্জস্যপূর্ণ কোডেক (PCMA, PCMU, G729, GSM); HD চালু থাকলে Opus + G.722 আগে যোগ হয়।
                // Speex/iLBC/L16 সবসময় বন্ধ, ট্রান্সকোডিং বিকৃতি এড়াতে।
                opus.forEach { it.enable(hd) }
                g722.forEach { it.enable(hd) }
                pcma.forEach { it.enable(true) }
                pcmu.forEach { it.enable(true) }
                g729.forEach { it.enable(true) }
                gsm.forEach { it.enable(true) }
                others.forEach { it.enable(false) }

                core.setAudioPayloadTypes(ordered.toTypedArray())
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.noxsip.app.ui.theme.UiTextScale.load(this)
        com.noxsip.app.ui.theme.ThemeMode.load(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityVisible = true
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityVisible = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityVisible = true
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) {
                    isMainActivityVisible = false
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        createNotificationChannels()
        initializeLinphone()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Delete old noisy call channels so Android never plays a duplicate notification sound
        try {
            notificationManager.deleteNotificationChannel("sip_call_channel")
            notificationManager.deleteNotificationChannel("sip_call_channel_v2")
        } catch (_: Exception) {
        }

        // পুরনো (LOW গুরুত্বের) চ্যানেল মুছে নতুন MIN চ্যানেল: স্ট্যাটাস বারে আইকন আসে না, শেডে নিচে ছোট করে থাকে
        try {
            notificationManager.deleteNotificationChannel("sip_service_channel")
        } catch (_: Exception) {
        }
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }

        val callChannel = NotificationChannel(
            CHANNEL_CALL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming SIP call notifications"
            setShowBadge(true)
            // Silent channel: CallRinger plays the single ringtone so there is never a double ringtone!
            setSound(null, null)
            enableVibration(false)
        }

        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(callChannel)
    }

    private fun initializeLinphone() {
        // Message-লেভেল লগ সবসময় চালু (Debug-লেভেলের মতো ভারী নয়): SipEngine "caller hung up" শনাক্তকরণের জন্য
        // সার্ভার থেকে আসা INVITE পুনঃপ্রেরণা এই লগ থেকে ধরে। বন্ধ করলে ওই ফিচার কাজ করবে না।
        Factory.instance().setDebugMode(false, "SipDialer")
        try {
            Factory.instance().loggingService.setLogLevel(LogLevel.Message)
        } catch (_: Exception) {
        }
        Factory.instance().enableLogCollection(LogCollectionState.Disabled)

        core = Factory.instance().createCore(null, null, this)

        try {
            core.config.setInt("sip", "keepalive_period", 15000)
            core.config.setInt("sip", "use_rport", 1)
            core.config.setInt("sip", "contact_autorewrite", 1)
            core.config.setInt("sip", "guess_hostname", 1)
            core.config.setInt("sip", "inc_timeout", 35)
        } catch (_: Exception) {
        }

        core.isKeepAliveEnabled = true
        core.guessHostname = true
        core.isPushNotificationEnabled = false
        core.isAutoSendRingingEnabled = true
        // Disable both Java and C++ internal ringers so only CallRinger plays a single ringtone
        core.isNativeRingingEnabled = false
        core.ring = null
        core.ringback = null
        core.isVibrationOnIncomingCallEnabled = false
        core.ringDuringIncomingEarlyMedia = false
        core.isAutoIterateEnabled = true
        core.autoIterateForegroundSchedule = 20
        core.autoIterateBackgroundSchedule = 50

        configureVoiceAudio(core)

        core.start()
    }

    override fun onTerminate() {
        core.stop()
        super.onTerminate()
    }
}

const val CHANNEL_SERVICE_ID = "sip_service_min"
const val CHANNEL_CALL_ID = "sip_call_channel_silent_v3"
