package com.ipdial.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ipdial.MainActivity
import com.ipdial.AppState
import com.ipdial.R
import com.ipdial.data.model.AudioDeviceMode
import com.ipdial.data.model.CallDirection
import com.ipdial.data.model.CallSession
import com.ipdial.data.model.CallState
import com.ipdial.data.model.RegStatus
import com.ipdial.data.model.PreferredCodec
import com.ipdial.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SipService : Service() {

    companion object {
        const val NOTIF_CHANNEL_SIP = "sip_service"
        const val NOTIF_CHANNEL_CALL = "incoming_call"
        const val NOTIF_ID_SERVICE = 1001
        const val NOTIF_ID_INCOMING = 1002

        const val ACTION_ANSWER = "com.ipdial.ANSWER"
        const val ACTION_DECLINE = "com.ipdial.DECLINE"
        const val ACTION_HANGUP = "com.ipdial.HANGUP"
        const val ACTION_START = "com.ipdial.START"
        const val ACTION_STOP = "com.ipdial.STOP"
        const val ACTION_TEST_CALL = "com.ipdial.TEST_CALL"
        const val ACTION_SET_AUDIO_DEVICE = "com.ipdial.SET_AUDIO_DEVICE"

        fun start(context: Context, delayStartForeground: Boolean = false) {
            val intent = Intent(context, SipService::class.java).apply {
                action = ACTION_START
                if (delayStartForeground) {
                    putExtra("delayStartForeground", true)
                }
            }
            if (delayStartForeground) {
                context.startService(intent)
            } else {
                try {
                    context.startForegroundService(intent)
                } catch (e: Throwable) {
                    Log.e("SipService", "startForegroundService failed, trying regular startService", e)
                    context.startService(intent)
                }
            }
        }

        fun showIncomingCallNotificationStatic(context: Context, callerName: String, callId: Int) {
            if (AppState.isForeground) {
                return
            }

            val fullscreenIntent = Intent(context, MainActivity::class.java).apply {
                action = "com.ipdial.ACTION_INCOMING_CALL"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val fullscreenPi = PendingIntent.getActivity(context, 0, fullscreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val answerPi = PendingIntent.getService(context, 1,
                Intent(context, SipService::class.java).apply {
                    action = ACTION_ANSWER
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val declinePi = PendingIntent.getService(context, 2,
                Intent(context, SipService::class.java).apply {
                    action = ACTION_DECLINE
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val callerPerson = androidx.core.app.Person.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

            val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_CALL)
                .setContentTitle("Incoming Call")
                .setContentText(callerName)
                .setSmallIcon(R.drawable.ic_notif_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullscreenPi, true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi))
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_INCOMING, notif)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var audioManager: AudioManager
    private lateinit var repo: AccountRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val activeConfigs = java.util.concurrent.ConcurrentHashMap<String, com.ipdial.data.model.SipAccount>()
    private var isConnected = false
    private var lastNetwork: Network? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        repo = AccountRepository(applicationContext)
        createNotificationChannels()
        TelecomHelper.registerPhoneAccount(applicationContext)
        
        SipEngine.onIncomingCall = { session -> 
            scope.launch {
                val isDnd = repo.dndEnabled.first()
                if (isDnd) {
                    SipEngine.hangupCall(session.callId)
                } else {
                    val displayName = session.remoteDisplayName
                    val cleanNum = session.remoteUri.replace("<", "").replace(">", "").removePrefix("sip:").substringBefore("@").substringBefore(";")
                    
                    val contactsRepo = com.ipdial.data.repository.ContactsRepository(applicationContext)
                    val contacts = contactsRepo.getContacts("")
                    val cleanedSessionDigits = cleanNum.filter { it.isDigit() }
                    
                    var matchedContact: com.ipdial.data.model.Contact? = null
                    if (cleanedSessionDigits.length >= 10) {
                        matchedContact = contacts.find { c ->
                            c.numbers.any { n ->
                                val cleanedContactDigits = n.filter { it.isDigit() }
                                cleanedContactDigits.length >= 10 && (cleanedSessionDigits.contains(cleanedContactDigits) || cleanedContactDigits.contains(cleanedSessionDigits))
                            }
                        }
                    }
                    
                    val finalDisplayName = matchedContact?.name ?: cleanNum.ifBlank { displayName }
                    SipEngine.updateCallSessionName(finalDisplayName)
                    
                    withContext(Dispatchers.Main) {
                        TelecomHelper.reportIncomingCall(applicationContext, session.remoteUri, finalDisplayName)
                        showIncomingCallNotification(finalDisplayName, session.callId)
                        
                        try {
                            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                                action = "com.ipdial.ACTION_INCOMING_CALL"
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            applicationContext.startActivity(intent)
                        } catch (e: Throwable) {
                            Log.e("SipService", "Failed to force start MainActivity", e)
                        }
                    }
                }
            }
        }

        startServiceForeground()

        scope.launch {
            withContext(Dispatchers.Main) {
                SipEngine.init(applicationContext)
            }
            registerAccountsFromDataStore()
            registerDefaultNetworkCallback()
        }

        observeCallState()
    }

    private fun registerDefaultNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val isInitial = (lastNetwork == null)
                    val wasOffline = !isConnected
                    val networkChanged = (lastNetwork != network)
                    
                    lastNetwork = network
                    isConnected = true
                    
                    scope.launch(Dispatchers.IO) {
                        val freshAccounts = repo.accounts.first()
                        val enabledAccounts = freshAccounts.filter { it.isEnabled }
                        if (enabledAccounts.isEmpty()) return@launch
                        
                        val hasUnregistered = enabledAccounts.any { it.regStatus != RegStatus.REGISTERED }
                        val shouldReconnect = if (!isInitial) {
                            networkChanged || wasOffline
                        } else {
                            hasUnregistered
                        }
                        
                        if (shouldReconnect) {
                            enabledAccounts.forEach { account ->
                                repo.updateRegStatus(account.id, RegStatus.REGISTERING)
                            }
                            
                            SipEngine.handleIpChange()
                            delay(1000)
                            SipEngine.forceReconnectAll()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (lastNetwork == network) {
                        isConnected = false
                        scope.launch {
                            val freshAccounts = repo.accounts.first()
                            freshAccounts.forEach {
                                repo.updateRegStatus(it.id, RegStatus.ERROR)
                            }
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            Log.e("SipService", "Failed to register network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServiceForeground()

        when (intent?.action) {
            ACTION_ANSWER -> {
                val callId = intent.getIntExtra("callId", -1)
                SipEngine.answerCall(callId)
                routeAudioToEarpiece()
                cancelIncomingNotification()
                val fullIntent = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    startActivity(fullIntent)
                } catch (e: Throwable) {}
            }
            ACTION_DECLINE -> {
                val callId = intent.getIntExtra("callId", -1)
                SipEngine.hangupCall(callId)
                cancelIncomingNotification()
            }
            ACTION_SET_AUDIO_DEVICE -> {
                val mode = intent.getStringExtra("mode") ?: AudioDeviceMode.EARPIECE.name
                when (mode) {
                    AudioDeviceMode.EARPIECE.name -> routeAudioToEarpiece()
                    AudioDeviceMode.SPEAKER.name -> routeAudioToSpeaker(true)
                    AudioDeviceMode.BLUETOOTH.name -> routeAudioToBluetooth()
                }
            }
            ACTION_HANGUP -> SipEngine.hangupCall()
            ACTION_STOP -> stopSelf()
            ACTION_TEST_CALL -> {
                val number = intent.getStringExtra("number") ?: "123"
                scope.launch {
                    try {
                        val accountsList = repo.accounts.first()
                        val acc = accountsList.firstOrNull { it.isEnabled }
                        if (acc != null) {
                            val transportSuffix = when (acc.transport) {
                                com.ipdial.data.model.Transport.TCP -> ";transport=tcp"
                                com.ipdial.data.model.Transport.TLS -> ";transport=tls"
                                else -> ""
                            }

                            val finalUri = if (number.contains("@")) {
                                val base = if (number.startsWith("sip:")) number else "sip:$number"
                                if (!base.contains("transport=") && transportSuffix.isNotEmpty()) {
                                    base + transportSuffix
                                } else {
                                    base
                                }
                            } else {
                                val num = number.removePrefix("sip:")
                                val host = if (acc.port != null && acc.port > 0 && !acc.domain.contains(":")) {
                                    "${acc.domain}:${acc.port}"
                                } else {
                                    acc.domain
                                }
                                "sip:$num@$host$transportSuffix"
                            }
                            
                            withContext(Dispatchers.Main) {
                                SipEngine.makeCall(acc.id, finalUri)
                            }
                        }
                    } catch (e: Throwable) {
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun registerAccountsFromDataStore() {
        scope.launch {
            repo.accounts.collectLatest { accounts ->
                accounts.forEach { account ->
                    if (account.isEnabled) {
                        val active = activeConfigs[account.id]
                        val hasChanged = active == null ||
                                active.username != account.username ||
                                active.password != account.password ||
                                active.domain != account.domain ||
                                active.proxy != account.proxy ||
                                active.port != account.port ||
                                active.transport != account.transport ||
                                active.codec != account.codec ||
                                active.ecEnabled != account.ecEnabled ||
                                active.nsEnabled != account.nsEnabled ||
                                active.agcEnabled != account.agcEnabled

                        if (hasChanged) {
                            activeConfigs[account.id] = account
                            SipEngine.addAccount(account)
                        }
                    } else {
                        if (activeConfigs.containsKey(account.id)) {
                            activeConfigs.remove(account.id)
                            SipEngine.removeAccount(account.id)
                        }
                        if (account.regStatus != RegStatus.UNREGISTERED) {
                            scope.launch {
                                repo.updateRegStatus(account.id, RegStatus.UNREGISTERED)
                            }
                        }
                    }
                }
            }
        }
        
        scope.launch {
            SipEngine.registrationEvents.collect { (accountId, status) ->
                repo.updateRegStatus(accountId, status)
            }
        }

        scope.launch {
            while (true) {
                delay(120_000) 
                try {
                    val accounts = repo.accounts.first()
                    accounts.forEach { account ->
                        if (account.isEnabled && account.regStatus != RegStatus.REGISTERED) {
                            SipEngine.reconnectAccount(account.id)
                        }
                    }
                } catch (e: Throwable) {
                }
            }
        }
    }

    private var lastWasConfirmed = false
    private var callStartTime = 0L
    private var lastSession: CallSession? = null
    private var ringtone: Ringtone? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isPlayingRingtone = false

    private fun playRingtone() {
        if (isPlayingRingtone || ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) return
        isPlayingRingtone = true
        scope.launch {
            try {
                val ringtoneUriStr = repo.globalRingtone.first()
                val vibrateEnabled = repo.globalVibrate.first()
                
                withContext(Dispatchers.Main) {
                    if (ringtone?.isPlaying == true || mediaPlayer?.isPlaying == true) {
                        return@withContext
                    }

                    val ringtoneUri = ringtoneUriStr?.let { Uri.parse(it) }
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    
                    try {
                        val mp = android.media.MediaPlayer()
                        mp.setDataSource(applicationContext, ringtoneUri)
                            mp.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            mp.isLooping = true
                            mp.prepare()
                            mp.start()
                            mediaPlayer = mp
                        } catch (e: Throwable) {
                            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                ringtone?.isLooping = true
                            }
                            ringtone?.play()
                        }
                    
                    if (vibrateEnabled) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
                    }
                }
            } catch (e: Throwable) {
                isPlayingRingtone = false
            }
        }
    }

    private fun stopRingtone() {
        isPlayingRingtone = false
        try {
            ringtone?.stop()
            ringtone = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
        } catch (e: Throwable) {}
    }

    private fun isBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothScoAvailableOffCall || audioManager.isBluetoothA2dpOn
        }
    }

    private fun routeAudioToDefault() {
        requestAudioFocus()
        val session = SipEngine.callSession.value ?: return
        if (session.isSpeaker) {
            routeAudioToSpeaker(true)
        } else if (isBluetoothConnected()) {
            routeAudioToBluetooth()
        } else {
            routeAudioToEarpiece()
        }
    }

    private fun observeCallState() {
        scope.launch {
            SipEngine.callSession.collect { session ->
                if (session == null) {
                    val sessionToLog = lastSession
                    if (sessionToLog != null) {
                        val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0L
                        val entry = com.ipdial.data.model.CallLogEntry(
                            accountId = sessionToLog.accountId,
                            remoteUri = sessionToLog.remoteUri,
                            remoteDisplayName = sessionToLog.remoteDisplayName,
                            direction = sessionToLog.direction,
                            timestampMs = System.currentTimeMillis(),
                            durationSeconds = duration,
                            missed = !lastWasConfirmed && sessionToLog.direction == CallDirection.INCOMING
                        )
                        // Global scope for guaranteed saving even if service scope cancels
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                com.ipdial.data.repository.CallLogRepository.getInstance(applicationContext).insert(entry)
                                Log.d("SipService", "Call history saved successfully.")
                            } catch (e: Throwable) {
                                Log.e("SipService", "Failed to save call log", e)
                            }
                        }
                    }
                    stopRingtone()
                    restoreAudio()
                    releaseWakeLock()
                    cancelIncomingNotification()
                    lastWasConfirmed = false
                    callStartTime = 0
                    lastSession = null
                } else {
                    val stateChanged = session.state != lastSession?.state
                    val speakerChanged = session.isSpeaker != lastSession?.isSpeaker
                    
                    lastSession = session
                    if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                    
                    when (session.state) {
                        CallState.INCOMING -> {
                            playRingtone()
                            acquireWakeLockForIncoming()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                            if (speakerChanged) {
                                routeAudioToSpeaker(session.isSpeaker)
                            }
                        }
                        CallState.CONFIRMED -> {
                            stopRingtone()
                            cancelIncomingNotification()
                            
                            if (stateChanged || speakerChanged) {
                                if (stateChanged) delay(300) 
                                routeAudioToDefault()
                            }
                            
                            acquireWakeLock()
                            lastWasConfirmed = true
                            if (callStartTime == 0L) callStartTime = System.currentTimeMillis()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        CallState.EARLY -> {
                            if (session.direction == CallDirection.OUTGOING && stateChanged) {
                                scope.launch {
                                    var tg: ToneGenerator? = null
                                    try {
                                        tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
                                        tg.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                                        // Wait until state is no longer EARLY
                                        while (SipEngine.callSession.value?.state == CallState.EARLY) {
                                            delay(1000)
                                        }
                                    } catch (e: Throwable) {
                                    } finally {
                                        tg?.release()
                                    }
                                }
                            }
                            if (stateChanged || speakerChanged) routeAudioToDefault()
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        CallState.CALLING, CallState.CONNECTING -> {
                            if (stateChanged || speakerChanged) {
                                routeAudioToDefault()
                            }
                            updateForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        else -> {
                            if (speakerChanged) {
                                routeAudioToDefault()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startServiceForeground() {
        val notification = buildServiceNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                }
            } catch (e: Throwable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        androidx.core.app.ServiceCompat.startForeground(
                            this,
                            NOTIF_ID_SERVICE,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } catch (ex: Throwable) {
                        try {
                            startForeground(NOTIF_ID_SERVICE, notification)
                        } catch (lastEx: Throwable) {}
                    }
                } else {
                    try {
                        startForeground(NOTIF_ID_SERVICE, notification)
                    } catch (lastEx: Throwable) {}
                }
            }
        } else {
            startForeground(NOTIF_ID_SERVICE, notification)
        }
    }

    private fun updateForegroundType(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    androidx.core.app.ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_SERVICE,
                        buildServiceNotification(),
                        type
                    )
                } else {
                    startForeground(NOTIF_ID_SERVICE, buildServiceNotification(), type)
                }
            } catch (e: Throwable) {}
        }
    }

    private fun routeAudioToEarpiece() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    fun routeAudioToSpeaker(on: Boolean) {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            if (connection != null) {
                val route = if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
                @Suppress("DEPRECATION")
                connection.setAudioRoute(route)
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        }
    }

    private fun routeAudioToBluetooth() {
        val session = SipEngine.callSession.value
        if (session != null && session.callId >= 0) {
            val connection = SipConnectionService.getConnection(session.callId)
            @Suppress("DEPRECATION")
            connection?.setAudioRoute(android.telecom.CallAudioState.ROUTE_BLUETOOTH)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val btDevice = devices.find {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (btDevice != null) {
                audioManager.setCommunicationDevice(btDevice)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        @Suppress("DEPRECATION")
        if (audioManager.isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = false
        }
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Throwable) {}
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        
        try {
            wakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "IPDial:call"
            ).apply { 
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) 
            }
        } catch (e: Throwable) {
            android.util.Log.e("SipService", "Failed to acquire proximity wake lock", e)
        }

        try {
            if (cpuWakeLock == null) {
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IPDial:cpu_call").apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L)
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("SipService", "Failed to acquire CPU wake lock", e)
        }
    }

    private fun acquireWakeLockForIncoming() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "IPDial:incoming_call_wake"
            )
            wl.acquire(10000L)
        } catch (e: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            cpuWakeLock?.let { if (it.isHeld) it.release() }
            cpuWakeLock = null
        } catch (e: Throwable) {}
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_SIP, "SIP Service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Background SIP registration"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_CALL, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming VoIP call alerts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
            }
        )
    }

    private fun buildServiceNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_SIP)
            .setContentTitle("IPDial")
            .setContentText("Ready to receive calls")
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentIntent(intent)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun showIncomingCallNotification(callerName: String, callId: Int) {
        showIncomingCallNotificationStatic(this, callerName, callId)
    }

    private fun cancelIncomingNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_INCOMING)
    }

    override fun onDestroy() {
        releaseWakeLock()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SipEngine.destroy()
            } catch (_: Throwable) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
