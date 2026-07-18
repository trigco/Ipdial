package com.ipdial.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.ipdial.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.*

/**
 * PJSIP engine singleton.
 * Manages the Endpoint lifecycle, account registration, and call sessions.
 */
object SipEngine {

    private const val TAG = "SipEngine"

    @Volatile
    private var endpoint: Endpoint? = null
    private var isLibraryLoaded = false
    private val accountMap = mutableMapOf<String, PjAccount>()   // accountId -> PjAccount
    private val accountConfigs = mutableMapOf<String, SipAccount>() // accountId -> SipAccount configuration
    private val callMap = mutableMapOf<Int, PjCall>()             // callId -> PjCall
    private val registeredThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private var udpTransportId: Int = -1
    private var tcpTransportId: Int = -1
    private var tlsTransportId: Int = -1

    private lateinit var audioManager: AudioManager

    private val initLock = Any()
    private var initCallCount = 0

    private val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    private val _registrationEvents = MutableSharedFlow<Pair<String, RegStatus>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val registrationEvents: SharedFlow<Pair<String, RegStatus>> = _registrationEvents.asSharedFlow()

    var onIncomingCall: ((CallSession) -> Unit)? = null

    private var recorder: AudioMediaRecorder? = null
    private var logWriter: LogWriter? = null

    // Volume Boost Factor (250%)
    private const val VOLUME_BOOST_FACTOR = 2.5f

    private fun log(message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        com.ipdial.util.SipLogger.log(TAG, message)
    }

    private fun registerCurrentThread() {
        val ep = endpoint ?: return
        val threadId = @Suppress("DEPRECATION") Thread.currentThread().id
        if (registeredThreads.contains(threadId)) {
            return
        }
        try {
            if (!ep.libIsThreadRegistered()) {
                val threadName = Thread.currentThread().name ?: "SipEngineThread"
                ep.libRegisterThread(threadName)
            }
            registeredThreads.add(threadId)
        } catch (e: Throwable) {
            log("Failed to register thread: ${e.message}", true)
        }
    }

    fun init(context: Context) {
        initCallCount++
        val callId = initCallCount
        @Suppress("DEPRECATION")
        val threadInfo = "${Thread.currentThread().name}[${Thread.currentThread().id}]"
        log("init() called (#$callId from $threadInfo)")

        if (endpoint != null) {
            log("init() skipped (#$callId) — endpoint already set")
            return
        }

        synchronized(initLock) {
            if (endpoint != null) {
                log("init() skipped in synchronized block (#$callId) — endpoint already set")
                return
            }

            try {
                if (!isLibraryLoaded) {
                    try {
                        try {
                            System.loadLibrary("c++_shared")
                        } catch (e: Throwable) {
                            log("#$callId: Failed to load c++_shared: ${e.message}", true)
                        }
                        System.loadLibrary("pjsua2")
                        isLibraryLoaded = true
                        log("#$callId: Native library pjsua2 loaded")
                    } catch (e: Throwable) {
                        log("#$callId: Failed to load pjsua2: ${e.message}", true)
                    }
                }

                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                log("#$callId: Creating PJSIP Endpoint...")

                val ep = try {
                    Endpoint()
                } catch (e: Throwable) {
                    log("#$callId: CRITICAL: Failed to create Endpoint instance: ${e.message}", true)
                    return
                }

                try {
                    ep.libCreate()
                    log("#$callId: PJSIP libCreate() successful")
                } catch (e: Throwable) {
                    log("#$callId: CRITICAL: PJSIP libCreate() failed: ${e.message}", true)
                    return
                }

                endpoint = ep

                log("#$callId: Thread already registered (pj_init in Endpoint constructor)")

                val writer = object : LogWriter() {
                    override fun write(entry: LogEntry) {
                        val msg = entry.msg
                        if (!msg.isNullOrBlank()) {
                            val trimmed = msg.trim()
                            com.ipdial.util.SipLogger.log("PJSIP", trimmed)
                            Log.d("PJSIP", trimmed)
                        }
                    }
                }
                this.logWriter = writer

                ep.apply {
                    val epCfg = EpConfig().apply {
                        logConfig.level = 5
                        logConfig.consoleLevel = 5
                        logConfig.writer = writer

                        medConfig.apply {
                            // 1. Modern WebRTC Echo Cancellation
                            ecOptions = 3 // PJMEDIA_ECHO_WEBRTC
                            ecTailLen = 250 // Higher tail length
                            
                            // 2. Voice Activity Detection (VAD)
                            noVad = false // False means VAD is ENABLED
                            
                            // 3. Prevent Crash: Let device pick safe hardware clock rate
                            clockRate = 0
                            sndClockRate = 0
                            
                            // 4. Maximum encoding quality
                            quality = 10 
                        }
                        uaConfig.apply {
                            userAgent = "IPDial/1.1 (HD Voice)"
                            maxCalls = 4
                            stunServer.add("stun.l.google.com:19302")
                        }
                    }
                    libInit(epCfg)

                    val sipTpCfg = TransportConfig()
                    sipTpCfg.port = 0
                    try {
                        udpTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTpCfg)
                    } catch (e: Throwable) { log("#$callId: Failed to create UDP transport: ${e.message}", true) }

                    val tcpTpCfg = TransportConfig()
                    tcpTpCfg.port = 0
                    try {
                        tcpTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpTpCfg)
                    } catch (e: Throwable) { log("#$callId: Failed to create TCP transport: ${e.message}", true) }

                    val tlsTpCfg = TransportConfig()
                    tlsTpCfg.tlsConfig.verifyServer = true
                    tlsTpCfg.tlsConfig.verifyClient = false
                    try {
                        tlsTransportId = transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsTpCfg)
                    } catch (e: Throwable) { log("#$callId: Failed to create TLS transport: ${e.message}", true) }

                    libStart()
                    log("#$callId: PJSIP started successfully with HD Audio Configs")

                    endpoint = ep
                }
            } catch (e: Throwable) {
                log("#$callId: PJSIP init failed: ${e.message}", true)
            }
        }
    }

    fun addAccount(account: SipAccount) {
        registerCurrentThread()
        try {
            val existingConfig = accountConfigs[account.id]
            if (existingConfig != null) {
                val hasChanged = existingConfig.username != account.username ||
                        existingConfig.password != account.password ||
                        existingConfig.domain != account.domain ||
                        existingConfig.proxy != account.proxy ||
                        existingConfig.port != account.port ||
                        existingConfig.transport != account.transport ||
                        existingConfig.codec != account.codec ||
                        existingConfig.ecEnabled != account.ecEnabled ||
                        existingConfig.nsEnabled != account.nsEnabled ||
                        existingConfig.agcEnabled != account.agcEnabled

                if (!hasChanged) {
                    log("Account ${account.id} configuration unchanged, triggering re-registration")
                    reconnectAccount(account.id)
                    return
                }
            }

            accountMap[account.id]?.let { removeAccount(account.id) }

            val acfg = AccountConfig().apply {
                idUri = "sip:${account.username}@${account.domain}"

                regConfig.registrarUri = if (account.port != null && account.port > 0) {
                    "sip:${account.domain}:${account.port}"
                } else {
                    "sip:${account.domain}"
                }

                regConfig.timeoutSec = 120
                regConfig.retryIntervalSec = 30

                val cred = AuthCredInfo("digest", "*", account.username, 0, account.password)
                sipConfig.authCreds.add(cred)

                if (account.proxy.isNotBlank()) {
                    sipConfig.proxies.add("sip:${account.proxy}")
                }

                sipConfig.transportId = when (account.transport) {
                    Transport.TCP -> tcpTransportId
                    Transport.TLS -> tlsTransportId
                    else -> udpTransportId
                }

                mediaConfig.apply {
                    srtpUse = if (account.transport == Transport.TLS)
                        pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                    else
                        pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                }

                natConfig.iceEnabled = false
                natConfig.turnEnabled = false
                natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
                natConfig.contactRewriteUse = 1
                natConfig.sipOutboundUse = 0
            }

            val pjAcc = PjAccount(account.id)
            try {
                configureCodecs(account.codec, account.ecEnabled, account.nsEnabled, account.agcEnabled)
                
                pjAcc.create(acfg)
                accountMap[account.id] = pjAcc
                accountConfigs[account.id] = account
                log("Account added successfully: ${account.id} (${account.username})")
            } catch (e: Throwable) {
                pjAcc.delete()
                throw e
            }
        } catch (e: Throwable) {
            log("addAccount failed: ${e.message}", true)
        }
    }

    fun removeAccount(accountId: String) {
        registerCurrentThread()
        try {
            accountMap[accountId]?.delete()
            accountMap.remove(accountId)
            accountConfigs.remove(accountId)
            log("Account removed: $accountId")
        } catch (e: Throwable) {
            log("removeAccount failed: ${e.message}", true)
        }
    }

    fun reconnectAccount(accountId: String) {
        registerCurrentThread()
        try {
            accountMap[accountId]?.setRegistration(true)
        } catch (e: Throwable) {
            log("reconnectAccount failed: ${e.message}", true)
        }
    }

    fun forceReconnectAll() {
        registerCurrentThread()
        try {
            val configs = accountConfigs.values.toList()
            configs.forEach { config ->
                log("Force reconnecting account: ${config.id}")
                try {
                    accountMap[config.id]?.delete()
                } catch (e: Throwable) {
                    log("Error deleting account during force reconnect: ${e.message}", true)
                }
                accountMap.remove(config.id)
                accountConfigs.remove(config.id)
            }
            configs.forEach { config ->
                addAccount(config)
            }
        } catch (e: Throwable) {
            log("forceReconnectAll failed: ${e.message}", true)
        }
    }

    fun handleIpChange() {
        registerCurrentThread()
        val ep = endpoint ?: return
        try {
            log("Calling handleIpChange...")
            val changeParam = IpChangeParam()
            ep.handleIpChange(changeParam)
            log("handleIpChange completed successfully")
        } catch (e: Throwable) {
            log("handleIpChange failed: ${e.message}", true)
        }
    }

    fun updateCallSessionName(name: String) {
        _callSession.value = _callSession.value?.copy(remoteDisplayName = name)
    }

    fun makeCall(accountId: String, destination: String): Boolean {
        registerCurrentThread()
        return try {
            val pjAcc = accountMap[accountId] ?: run {
                log("makeCall failed: accountId $accountId not found in accountMap.", true)
                return false
            }
            val destUri = formatSipUri(destination, accountId)
            log("makeCall: destination=$destination -> destUri=$destUri")
            log("making call to $destUri")
            val call = PjCall(pjAcc)

            _callSession.value = CallSession(
                callId = -1,
                accountId = accountId,
                remoteUri = destUri,
                direction = CallDirection.OUTGOING,
                state = CallState.CALLING
            )

            val prm = CallOpParam(true).apply {
                opt.audioCount = 1
                opt.videoCount = 0
            }

            try {
                call.makeCall(destUri, prm)
                val realId = call.getId()
                callMap[realId] = call
                log("call.makeCall returned successfully. assigned call ID = $realId")

                _callSession.value?.let { currentSession ->
                    if (currentSession.state != CallState.DISCONNECTED) {
                        _callSession.value = currentSession.copy(callId = realId)
                    }
                }
                true
            } catch (e: Throwable) {
                call.delete()
                _callSession.value = null
                log("call.makeCall failed: ${e.message}", true)
                false
            }
        } catch (e: Throwable) {
            log("makeCall failed: ${e.message}", true)
            false
        }
    }

    fun answerCall(callId: Int) {
        registerCurrentThread()
        callMap[callId]?.let { call ->
            try {
                val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_OK }
                call.answer(prm)
            } catch (e: Throwable) {
                log("answerCall failed: ${e.message}", true)
            }
        }
    }

    fun hangupCall(callId: Int = -1) {
        registerCurrentThread()
        val id = if (callId >= 0) callId else _callSession.value?.callId ?: return
        log("Hangup requested for callId=$id")
        val call = callMap[id]
        if (call != null) {
            try {
                val prm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
                call.hangup(prm)
            } catch (e: Throwable) {
                log("hangupCall failed: ${e.message}", true)
            }
        } else {
            log("Hangup failed: callId=$id not found in map")
            _callSession.value = null
        }
    }

    fun setMute(muted: Boolean) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try {
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            if (muted) aud.adjustTxLevel(0f) else aud.adjustTxLevel(VOLUME_BOOST_FACTOR)
                        }
                    }
                    _callSession.value = session.copy(isMuted = muted)
                } catch (e: Throwable) {
                    log("setMute failed: ${e.message}", true)
                }
            }
        }
    }

    fun setSpeaker(enabled: Boolean) {
        log("setSpeaker: $enabled")
        _callSession.value = _callSession.value?.copy(isSpeaker = enabled)
    }

    fun setCallVolume(factor: Float) {
        registerCurrentThread()
        log("Adjusting call volume (Rx level) to factor: $factor")
        _callSession.value?.let { session ->
            _callSession.value = session.copy(rxVolume = factor)
            callMap[session.callId]?.let { call ->
                try {
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            aud.adjustRxLevel(factor)
                        }
                    }
                } catch (e: Throwable) {
                    log("setCallVolume failed: ${e.message}", true)
                }
            }
        }
    }

    fun startRecording(filePath: String) {
        registerCurrentThread()
        try {
            recorder?.delete()
            recorder = AudioMediaRecorder()
            recorder?.createRecorder(filePath)

            _callSession.value?.let { session ->
                callMap[session.callId]?.let { call ->
                    val ci = call.info
                    for (i in 0 until ci.media.size) {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val aud = AudioMedia.typecastFromMedia(call.getMedia(mi.index.toLong()))
                            aud.startTransmit(recorder)
                            endpoint?.audDevManager()?.captureDevMedia?.startTransmit(recorder)
                        }
                    }
                }
                _callSession.value = session.copy(isRecording = true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed: ${e.message}")
        }
    }

    fun stopRecording() {
        registerCurrentThread()
        try {
            recorder?.let {
                it.delete()
            }
            recorder = null
            _callSession.value = _callSession.value?.copy(isRecording = false)
        } catch (e: Throwable) {
            log("stopRecording failed: ${e.message}", true)
        }
    }

    fun sendDtmf(digit: Char) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try { call.dialDtmf(digit.toString()) } catch (e: Throwable) {
                    log("sendDtmf failed: ${e.message}", true)
                }
            }
        }
    }

    fun holdCall(onHold: Boolean) {
        registerCurrentThread()
        _callSession.value?.let { session ->
            callMap[session.callId]?.let { call ->
                try {
                    val prm = CallOpParam()
                    if (onHold) call.setHold(prm) else call.reinvite(prm)
                    _callSession.value = session.copy(isOnHold = onHold)
                } catch (e: Throwable) {
                    log("holdCall failed: ${e.message}", true)
                }
            }
        }
    }

    private fun configureCodecs(preferred: PreferredCodec, ecEnabled: Boolean, nsEnabled: Boolean, agcEnabled: Boolean) {
        val ep = endpoint ?: return
        try {
            val codecs = ep.codecEnum2()
            
            for (i in 0 until codecs.size) {
                val codec = codecs.get(i)
                val codecId = codec.codecId
                val name = codecId.lowercase()

                val priority: Short = when (preferred) {
                    PreferredCodec.AUTO -> {
                        when {
                            name.contains("g729") -> 255 
                            name.contains("opus") -> 200
                            name.contains("pcma") -> 150
                            name.contains("pcmu") -> 140
                            else -> 0
                        }
                    }
                    else -> {
                        val targetKeyword = when (preferred) {
                            PreferredCodec.G729 -> "g729"
                            PreferredCodec.OPUS -> "opus"
                            PreferredCodec.G722 -> "g722"
                            PreferredCodec.G711A -> "pcma"
                            PreferredCodec.G711U -> "pcmu"
                            else -> ""
                        }
                        when {
                            name.contains(targetKeyword) -> 255
                            name.contains("g729") -> 100 
                            else -> 0
                        }
                    }
                }
                
                ep.codecSetPriority(codecId, priority)
            }

            try {
                val audDevManager = ep.audDevManager()
                if (audDevManager != null) {
                    if (ecEnabled) ep.audDevManager().setEcOptions(3, 250)
                    else ep.audDevManager().setEcOptions(0, 0)
                }
            } catch (e: Throwable) {}

        } catch (e: Throwable) {
            log("Error configuring codecs: ${e.message}", true)
        }
    }

    fun destroy() {
        try {
            registerCurrentThread()
            callMap.values.forEach { it.delete() }
            callMap.clear()
            accountMap.values.forEach { it.delete() }
            accountMap.clear()

            recorder?.delete()
            recorder = null

            endpoint?.libDestroy()
            endpoint?.delete()
            endpoint = null

            logWriter?.delete()
            logWriter = null

            registeredThreads.clear()

            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Throwable) {
            log("destroy failed: ${e.message}", true)
        }
    }

    class PjAccount(private val accountId: String) : Account() {
        override fun onRegState(prm: OnRegStateParam) {
            try {
                val ai = try { info } catch (e: Throwable) {
                    log("CRITICAL: Account $accountId info retrieval failed: ${e.message}", true)
                    return
                }

                if (ai == null) {
                    log("CRITICAL: Account $accountId info is null", true)
                    return
                }

                val status = when {
                    ai.regIsActive -> RegStatus.REGISTERED
                    ai.regStatus / 100 == 2 -> RegStatus.REGISTERED
                    ai.regStatus >= 300 -> RegStatus.ERROR
                    else -> RegStatus.UNREGISTERED
                }
                log("REG_UPDATE: Account $accountId status=$status (code=${ai.regStatus}, reason=${ai.regStatusText}, active=${ai.regIsActive})")
                _registrationEvents.tryEmit(Pair(accountId, status))
            } catch (e: Throwable) {
                log("onRegState failed for account $accountId: ${e.message}", true)
            }
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            log("onIncomingCall callback from PJSIP: callId=${prm.callId}")
            try {
                val call = PjCall(this, prm.callId)
                callMap[prm.callId] = call

                val opPrm = CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING }
                try {
                    log("Answering incoming call $${prm.callId} with RINGING")
                    call.answer(opPrm)
                } catch (e: Throwable) {
                    log("Failed to answer incoming call $${prm.callId} with RINGING: ${e.message}", true)
                    call.delete()
                    callMap.remove(prm.callId)
                    throw e
                }

                try {
                    val ci = call.info ?: run {
                        log("Call info is null for incoming call $${prm.callId}", true)
                        call.delete()
                        callMap.remove(prm.callId)
                        return
                    }

                    log("Incoming call from ${ci.remoteUri}, state=${ci.stateText}")

                    val session = CallSession(
                        callId = prm.callId,
                        accountId = accountId,
                        remoteUri = ci.remoteUri ?: "",
                        remoteDisplayName = ci.remoteContact ?: ci.remoteUri ?: "",
                        direction = CallDirection.INCOMING,
                        state = CallState.INCOMING
                    )
                    _callSession.value = session
                    
                    if (onIncomingCall == null) {
                        log("WARNING: onIncomingCall lambda is NULL in SipEngine", true)
                    }
                    onIncomingCall?.invoke(session)
                } catch (e: Throwable) {
                    log("Failed to process incoming call info: ${e.message}", true)
                    call.delete()
                    callMap.remove(prm.callId)
                }
            } catch (e: Throwable) {
                log("onIncomingCall failed: ${e.message}", true)
            }
        }
    }

    class PjCall(acct: Account, callId: Int = -1) : Call(acct, callId) {
        override fun onCallState(prm: OnCallStateParam) {
            registerCurrentThread()
            try {
                val currentCallId = try { getId() } catch (e: Throwable) {
                    log("Failed to get call ID in onCallState: ${e.message}", true)
                    return
                }

                val ci = try { info } catch (e: Throwable) {
                    log("Failed to get call info for call $currentCallId: ${e.message}", true)
                    return
                }

                if (ci == null) {
                    log("Call info is null for call $currentCallId", true)
                    return
                }

                log("Call $currentCallId state changed to ${ci.stateText} (code=${ci.lastStatusCode}, reason=${ci.lastReason})")
                val newState = when (ci.state) {
                    pjsip_inv_state.PJSIP_INV_STATE_CALLING -> CallState.CALLING
                    pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> CallState.INCOMING
                    pjsip_inv_state.PJSIP_INV_STATE_EARLY -> CallState.EARLY
                    pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> CallState.CONNECTING
                    pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> CallState.CONFIRMED
                    pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> CallState.DISCONNECTED
                    else -> CallState.IDLE
                }

                if (newState == CallState.DISCONNECTED) {
                    log("Call $currentCallId DISCONNECTED (code=${ci.lastStatusCode}, reason=${ci.lastReason})")

                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = false

                    callMap.remove(currentCallId)
                    _callSession.value = null

                    try {
                        recorder?.delete()
                        recorder = null
                    } catch (e: Throwable) {
                        log("Failed to delete recorder on disconnect: ${e.message}", true)
                    }

                    SipConnectionService.getConnection(currentCallId)?.let { conn ->
                        try {
                            conn.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.REMOTE))
                            conn.destroy()
                            SipConnectionService.removeConnection(currentCallId)
                        } catch (e: Throwable) {
                            log("Failed to disconnect telecom connection: ${e.message}", true)
                        }
                    }
                } else {
                    _callSession.value = _callSession.value?.copy(state = newState, callId = currentCallId)

                    SipConnectionService.getConnection(currentCallId)?.let { conn ->
                        try {
                            when (newState) {
                                CallState.CONFIRMED -> conn.setActive()
                                CallState.EARLY -> {
                                    // For outgoing, we play local ringback. For incoming, we already called setRinging in answer flow.
                                }
                                CallState.CONNECTING -> conn.setDialing()
                                else -> {}
                            }
                        } catch (e: Throwable) {
                            log("Failed to update telecom connection state: ${e.message}", true)
                        }
                    }
                }
            } catch (e: Throwable) {
                log("PjCall.onCallState failed: ${e.message}", true)
            }
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            registerCurrentThread()
            try {
                val ci = try { info } catch (e: Throwable) {
                    log("Failed to get call info in onCallMediaState: ${e.message}", true)
                    return
                }

                if (ci == null) {
                    log("Call info is null in onCallMediaState", true)
                    return
                }

                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }

                for (i in 0 until ci.media.size) {
                    try {
                        val mi = ci.media.get(i)
                        if (mi.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                            mi.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            val media = getMedia(mi.index.toLong())
                            if (media != null) {
                                val aud = AudioMedia.typecastFromMedia(media)

                                val currentSession = _callSession.value
                                val txLevel = if (currentSession?.isMuted == true) 0f else VOLUME_BOOST_FACTOR
                                val rxLevel = currentSession?.rxVolume ?: VOLUME_BOOST_FACTOR
                                
                                aud.adjustRxLevel(rxLevel)
                                aud.adjustTxLevel(txLevel)

                                aud.startTransmit(endpoint?.audDevManager()?.playbackDevMedia)
                                endpoint?.audDevManager()?.captureDevMedia?.startTransmit(aud)

                                recorder?.let {
                                    aud.startTransmit(it)
                                    endpoint?.audDevManager()?.captureDevMedia?.startTransmit(it)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        log("Failed to process media state for stream $i: ${e.message}", true)
                    }
                }
            } catch (e: Throwable) {
                log("onCallMediaState failed: ${e.message}", true)
            }
        }
    }

    private fun formatSipUri(destination: String, accountId: String? = null): String {
        if (destination.startsWith("sip:") && destination.contains("@")) return destination

        val cleanDestination = destination.removePrefix("sip:").substringBefore("@")
        val number = cleanDestination
        
        val targetAccountId = accountId ?: _callSession.value?.accountId
        val domain = if (targetAccountId != null) accountConfigs[targetAccountId]?.domain else null
        
        return if (!domain.isNullOrBlank()) {
            "sip:$number@$domain"
        } else {
            "sip:$number"
        }
    }
}
