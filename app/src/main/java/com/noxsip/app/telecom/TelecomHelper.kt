package com.noxsip.app.telecom

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.noxsip.app.MainActivity
import com.noxsip.app.R
import com.noxsip.app.util.NotificationHelper

data class SimAccountInfo(
    val handle: PhoneAccountHandle,
    val label: String,
    val subLabel: String,
    val slotIndex: Int
)

object TelecomHelper {

    private const val TAG = "TelecomHelper"
    private const val PHONE_ACCOUNT_ID = "sip_dialer_account"
    private const val PREFS_NAME = "telecom_prefs"
    private const val KEY_ROUTING_MODE = "native_call_routing_mode"
    private const val KEY_AUTO_RECORD = "auto_call_record_enabled"

    const val MODE_ASK_EVERY_TIME = 0 // Show SIM 1 / SIM 2 / SIP popup
    const val MODE_ALWAYS_SIP = 1     // Route all native dialer calls via SIP
    const val MODE_SIM_ONLY = 2       // Bypass SIP (use SIM only)

    fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        val componentName = ComponentName(context.applicationContext, SipConnectionService::class.java)
        return PhoneAccountHandle(componentName, PHONE_ACCOUNT_ID)
    }

    fun registerPhoneAccount(context: Context) {
        // Unregister any legacy Telecom PhoneAccount so Android's system dialer never opens
        // a duplicate call screen, never plays a second ringtone, and never fights over the microphone!
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = getPhoneAccountHandle(context)
            telecomManager.unregisterPhoneAccount(handle)
        } catch (_: Exception) {
        }
    }

    /**
     * Queries active SIM cards (SIM 1, SIM 2) from TelecomManager & SubscriptionManager
     */
    fun getAvailableSimAccounts(context: Context): List<SimAccountInfo> {
        val result = mutableListOf<SimAccountInfo>()
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return result
            }
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val ourHandle = getPhoneAccountHandle(context)
            val handles = telecomManager.callCapablePhoneAccounts

            // Also read carrier names from SubscriptionManager if available
            val subCarriers = mutableMapOf<Int, String>()
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                subManager?.activeSubscriptionInfoList?.forEach { info ->
                    val slot = info.simSlotIndex + 1
                    val carrier = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    if (!carrier.isNullOrBlank()) {
                        subCarriers[slot] = carrier
                    }
                }
            } catch (_: Exception) {
            }

            var index = 1
            for (handle in handles) {
                if (handle == ourHandle || handle.componentName.packageName == context.packageName) {
                    continue
                }
                val account = telecomManager.getPhoneAccount(handle)
                val rawLabel = account?.label?.toString()?.takeIf { it.isNotBlank() }
                    ?: subCarriers[index]
                    ?: "SIM $index"
                val desc = account?.shortDescription?.toString()?.takeIf { it.isNotBlank() && it != rawLabel }
                    ?: account?.address?.schemeSpecificPart?.takeIf { it.isNotBlank() }
                    ?: "Mobile Network • Slot $index"

                val formattedTitle = if (rawLabel.startsWith("SIM", ignoreCase = true)) {
                    rawLabel
                } else {
                    "SIM $index — $rawLabel"
                }

                result.add(
                    SimAccountInfo(
                        handle = handle,
                        label = formattedTitle,
                        subLabel = desc,
                        slotIndex = index
                    )
                )
                index++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query SIM accounts", e)
        }
        return result
    }

    fun isPhoneAccountEnabled(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = getPhoneAccountHandle(context)
            val account = telecomManager.getPhoneAccount(handle)
            account?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    fun isCallRedirectionRoleHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) == true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    /** Beta: ডিফল্ট ডায়ালার অবস্থা (সেটিংস টাইল ও অ্যাপ খোলার পপ-আপ একই উৎস থেকে পড়ে) */
    val defaultDialerHeld = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** রোল এখন আছে কি না আবার পড়ে ফ্লো আপডেট করে */
    fun refreshDefaultDialer(context: Context): Boolean {
        val held = isDefaultDialerRoleHeld(context)
        defaultDialerHeld.value = held
        return held
    }

    /** Beta: এই অ্যাপ ফোনের ডিফল্ট ডায়ালার (ROLE_DIALER) কি না */
    fun isDefaultDialerRoleHeld(context: Context): Boolean {
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Beta: ডিফল্ট ডায়ালার রোল চাওয়ার ইন্টেন্ট (না পাওয়া গেলে null) */
    fun createDefaultDialerRoleIntent(context: Context): Intent? {
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dialer role request intent", e)
            null
        }
    }

    fun isDialerIntegrated(context: Context): Boolean {
        return isCallRedirectionRoleHeld(context) || isPhoneAccountEnabled(context)
    }

    fun createCallRedirectionRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION)) {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create role request intent", e)
            }
        }
        return null
    }

    fun getNativeCallRoutingMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ROUTING_MODE, MODE_ASK_EVERY_TIME)
    }

    fun setNativeCallRoutingMode(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_ROUTING_MODE, mode).apply()
    }

    fun isAutoRecordEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RECORD, true)
    }

    fun setAutoRecordEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun createOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun openPhoneAccountSettings(context: Context) {
        registerPhoneAccount(context)
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.android.server.telecom",
                    "com.android.server.telecom.settings.EnableAccountPreferenceActivity"
                )
            },
            Intent(TelecomManager.ACTION_CONFIGURE_PHONE_ACCOUNT).apply {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getPhoneAccountHandle(context))
            },
            Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS),
            Intent().apply {
                component = ComponentName(
                    "com.android.phone",
                    "com.android.phone.settings.PhoneAccountSettingsActivity"
                )
            }
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            context,
            "Calling Accounts menu is hidden on this device. Please use Call Redirection & Overlay above.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun placeSimCallWithHandle(context: Context, number: String, simHandle: PhoneAccountHandle?) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null)
            val extras = Bundle().apply {
                if (simHandle != null) {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle)
                }
            }
            // আমাদের নিজের বাছাই করা SIM কলে রিডিরেক্ট পপ-আপ দ্বিতীয়বার আসবে না
            SipCallRedirectionService.bypassNextNumber = number
            SipCallRedirectionService.bypassUntilMs = System.currentTimeMillis() + 8000L
            telecomManager.placeCall(uri, extras)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place SIM call with handle", e)
        }
    }

    fun reportIncomingCall(context: Context, callerAddress: String) {
        // Show top incoming call popup banner first; only open full-screen InCallScreen when answered!
        NotificationHelper.showIncomingCallNotification(context, callerAddress)
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val locked = km?.isKeyguardLocked == true || pm?.isInteractive == false
        if (!locked && NativeDialerOverlay.canShowOverlay(context)) {
            NativeDialerOverlay.showIncomingCallOverlay(context, callerAddress)
        } else {
            // লক/স্ক্রিন বন্ধ: ওভারলে কীগার্ডের উপরে দেখানো যায় না, তাই পূর্ণ-স্ক্রিন কল পেজ (Answer/Decline সহ)
            IncomingUiRequest.request()
            try {
                val callIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("incoming_call", true)
                    putExtra("caller_address", callerAddress)
                }
                context.startActivity(callIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not auto-launch MainActivity on incoming call", e)
            }
        }
    }

    fun placeSipCallFallback(context: Context, number: String) {
        val engine = com.noxsip.app.sip.SipEngine.getInstance(context.applicationContext)
        if (engine.makeCall(number)) {
            if (NativeDialerOverlay.canShowOverlay(context.applicationContext)) {
                NativeDialerOverlay.openAppCallScreen(context.applicationContext, number = number)
            } else {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("from_native_dialer", true)
                }
                context.startActivity(intent)
            }
        }
    }

    fun placeOutgoingCall(context: Context, destination: String): Boolean {
        return false
    }
}
