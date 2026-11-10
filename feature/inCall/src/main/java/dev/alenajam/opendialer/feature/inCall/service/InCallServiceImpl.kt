package dev.alenajam.opendialer.feature.inCall.service

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Native Telecom entry point. When the owner has enabled AI and configured a Vapi
 * phone number, TWIGA can attempt to add that AI number as a second cellular call
 * and merge both legs using the carrier's conference-call service.
 *
 * This does not capture protected SIM audio. It relies on carrier conference support.
 */
@AndroidEntryPoint
class InCallServiceImpl : InCallService() {
    private val callerIdCallbacks = mutableMapOf<Call, Call.Callback>()
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private var customerCall: Call? = null
    private var aiCall: Call? = null
    private var bridgeRequested = false
    private var bridgeMergeInFlight = false
    private val autoAnswerRequested = mutableSetOf<Call>()
    private var bridgeMerged = false
    private var bridgeTimeout: Runnable? = null

    @Inject
    lateinit var callHandler: CallsHandler

    @Inject
    lateinit var telecomAdapter: TelecomAdapter

    @Inject
    lateinit var notificationManager: CallNotificationManager

    @Inject
    lateinit var hardwareManager: CallHardwareManager

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        reportCallerId(call.details.handle?.schemeSpecificPart.orEmpty())
        val callback = object : Call.Callback() {
            override fun onDetailsChanged(call: Call, details: Call.Details) {
                reportCallerId(details.handle?.schemeSpecificPart.orEmpty())
                observeAiLeg(call)
            }

            override fun onStateChanged(call: Call, state: Int) {
                maybeAutoAnswer(call)
                observeAiLeg(call)
            }

            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) {
                tryMergeAiLeg()
            }
        }
        callerIdCallbacks[call] = callback
        call.registerCallback(callback)
        callHandler.addCall(call, this)
        maybeAutoAnswer(call)
        observeAiLeg(call)
    }

    private fun reportCallerId(number: String) {
        if (number.isBlank()) return
        sendBroadcast(Intent("dev.alenajam.opendialer.TELECOM_CALL_DETAILS").apply {
            setClassName(packageName, "dev.alenajam.opendialer.remote.TelecomCallDetailsReceiver")
            putExtra("number", number)
        })
    }

    /** Auto-answer incoming SIM calls only when the owner has enabled the AI receptionist. */
    private fun maybeAutoAnswer(call: Call) {
        if (call.state != Call.STATE_RINGING) return
        val prefs = getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_answer", false)) return
        val aiNumber = prefs.getString("ai_line", "").orEmpty().trim()
        val caller = call.details.handle?.schemeSpecificPart.orEmpty()
        if (aiNumber.isNotBlank() && samePhoneNumber(caller, aiNumber)) return
        if (!autoAnswerRequested.add(call)) return
        try {
            prefs.edit().putBoolean("bridge_next_call", true).putString("ai_bridge_status", "answering_customer").apply()
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (error: Exception) {
            autoAnswerRequested.remove(call)
            prefs.edit().putBoolean("bridge_next_call", false).putString("ai_bridge_status", "auto_answer_failed").apply()
        }
    }

    /**
     * If this is the configured AI number, remember it as the AI leg. Otherwise,
     * when a customer call becomes active, dial the configured AI number once.
     */
    @SuppressLint("MissingPermission")
    private fun observeAiLeg(call: Call) {
        val prefs = getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
        if (!prefs.getBoolean("ai", false)) return
        val aiNumber = prefs.getString("ai_line", "").orEmpty().trim()
        if (aiNumber.isBlank()) return

        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        if (samePhoneNumber(number, aiNumber)) {
            aiCall = call
            tryMergeAiLeg()
            return
        }

        val isOutgoing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        val emergency = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)
                .isEmergencyNumber(number)
        }.getOrDefault(false)
        if (emergency) return
        val shouldBridge = prefs.getBoolean("bridge_next_call", false) ||
            (isOutgoing && prefs.getBoolean("auto_answer", false))
        if (!shouldBridge) return
        if (call.state == Call.STATE_ACTIVE && customerCall == null && !bridgeRequested && !bridgeMerged) {
            prefs.edit().putBoolean("bridge_next_call", false).apply()
            customerCall = call
            bridgeRequested = true
            prefs.edit().putString("ai_bridge_status", "calling_ai").apply()
            try {
                val telecom = getSystemService(TelecomManager::class.java)
                // Use the same SIM/account as the customer leg; conference merging across
                // different SIM subscriptions is not supported by most carrier stacks.
                val extras = Bundle()
                customerCall?.details?.accountHandle?.let { account ->
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                }
                telecom.placeCall(Uri.fromParts("tel", aiNumber, null), extras)
                scheduleBridgeFallback()
            } catch (error: Exception) {
                prefs.edit().putString("ai_bridge_status", "failed_to_call_ai").apply()
                bridgeRequested = false
                customerCall = null
            }
        }
    }

    private fun tryMergeAiLeg() {
        val customer = customerCall ?: return
        val ai = aiCall ?: return
        val customerReady = customer.state == Call.STATE_ACTIVE || customer.state == Call.STATE_HOLDING
        if (bridgeMerged || !customerReady || ai.state != Call.STATE_ACTIVE) return

        val customerCanMerge = customer.conferenceableCalls.contains(ai)
        val aiCanMerge = ai.conferenceableCalls.contains(customer)
        if (!customerCanMerge && !aiCanMerge) return

        runCatching {
            bridgeMergeInFlight = true
            if (customerCanMerge) customer.conference(ai) else ai.conference(customer)
            getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
                .edit().putString("ai_bridge_status", "merge_requested").apply()
            // Telecom creates a conference parent asynchronously. Verify it before
            // treating the bridge as live; otherwise disconnect the AI leg safely.
            bridgeTimeout?.let(bridgeHandler::removeCallbacks)
            val verify = Runnable {
                val merged = calls.any { parent ->
                    parent.children.contains(customer) && parent.children.contains(ai)
                }
                bridgeMergeInFlight = false
                if (merged) {
                    bridgeMerged = true
                    getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
                        .edit().putString("ai_bridge_status", "connected").apply()
                } else {
                    failBridge("Carrier did not merge the two calls")
                }
            }
            bridgeTimeout = verify
            bridgeHandler.postDelayed(verify, 2500L)
        }.onFailure {
            failBridge(it.message ?: "Conference merge failed")
        }
    }

    private fun scheduleBridgeFallback() {
        bridgeTimeout?.let(bridgeHandler::removeCallbacks)
        val timeout = Runnable {
            if (!bridgeMerged) failBridge("AI call did not join the customer call")
        }
        bridgeTimeout = timeout
        bridgeHandler.postDelayed(timeout, 25000L)
    }

    private fun failBridge(reason: String) {
        if (bridgeMerged) return
        bridgeTimeout?.let(bridgeHandler::removeCallbacks)
        bridgeTimeout = null
        runCatching {
            aiCall?.takeIf { it.state != Call.STATE_DISCONNECTED }?.disconnect()
        }
        // Fail open to the normal cellular conversation: disconnect only the AI leg.
        // Never drop the customer's original SIM call just because the AI number,
        // carrier conference service or network is unavailable.
        getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
            .edit().putBoolean("bridge_next_call", false).apply()
        getSharedPreferences("twiga_remote_desk", MODE_PRIVATE)
            .edit().putString("ai_bridge_status", "AI unavailable; normal call continues: " + reason.take(100)).apply()
        customerCall = null
        aiCall = null
        bridgeRequested = false
        bridgeMergeInFlight = false
    }

    private fun samePhoneNumber(a: String, b: String): Boolean {
        val left = a.filter { it.isDigit() }
        val right = b.filter { it.isDigit() }
        return left.isNotBlank() && right.isNotBlank() &&
            (left == right || left.takeLast(9) == right.takeLast(9))
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callerIdCallbacks.remove(call)?.let(call::unregisterCallback)
        autoAnswerRequested.remove(call)
        if (!bridgeMerged && call != customerCall && call != aiCall) {
            getSharedPreferences("twiga_remote_desk", MODE_PRIVATE).edit()
                .putBoolean("bridge_next_call", false).apply()
        }
        callHandler.removeCall(call)
        if (!bridgeMerged && !bridgeMergeInFlight && (call == customerCall || call == aiCall)) {
            failBridge("One of the calls ended before conference was established")
        } else if (bridgeMerged && calls.isEmpty()) {
            bridgeTimeout?.let(bridgeHandler::removeCallbacks)
            bridgeTimeout = null
            customerCall = null
            aiCall = null
            bridgeRequested = false
            bridgeMerged = false
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        telecomAdapter.onLegacyAudioStateChanged(audioState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callHandler.updateCallAudioState(audioState)
        }
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        telecomAdapter.onCallEndpointChanged(callEndpoint)
        callHandler.updateAvailableAudioRoutes(
            TelecomAdapter.toLegacyRoute(callEndpoint.endpointType),
            telecomAdapter.availableAudioRoutes()
        )
    }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        telecomAdapter.onAvailableCallEndpointsChanged(availableEndpoints)
        callHandler.updateAvailableAudioRoutes(
            telecomAdapter.availableAudioRoutes().firstOrNull { it.isSelected }?.type
                ?: CallAudioState.ROUTE_EARPIECE,
            telecomAdapter.availableAudioRoutes()
        )
    }

    @RequiresApi(34)
    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        telecomAdapter.onMuteStateChanged(isMuted)
        callHandler.updateMuteState(isMuted)
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        super.onCanAddCallChanged(canAddCall)
        callHandler.updateCanAddCall(canAddCall)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        callHandler.attemptStartActivity()
    }

    override fun onBind(intent: Intent): IBinder? {
        callHandler.setup(this, applicationContext)
        telecomAdapter.attach(this)
        notificationManager.attach(this)
        hardwareManager.attach()
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        bridgeTimeout?.let(bridgeHandler::removeCallbacks)
        callHandler.tearDown()
        telecomAdapter.detach(this)
        notificationManager.detach()
        hardwareManager.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridgeTimeout?.let(bridgeHandler::removeCallbacks)
        super.onDestroy()
    }
}
