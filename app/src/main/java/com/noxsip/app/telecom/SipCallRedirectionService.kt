package com.noxsip.app.telecom

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.noxsip.app.data.AccountRepository
import com.noxsip.app.sip.SipEngine
import com.noxsip.app.sip.SipRegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 10+ (API 29+) CallRedirectionService
 * Intercepts outgoing calls from the built-in phone dialer (including Vivo X200T OriginOS/FuntouchOS)
 * and displays the SIM 1 / SIM 2 / SIP Dialer chooser popup.
 */
class SipCallRedirectionService : CallRedirectionService() {

    companion object {
        private const val TAG = "SipCallRedirection"
        private const val INTERACTIVE_WINDOW_MS = 4100L

        @Volatile
        var bypassNextNumber: String? = null

        @Volatile
        var bypassUntilMs: Long = 0L

        fun normalizeDigits(input: String): String {
            return input.filter { it.isDigit() || it == '+' }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean
    ) {
        val number = Uri.decode(handle.schemeSpecificPart ?: "").trim()
        val norm = normalizeDigits(number)
        Log.i(TAG, "onPlaceCall intercepted: number=$number, norm=$norm")

        val ourHandle = TelecomHelper.getPhoneAccountHandle(applicationContext)

        // Allow calls already placed via our PhoneAccount or explicitly bypassed SIM calls
        val isBypassed = norm.isNotEmpty() &&
                norm == normalizeDigits(bypassNextNumber ?: "") &&
                System.currentTimeMillis() < bypassUntilMs

        if (initialPhoneAccount == ourHandle || isBypassed) {
            bypassNextNumber = null
            bypassUntilMs = 0L
            placeCallUnmodified()
            return
        }

        val mode = TelecomHelper.getNativeCallRoutingMode(applicationContext)
        if (mode == TelecomHelper.MODE_SIM_ONLY || number.isEmpty()) {
            placeCallUnmodified()
            return
        }

        val sipEngine = SipEngine.getInstance(applicationContext)

        when (mode) {
            TelecomHelper.MODE_ASK_EVERY_TIME -> {
                if (NativeDialerOverlay.canShowOverlay(applicationContext)) {
                    val callHandled = AtomicBoolean(false)

                    // Hold the Telecom redirection transaction open for up to 4.1s so SIM selection
                    // can proceed natively without cancelling; if user takes longer, cancel at 4.1s
                    // and place a fresh SIM call when they decide.
                    val timeoutRunnable = Runnable {
                        if (callHandled.compareAndSet(false, true)) {
                            try {
                                cancelCall()
                            } catch (e: Exception) {
                                Log.w(TAG, "cancelCall on timeout failed", e)
                            }
                        }
                    }
                    mainHandler.postDelayed(timeoutRunnable, INTERACTIVE_WINDOW_MS)

                    NativeDialerOverlay.showAccountChooser(
                        context = applicationContext,
                        number = number,
                        onSimSelected = { selectedSimHandle ->
                            mainHandler.removeCallbacks(timeoutRunnable)
                            if (callHandled.compareAndSet(false, true)) {
                                // Still inside the 4.1s Telecom window -> native redirect/unmodified!
                                try {
                                    if (selectedSimHandle != null && selectedSimHandle != initialPhoneAccount) {
                                        redirectCall(handle, selectedSimHandle, false)
                                    } else {
                                        placeCallUnmodified()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Native redirect failed, placing SIM call directly", e)
                                    bypassNextNumber = number
                                    bypassUntilMs = System.currentTimeMillis() + 8000L
                                    TelecomHelper.placeSimCallWithHandle(
                                        applicationContext,
                                        number,
                                        selectedSimHandle ?: initialPhoneAccount
                                    )
                                }
                            } else {
                                // User chose SIM after the 4.1s timeout -> place fresh SIM call
                                bypassNextNumber = number
                                bypassUntilMs = System.currentTimeMillis() + 8000L
                                TelecomHelper.placeSimCallWithHandle(
                                    applicationContext,
                                    number,
                                    selectedSimHandle ?: initialPhoneAccount
                                )
                            }
                        },
                        onSipSelected = {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            // আগে অ্যাপের কল স্ক্রিন সামনে আনি, যাতে বাতিল হওয়া SIM কলের সিস্টেম স্ক্রিন/ব্যানার ঝলকে না ওঠে
                            NativeDialerOverlay.openAppCallScreen(applicationContext, returnToPrevious = false, number = number)
                            if (callHandled.compareAndSet(false, true)) {
                                try {
                                    cancelCall()
                                } catch (_: Exception) {
                                }
                            }
                            startSipCallEnsuringRegistration(sipEngine, number)
                        },
                        onCancel = {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            if (callHandled.compareAndSet(false, true)) {
                                try {
                                    cancelCall()
                                } catch (_: Exception) {
                                }
                            }
                        },
                        lockedHandle = initialPhoneAccount
                    )
                } else {
                    // Fallback if Overlay permission hasn't been enabled yet
                    cancelCall()
                    val choiceIntent = Intent(applicationContext, CallChoiceActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        putExtra("phone_number", number)
                    }
                    try {
                        startActivity(choiceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback startActivity failed", e)
                    }
                }
            }

            TelecomHelper.MODE_ALWAYS_SIP -> {
                cancelCall()
                if (NativeDialerOverlay.canShowOverlay(applicationContext)) {
                    NativeDialerOverlay.openAppCallScreen(applicationContext, number = number)
                }
                startSipCallEnsuringRegistration(sipEngine, number)
            }

            else -> placeCallUnmodified()
        }
    }

    private fun startSipCallEnsuringRegistration(sipEngine: SipEngine, number: String) {
        serviceScope.launch {
            if (sipEngine.registrationState.value != SipRegistrationState.REGISTERED) {
                val repo = AccountRepository(applicationContext)
                val savedAccount = repo.getAccount().first()
                if (savedAccount != null) {
                    sipEngine.registerAllSaved()
                    // Wait up to 4 seconds for registration to complete
                    var waited = 0
                    while (sipEngine.registrationState.value != SipRegistrationState.REGISTERED && waited < 40) {
                        delay(100)
                        waited++
                    }
                }
            }
            sipEngine.makeCall(number)
        }
    }
}
