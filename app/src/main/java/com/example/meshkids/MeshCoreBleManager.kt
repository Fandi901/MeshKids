package com.example.meshkids

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

enum class DeliveryStatus {
    SENDING,
    DELIVERED,
    FAILED,
    GROUP_SENT,
    GROUP_WAITING_REPEATER,
    GROUP_REPEATER_HEARD
}

data class MeshContact(
    val pubKeyPrefixHex: String,
    val pubKeyPrefixBytes: ByteArray,
    val originalName: String,
    val aliasName: String,
    val isAllowedForKid: Boolean = false
) {
    val displayName: String
        get() = aliasName.trim().ifBlank { originalName.trim() }
}

data class MeshChannel(
    val index: Int,
    val originalName: String,
    val aliasName: String,
    val regionScope: String = "",
    val isEncryptedPskSet: Boolean = false,
    val isAllowedForKid: Boolean = false
) {
    val displayName: String
        get() = aliasName.trim().ifBlank { originalName.trim() }
}

enum class ChatTargetType {
    CONTACT,
    CHANNEL
}

data class ChatTarget(
    val type: ChatTargetType,
    val id: String,
    val title: String,
    val contact: MeshContact? = null,
    val channel: MeshChannel? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val targetId: String,
    val senderName: String? = null,
    val text: String,
    val isFromMe: Boolean,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENDING,
    val expectedAckTag: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@SuppressLint("MissingPermission")
class MeshCoreBleManager(private val context: Context) {

    companion object {
        const val RETRY_INTERVAL_MS: Long = 120_000L
        const val MAX_SEND_ATTEMPTS: Int = 3
        const val GROUP_REPEATER_WAIT_MS: Long = 30_000L
        const val CHANNEL_SEND_MIN_SPACING_MS: Long = 2_500L
        const val SCOPE_TO_MSG_GAP_MS: Long = 220L
        const val RECONNECT_INTERVAL_MS: Long = 4_000L
        const val APP_VERSION: String = "1.6"
        const val MAX_MESSAGES_PER_TARGET: Int = 20
        val DEFAULT_QUICK_REPLIES: List<String> = listOf(
            "Hallo!",
            "Bin gut angekommen!",
            "Ich komme jetzt nach Hause",
            "Kannst du mich abholen?"
        )

        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_APP_START: Byte = 0x01
        const val CMD_SEND_TXT_MSG: Byte = 0x02
        const val CMD_SEND_CHANNEL_TXT_MSG: Byte = 0x03
        const val CMD_GET_CONTACTS: Byte = 0x04
        const val CMD_SET_DEVICE_TIME: Byte = 0x06
        const val CMD_SEND_SELF_ADVERT: Byte = 0x07
        const val CMD_SET_ADVERT_NAME: Byte = 0x08
        const val CMD_SYNC_NEXT_MESSAGE: Byte = 0x0A
        const val CMD_GET_CHANNEL: Byte = 0x1F
        const val CMD_SET_FLOOD_SCOPE: Byte = 0x36

        const val RESP_CONTACT: Byte = 0x03
        const val RESP_CONTACT_MSG_RECV: Byte = 0x07
        const val RESP_CHANNEL_MSG_RECV: Byte = 0x08
        const val RESP_CONTACT_MSG_RECV_V3: Byte = 0x10
        const val RESP_CHANNEL_MSG_RECV_V3: Byte = 0x11
        const val RESP_CHANNEL_INFO: Byte = 0x12

        const val PUSH_SEND_CONFIRMED: Byte = 0x82.toByte()
        const val PUSH_MSG_WAITING: Byte = 0x83.toByte()
        const val PUSH_LOG_RX_DATA: Byte = 0x88.toByte()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSendJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val pendingGroupEchoes = java.util.concurrent.ConcurrentHashMap<String, PendingGroupEcho>()
    @Volatile
    private var lastChannelTxScheduledAtMs: Long = 0L
    private var reconnectJob: Job? = null

    private data class PendingGroupEcho(
        val msgId: String,
        val channelIndex: Int,
        val text: String,
        val createdAtMs: Long
    )
    private var manualDisconnect = false
    private var isConnecting = false
    private var connectAttemptStartedAtMs = 0L

    private val commandQueue = ConcurrentLinkedDeque<ByteArray>()
    @Volatile
    private var lastWrittenCommand: ByteArray? = null
    @Volatile
    private var lastWriteRetried: Boolean = false
    private var isWriting = false
    private var awaitingCommandAck = false
    private var ackTimeoutJob: Job? = null

    private val prefs = context.getSharedPreferences("meshkids_prefs", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow("Getrennt")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices

    private val _contacts = MutableStateFlow<List<MeshContact>>(loadCachedContacts())
    val contacts: StateFlow<List<MeshContact>> = _contacts

    private fun saveCachedContacts(list: List<MeshContact>) {
        try {
            val arr = JSONArray()
            for (c in list) {
                val obj = JSONObject().apply {
                    put("prefixHex", c.pubKeyPrefixHex)
                    put("prefixBytesHex", c.pubKeyPrefixBytes.joinToString("") { "%02x".format(it) })
                    put("originalName", c.originalName)
                    put("aliasName", c.aliasName)
                    put("isAllowedForKid", c.isAllowedForKid)
                }
                arr.put(obj)
            }
            prefs.edit().putString("cached_contacts_json", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadCachedContacts(): List<MeshContact> {
        val rawJson = prefs.getString("cached_contacts_json", null) ?: return emptyList()
        val allowedSet = (prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()).map { it.lowercase() }.toSet()
        return try {
            val arr = JSONArray(rawJson)
            val result = ArrayList<MeshContact>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val prefixHex = obj.optString("prefixHex", "").lowercase()
                if (prefixHex.length != 12) continue
                val bytesHex = obj.optString("prefixBytesHex", prefixHex)
                val prefixBytes = ByteArray(6) { idx ->
                    val hi = Character.digit(bytesHex.getOrElse(idx * 2) { '0' }, 16)
                    val lo = Character.digit(bytesHex.getOrElse(idx * 2 + 1) { '0' }, 16)
                    (((if (hi >= 0) hi else 0) shl 4) or (if (lo >= 0) lo else 0)).toByte()
                }
                val origName = obj.optString("originalName", "Node $prefixHex")
                val savedAlias = prefs.getString("contact_alias_$prefixHex", null)
                    ?: obj.optString("aliasName", "")
                val isAllowed = allowedSet.contains(prefixHex) || obj.optBoolean("isAllowedForKid", false)
                result.add(MeshContact(prefixHex, prefixBytes, origName, savedAlias, isAllowed))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val _manualGroupAliases = MutableStateFlow<Map<String, String>>(loadManualGroupAliases())
    val manualGroupAliases: StateFlow<Map<String, String>> = _manualGroupAliases

    private val _channels = MutableStateFlow<List<MeshChannel>>(loadCachedDeviceChannels())
    val channels: StateFlow<List<MeshChannel>> = _channels

    private val _messages = MutableStateFlow<List<ChatMessage>>(loadPersistedMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _quickReplies = MutableStateFlow<List<String>>(loadQuickReplies())
    val quickReplies: StateFlow<List<String>> = _quickReplies

    private val _unreadTargetIds = MutableStateFlow<Set<String>>(loadPersistedUnreadTargetIds())
    val unreadTargetIds: StateFlow<Set<String>> = _unreadTargetIds

    private val _incomingAlertCounter = MutableStateFlow(0L)
    val incomingAlertCounter: StateFlow<Long> = _incomingAlertCounter

    private var activeMediaPlayer: MediaPlayer? = null

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && !manualDisconnect) {
                    isConnecting = false
                    scheduleReconnect(immediate = true)
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    _isConnected.value = false
                    isConnecting = false
                    _connectionState.value = "Bluetooth aus – warte..."
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(btStateReceiver, filter)
        }
        startReconnectWatchdog()
    }

    private fun loadQuickReplies(): List<String> {
        val rawJson = prefs.getString("quick_replies_json", null)
        if (rawJson.isNullOrBlank()) return DEFAULT_QUICK_REPLIES
        return try {
            val arr = JSONArray(rawJson)
            val list = (0 until arr.length()).mapNotNull { idx ->
                val item = sanitizeText(arr.optString(idx, "")).take(60)
                if (item.isNotBlank()) item else null
            }
            if (list.isNotEmpty()) list else DEFAULT_QUICK_REPLIES
        } catch (_: Exception) {
            DEFAULT_QUICK_REPLIES
        }
    }

    fun setQuickReplies(updatedReplies: List<String>) {
        val cleaned = updatedReplies
            .map { sanitizeText(it).take(60) }
            .filter { it.isNotBlank() }
            .ifEmpty { DEFAULT_QUICK_REPLIES }
        val arr = JSONArray()
        cleaned.forEach { arr.put(it) }
        prefs.edit().putString("quick_replies_json", arr.toString()).apply()
        _quickReplies.value = cleaned
    }

    // Keep at most MAX_MESSAGES_PER_TARGET (20) messages per chat target and persist them.
    private fun trimAndPersistMessages(rawList: List<ChatMessage>): List<ChatMessage> {
        val countsByTarget = mutableMapOf<String, Int>()
        val keptReversed = ArrayList<ChatMessage>(rawList.size)
        for (i in rawList.indices.reversed()) {
            val msg = rawList[i]
            val currentCount = countsByTarget[msg.targetId] ?: 0
            if (currentCount < MAX_MESSAGES_PER_TARGET) {
                countsByTarget[msg.targetId] = currentCount + 1
                keptReversed.add(msg)
            }
        }
        val trimmed = keptReversed.asReversed()
        saveMessagesToPrefs(trimmed)
        return trimmed
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        _messages.value = trimAndPersistMessages(transform(_messages.value))
    }

    private fun saveMessagesToPrefs(list: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            for (msg in list) {
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("targetId", msg.targetId)
                    put("senderName", msg.senderName ?: JSONObject.NULL)
                    put("text", msg.text)
                    put("isFromMe", msg.isFromMe)
                    put("deliveryStatus", msg.deliveryStatus.name)
                    put("expectedAckTag", msg.expectedAckTag ?: JSONObject.NULL)
                    put("timestamp", msg.timestamp)
                }
                arr.put(obj)
            }
            prefs.edit().putString("chat_history_json", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadPersistedMessages(): List<ChatMessage> {
        val rawJson = prefs.getString("chat_history_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val parsed = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", UUID.randomUUID().toString())
                val targetId = obj.optString("targetId", "")
                val text = obj.optString("text", "")
                if (targetId.isBlank() || text.isBlank()) continue
                val senderName = if (obj.isNull("senderName")) null else obj.optString("senderName", "").ifBlank { null }
                val isFromMe = obj.optBoolean("isFromMe", false)
                val statusName = obj.optString("deliveryStatus", DeliveryStatus.DELIVERED.name)
                val status = try {
                    DeliveryStatus.valueOf(statusName)
                } catch (_: Exception) {
                    if (isFromMe) DeliveryStatus.DELIVERED else DeliveryStatus.DELIVERED
                }
                // Any in-flight SENDING message from a previous process should not stay stuck forever on restart
                val normalizedStatus = when (status) {
                    DeliveryStatus.SENDING -> DeliveryStatus.FAILED
                    DeliveryStatus.GROUP_WAITING_REPEATER -> DeliveryStatus.GROUP_SENT
                    else -> status
                }
                val ackTag = if (obj.isNull("expectedAckTag")) null else obj.optInt("expectedAckTag", 0).takeIf { it != 0 }
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                parsed.add(
                    ChatMessage(
                        id = id,
                        targetId = targetId,
                        senderName = senderName,
                        text = text,
                        isFromMe = isFromMe,
                        deliveryStatus = normalizedStatus,
                        expectedAckTag = ackTag,
                        timestamp = timestamp
                    )
                )
            }
            val countsByTarget = mutableMapOf<String, Int>()
            val keptReversed = ArrayList<ChatMessage>(parsed.size)
            for (i in parsed.indices.reversed()) {
                val msg = parsed[i]
                val currentCount = countsByTarget[msg.targetId] ?: 0
                if (currentCount < MAX_MESSAGES_PER_TARGET) {
                    countsByTarget[msg.targetId] = currentCount + 1
                    keptReversed.add(msg)
                }
            }
            keptReversed.asReversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadCachedDeviceChannels(): List<MeshChannel> {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        return (0..7).mapNotNull { idx ->
            val deviceName = prefs.getString("device_channel_name_$idx", null)
            if (deviceName.isNullOrBlank()) {
                null
            } else {
                val savedAlias = prefs.getString("channel_alias_$idx", "") ?: ""
                val savedScope = prefs.getString("channel_scope_$idx", "") ?: ""
                val hasPsk = prefs.getBoolean("device_channel_has_psk_$idx", false)
                MeshChannel(
                    index = idx,
                    originalName = deviceName,
                    aliasName = savedAlias,
                    regionScope = savedScope,
                    isEncryptedPskSet = hasPsk,
                    isAllowedForKid = allowedSet.contains(idx.toString())
                )
            }
        }
    }

    private fun loadPersistedUnreadTargetIds(): Set<String> {
        return prefs.getStringSet("unread_target_ids", emptySet())?.toSet() ?: emptySet()
    }

    private fun saveUnreadTargetIds(set: Set<String>) {
        prefs.edit().putStringSet("unread_target_ids", set.toSet()).apply()
    }

    fun markTargetRead(targetId: String) {
        if (_unreadTargetIds.value.contains(targetId)) {
            val updated = _unreadTargetIds.value - targetId
            saveUnreadTargetIds(updated)
            _unreadTargetIds.value = updated
        }
    }

    private fun markTargetUnreadAndPlaySound(targetId: String) {
        val updated = _unreadTargetIds.value + targetId
        saveUnreadTargetIds(updated)
        _unreadTargetIds.value = updated
        _incomingAlertCounter.value = _incomingAlertCounter.value + 1L
        playNewMessageNotificationSound()
    }

    // Plays notification sound strictly for newly received chat messages (never for system frames).
    // Uses MediaPlayer with fallback to RingtoneManager so playback works reliably on BLE/IO threads.
    private fun playNewMessageNotificationSound() {
        scope.launch(Dispatchers.Main) {
            try {
                activeMediaPlayer?.stop()
                activeMediaPlayer?.release()
                activeMediaPlayer = null

                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: return@launch

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context.applicationContext, uri)
                    setOnCompletionListener { player ->
                        try {
                            player.release()
                        } catch (_: Exception) {
                        }
                        if (activeMediaPlayer === player) {
                            activeMediaPlayer = null
                        }
                    }
                    prepare()
                    start()
                }
                activeMediaPlayer = mp
            } catch (_: Exception) {
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return@launch
                    RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun autoConnectIfSaved() {
        if (manualDisconnect) return
        val savedAddress = prefs.getString("saved_mac", null) ?: return
        if (_isConnected.value && bluetoothGatt != null) return
        scheduleReconnect(immediate = true)
    }

    fun scheduleReconnect(immediate: Boolean = false) {
        if (manualDisconnect) return
        if (prefs.getString("saved_mac", null) == null) return
        if (immediate && !_isConnected.value) {
            isConnecting = false
            scope.launch { attemptReconnectOnce() }
        }
        startReconnectWatchdog()
    }

    private fun startReconnectWatchdog() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (isActive) {
                delay(RECONNECT_INTERVAL_MS)
                if (manualDisconnect) continue
                val savedAddress = prefs.getString("saved_mac", null) ?: continue
                if (_isConnected.value) {
                    isConnecting = false
                    continue
                }
                if (isConnecting && (System.currentTimeMillis() - connectAttemptStartedAtMs) > 10_000L) {
                    isConnecting = false
                }
                if (!isConnecting) {
                    attemptReconnectOnce(savedAddress)
                }
            }
        }
    }

    private suspend fun attemptReconnectOnce(addressOverride: String? = null) {
        if (manualDisconnect || _isConnected.value || isConnecting) return
        val savedAddress = addressOverride ?: prefs.getString("saved_mac", null) ?: return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _connectionState.value = "Bluetooth aus – warte..."
            return
        }

        isConnecting = true
        connectAttemptStartedAtMs = System.currentTimeMillis()
        _connectionState.value = "Verbinde automatisch neu..."
        try {
            val device = adapter.getRemoteDevice(savedAddress)
            withContext(Dispatchers.Main) {
                try {
                    bluetoothGatt?.close()
                } catch (_: Exception) {
                }
                bluetoothGatt = null
                rxCharacteristic = null
                isWriting = false
                bluetoothGatt = device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        } catch (_: Exception) {
            isConnecting = false
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _discoveredDevices.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scope.launch {
            delay(8000)
            stopScan()
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (_discoveredDevices.value.none { it.address == device.address }) {
                _discoveredDevices.value = _discoveredDevices.value + device
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        stopScan()
        manualDisconnect = false
        isConnecting = true
        connectAttemptStartedAtMs = System.currentTimeMillis()
        _connectionState.value = "Verbinde..."
        prefs.edit().putString("saved_mac", device.address).apply()
        scope.launch(Dispatchers.Main) {
            try {
                bluetoothGatt?.close()
            } catch (_: Exception) {
            }
            bluetoothGatt = null
            rxCharacteristic = null
            isWriting = false
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnecting = false
                _connectionState.value = "Suche Dienste..."
                scope.launch(Dispatchers.Main) {
                    delay(200)
                    try {
                        if (!gatt.requestMtu(185)) {
                            gatt.discoverServices()
                        }
                    } catch (_: Exception) {
                        gatt.discoverServices()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                isConnecting = false
                _isConnected.value = false
                rxCharacteristic = null
                isWriting = false
                commandQueue.clear()
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                if (!manualDisconnect && prefs.getString("saved_mac", null) != null) {
                    _connectionState.value = "Verbindung unterbrochen – stelle wieder her..."
                    scheduleReconnect(immediate = false)
                } else {
                    _connectionState.value = "Getrennt"
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            val service = gatt.getService(SERVICE_UUID) ?: run {
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
            val txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)

            if (txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                } else {
                    isConnecting = false
                    _isConnected.value = true
                    _connectionState.value = "Verbunden"
                    initializeMeshCoreSession()
                }
            } else {
                isConnecting = false
                _isConnected.value = true
                _connectionState.value = "Verbunden"
                initializeMeshCoreSession()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isConnecting = false
            _isConnected.value = true
            _connectionState.value = "Verbunden"
            initializeMeshCoreSession()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val finishedCmd = lastWrittenCommand
            isWriting = false
            awaitingCommandAck = false
            ackTimeoutJob?.cancel()
            if (status != BluetoothGatt.GATT_SUCCESS && finishedCmd != null && !lastWriteRetried) {
                lastWriteRetried = true
                commandQueue.addFirst(finishedCmd)
                scope.launch {
                    delay(180L)
                    processNextCommand()
                }
                return
            }
            lastWriteRetried = false
            val postWriteDelay = if (finishedCmd != null && finishedCmd.isNotEmpty() && finishedCmd[0] == CMD_SET_FLOOD_SCOPE) {
                SCOPE_TO_MSG_GAP_MS
            } else {
                90L
            }
            scope.launch {
                delay(postWriteDelay)
                processNextCommand()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                handleIncomingFrame(characteristic.value)
            }
        }
    }

    private fun initializeMeshCoreSession() {
        val appStart = ByteBuffer.allocate(8).apply {
            put(CMD_APP_START)
            put(0x08)
            put(ByteArray(6))
        }.array()
        enqueueCommand(appStart)

        val epochSec = (System.currentTimeMillis() / 1000L).toInt()
        val timeCmd = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(CMD_SET_DEVICE_TIME)
            putInt(epochSec)
        }.array()
        enqueueCommand(timeCmd)

        syncContacts()
        syncChannelsFromDevice()
        enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
    }

    fun sendAdvertNow() {
        enqueueCommand(byteArrayOf(CMD_SEND_SELF_ADVERT, 0x01))
    }

    fun syncContacts() {
        enqueueCommand(byteArrayOf(CMD_GET_CONTACTS))
    }

    fun syncChannelsFromDevice() {
        for (idx in 0..7) {
            enqueueCommand(byteArrayOf(CMD_GET_CHANNEL, idx.toByte()))
        }
    }


    private fun loadManualGroupAliases(): Map<String, String> {
        val rawJson = prefs.getString("manual_group_aliases_json", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(rawJson)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = sanitizeText(keys.next()).take(48)
                val v = sanitizeText(obj.optString(k, "")).take(24)
                if (k.isNotBlank() && v.isNotBlank()) {
                    map[k] = v
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveManualGroupAliases(map: Map<String, String>) {
        try {
            val obj = JSONObject()
            for ((k, v) in map) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    obj.put(k, v)
                }
            }
            prefs.edit().putString("manual_group_aliases_json", obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun addOrUpdateManualGroupAlias(chatName: String, alias: String) {
        val cleanChatName = sanitizeText(chatName).take(48)
        val cleanAlias = sanitizeText(alias).take(24)
        if (cleanChatName.isBlank() || cleanAlias.isBlank()) return
        val updated = LinkedHashMap(_manualGroupAliases.value)
        updated[cleanChatName] = cleanAlias
        saveManualGroupAliases(updated)
        _manualGroupAliases.value = updated
        refreshExistingMessageSenderNames(_contacts.value, updated)
    }

    fun removeManualGroupAlias(chatName: String) {
        if (!_manualGroupAliases.value.containsKey(chatName)) return
        val updated = LinkedHashMap(_manualGroupAliases.value)
        updated.remove(chatName)
        saveManualGroupAliases(updated)
        _manualGroupAliases.value = updated
        refreshExistingMessageSenderNames(_contacts.value, updated)
    }

    fun setContactAlias(prefixHex: String, alias: String) {
        val cleanAlias = sanitizeText(alias).take(24)
        prefs.edit().putString("contact_alias_$prefixHex", cleanAlias).apply()
        val updatedContacts = _contacts.value.map {
            if (it.pubKeyPrefixHex == prefixHex) it.copy(aliasName = cleanAlias) else it
        }
        _contacts.value = updatedContacts
        saveCachedContacts(updatedContacts)
        refreshExistingMessageSenderNames(updatedContacts)
    }

    private fun refreshExistingMessageSenderNames(
        currentContacts: List<MeshContact>,
        currentManualAliases: Map<String, String> = _manualGroupAliases.value
    ) {
        updateMessages { list ->
            list.map { msg ->
                if (!msg.isFromMe && !msg.senderName.isNullOrBlank()) {
                    val mapped = resolveContactName(msg.senderName, currentContacts, currentManualAliases)
                    if (mapped != msg.senderName) msg.copy(senderName = mapped) else msg
                } else msg
            }
        }
    }

    fun setChannelAlias(channelIndex: Int, alias: String) {
        val cleanAlias = sanitizeText(alias).take(24)
        prefs.edit().putString("channel_alias_$channelIndex", cleanAlias).apply()
        _channels.value = _channels.value.map {
            if (it.index == channelIndex) it.copy(aliasName = cleanAlias) else it
        }
    }


    private fun parseHex16OrNull(input: String): ByteArray? {
        val hex = input.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 32 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        return ByteArray(16) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    private fun deriveFloodScopeKey16(regionScope: String): ByteArray {
        val rawTrimmed = sanitizeText(regionScope).trim()
        if (rawTrimmed.isEmpty()) {
            return ByteArray(16)
        }
        parseHex16OrNull(rawTrimmed)?.let { return it }

        // MeshCore TransportKeyStore::getAutoKeyFor(name) hashes "#" + lowercase region name with SHA-256
        val cleanName = rawTrimmed.removePrefix("#").trim().lowercase()
        if (cleanName.isEmpty()) {
            return ByteArray(16)
        }
        val prefixed = "#$cleanName"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(prefixed.toByteArray(Charsets.UTF_8))
            digest.copyOfRange(0, 16)
        } catch (_: Exception) {
            val out = ByteArray(16)
            val raw = prefixed.toByteArray(Charsets.UTF_8)
            System.arraycopy(raw, 0, out, 0, minOf(raw.size, 16))
            out
        }
    }

    private fun buildFloodScopePacket(regionScope: String): ByteArray {
        val key16 = deriveFloodScopeKey16(regionScope)
        // MeshCore CMD_SET_FLOOD_SCOPE (0x36) frame format: [0x36, 0x00, <16-byte TransportKey>] (18 bytes)
        val cmd = ByteArray(18)
        cmd[0] = CMD_SET_FLOOD_SCOPE
        cmd[1] = 0x00
        System.arraycopy(key16, 0, cmd, 2, 16)
        return cmd
    }

    fun applyChannelFloodScope(regionScope: String) {
        if (regionScope.trim().isNotEmpty()) {
            enqueueCommand(buildFloodScopePacket(regionScope))
        }
    }

    fun setChannelRegionScope(channelIndex: Int, regionScope: String) {
        val cleanScope = sanitizeText(regionScope).take(32)
        prefs.edit().putString("channel_scope_$channelIndex", cleanScope).apply()
        _channels.value = _channels.value.map {
            if (it.index == channelIndex) it.copy(regionScope = cleanScope) else it
        }
    }

    fun setKidNodeName(name: String) {
        val cleanName = sanitizeText(name).take(31)
        if (cleanName.isEmpty()) return
        val nameBytes = cleanName.toByteArray(Charsets.UTF_8)
        val cmd = ByteArray(1 + nameBytes.size)
        cmd[0] = CMD_SET_ADVERT_NAME
        System.arraycopy(nameBytes, 0, cmd, 1, nameBytes.size)
        enqueueCommand(cmd)
    }

    fun sendMessage(target: ChatTarget, text: String) {
        val cleanText = sanitizeText(text).trim().take(133)
        if (cleanText.isEmpty()) return

        val msgId = UUID.randomUUID().toString()
        val epochSec = (System.currentTimeMillis() / 1000L).toInt()
        val ackTag = (epochSec and 0x7FFFFFFF)

        when (target.type) {
            ChatTargetType.CONTACT -> {
                val contact = target.contact ?: return
                val textBytes = cleanText.toByteArray(Charsets.UTF_8)

                fun buildDirectPacket(tagEpochSec: Int): ByteArray {
                    val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + 6 + textBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.put(CMD_SEND_TXT_MSG)
                    buffer.put(0x00)
                    buffer.put(0x00)
                    buffer.putInt(tagEpochSec)
                    buffer.put(contact.pubKeyPrefixBytes)
                    buffer.put(textBytes)
                    return buffer.array()
                }

                val initialHashes = buildCandidateAckHashes(epochSec, textBytes, contact.pubKeyPrefixBytes)
                registerDirectAckCandidates(msgId, initialHashes + ackTag)

                // Attempt 1 (immediate)
                enqueueCommand(buildDirectPacket(epochSec))

                updateMessages { list ->
                    list + ChatMessage(
                        id = msgId,
                        targetId = target.id,
                        text = cleanText,
                        isFromMe = true,
                        deliveryStatus = DeliveryStatus.SENDING,
                        expectedAckTag = ackTag
                    )
                }

                activeSendJobs[msgId]?.cancel()
                val job = scope.launch {
                    try {
                        for (attempt in 2..MAX_SEND_ATTEMPTS) {
                            delay(RETRY_INTERVAL_MS)
                            val currentMsg = _messages.value.firstOrNull { it.id == msgId }
                            if (currentMsg == null || currentMsg.deliveryStatus != DeliveryStatus.SENDING) {
                                return@launch
                            }
                            val retryEpochSec = (System.currentTimeMillis() / 1000L).toInt()
                            val retryAckTag = (retryEpochSec and 0x7FFFFFFF)
                            val retryHashes = buildCandidateAckHashes(retryEpochSec, textBytes, contact.pubKeyPrefixBytes)
                            registerDirectAckCandidates(msgId, retryHashes + retryAckTag)
                            updateMessages { list ->
                                list.map { m ->
                                    if (m.id == msgId && m.deliveryStatus == DeliveryStatus.SENDING) {
                                        m.copy(expectedAckTag = retryAckTag)
                                    } else m
                                }
                            }
                            enqueueCommand(buildDirectPacket(retryEpochSec))
                        }

                        // Wait 2 minutes after the 3rd attempt before marking permanently FAILED
                        delay(RETRY_INTERVAL_MS)
                        clearDirectAckCandidates(msgId)
                        updateMessages { list ->
                            list.map { m ->
                                if (m.id == msgId && m.deliveryStatus == DeliveryStatus.SENDING) {
                                    m.copy(deliveryStatus = DeliveryStatus.FAILED)
                                } else m
                            }
                        }
                    } finally {
                        activeSendJobs.remove(msgId)
                    }
                }
                activeSendJobs[msgId] = job
            }

            ChatTargetType.CHANNEL -> {
                val channel = target.channel ?: return
                val textBytes = cleanText.toByteArray(Charsets.UTF_8)

                fun buildChannelPacket(txEpochSec: Int): ByteArray {
                    val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + textBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.put(CMD_SEND_CHANNEL_TXT_MSG)
                    buffer.put(0x00) // txt_type = 0 (plain text)
                    buffer.put(channel.index.toByte())
                    buffer.putInt(txEpochSec)
                    buffer.put(textBytes)
                    return buffer.array()
                }

                // Pace rapid consecutive channel sends so LoRa TX & repeaters have breathing room
                val nowMs = System.currentTimeMillis()
                val scheduledAtMs = maxOf(nowMs, lastChannelTxScheduledAtMs + CHANNEL_SEND_MIN_SPACING_MS)
                val initialPacingDelayMs = (scheduledAtMs - nowMs).coerceAtLeast(0L)
                lastChannelTxScheduledAtMs = scheduledAtMs

                updateMessages { list ->
                    list + ChatMessage(
                        id = msgId,
                        targetId = target.id,
                        text = cleanText,
                        isFromMe = true,
                        deliveryStatus = DeliveryStatus.GROUP_WAITING_REPEATER,
                        expectedAckTag = null
                    )
                }

                pendingGroupEchoes[msgId] = PendingGroupEcho(
                    msgId = msgId,
                    channelIndex = channel.index,
                    text = cleanText,
                    createdAtMs = scheduledAtMs
                )

                activeSendJobs[msgId]?.cancel()
                val job = scope.launch {
                    try {
                        if (initialPacingDelayMs > 0L) {
                            delay(initialPacingDelayMs)
                        }
                        val firstEpochSec = (System.currentTimeMillis() / 1000L).toInt()
                        if (channel.regionScope.isNotBlank()) {
                            enqueueCommand(buildFloodScopePacket(channel.regionScope))
                        }
                        enqueueCommand(buildChannelPacket(firstEpochSec))

                        // Wait 30s for at least one repeater to echo the packet (PUSH_LOG_RX_DATA 0x88)
                        delay(GROUP_REPEATER_WAIT_MS)
                        val stillWaiting = _messages.value.firstOrNull {
                            it.id == msgId && it.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER
                        }
                        if (stillWaiting != null) {
                            // No repeater heard yet -> re-send automatically a second time
                            val retryNowMs = System.currentTimeMillis()
                            val retrySchedMs = maxOf(retryNowMs, lastChannelTxScheduledAtMs + CHANNEL_SEND_MIN_SPACING_MS)
                            val retryWaitMs = (retrySchedMs - retryNowMs).coerceAtLeast(0L)
                            lastChannelTxScheduledAtMs = retrySchedMs
                            if (retryWaitMs > 0L) {
                                delay(retryWaitMs)
                            }
                            val secondEpochSec = (System.currentTimeMillis() / 1000L).toInt()
                            if (channel.regionScope.isNotBlank()) {
                                enqueueCommand(buildFloodScopePacket(channel.regionScope))
                            }
                            enqueueCommand(buildChannelPacket(secondEpochSec))

                            // Give the 2nd attempt 30s to be heard by a repeater; otherwise finalize as GROUP_SENT
                            delay(GROUP_REPEATER_WAIT_MS)
                            updateMessages { list ->
                                list.map { m ->
                                    if (m.id == msgId && m.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER) {
                                        m.copy(deliveryStatus = DeliveryStatus.GROUP_SENT)
                                    } else m
                                }
                            }
                        }
                    } finally {
                        pendingGroupEchoes.remove(msgId)
                        activeSendJobs.remove(msgId)
                    }
                }
                activeSendJobs[msgId] = job
            }
        }
    }

    private val directAckCandidatesByMsgId = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()

    private fun registerDirectAckCandidates(msgId: String, hashes: Collection<Int>) {
        val set = directAckCandidatesByMsgId.computeIfAbsent(msgId) {
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        }
        hashes.filter { it != 0 }.forEach { set.add(it) }
    }

    private fun clearDirectAckCandidates(msgId: String) {
        directAckCandidatesByMsgId.remove(msgId)
    }

    private fun computeAckHashInt(
        timestampSec: Int,
        attempt: Int,
        textBytes: ByteArray,
        pubKeyPrefixBytes: ByteArray
    ): Int {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val tsBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(timestampSec).array()
            md.update(tsBuf)
            md.update(attempt.toByte())
            md.update(textBytes)
            md.update(pubKeyPrefixBytes)
            val digest = md.digest()
            ByteBuffer.wrap(digest, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
        } catch (_: Exception) {
            0
        }
    }

    private fun buildCandidateAckHashes(
        timestampSec: Int,
        textBytes: ByteArray,
        pubKeyPrefixBytes: ByteArray
    ): List<Int> {
        return (0..3).map { att ->
            computeAckHashInt(timestampSec, att, textBytes, pubKeyPrefixBytes)
        }.filter { it != 0 }.distinct()
    }

    private fun markDirectMessageDelivered(ackTag: Int?) {
        val currentList = _messages.value
        val exactMatchIdx = if (ackTag != null && ackTag != 0) {
            currentList.indexOfFirst { msg ->
                if (!msg.isFromMe || msg.deliveryStatus != DeliveryStatus.SENDING) return@indexOfFirst false
                val candidates = directAckCandidatesByMsgId[msg.id]
                (candidates != null && candidates.contains(ackTag)) || (msg.expectedAckTag == ackTag)
            }
        } else -1

        // Fallback to oldest in-flight SENDING direct message if hash does not match (since Firmware computes
        // ACK hash with its own 32-byte sender PubKey in composeMsgPacket, which is not in the 6-byte recipient prefix).
        val targetIdx = if (exactMatchIdx >= 0) {
            exactMatchIdx
        } else {
            currentList.indexOfFirst { msg ->
                msg.isFromMe && msg.deliveryStatus == DeliveryStatus.SENDING
            }
        }

        if (targetIdx < 0) return
        val confirmedMsgId = currentList[targetIdx].id
        clearDirectAckCandidates(confirmedMsgId)
        activeSendJobs.remove(confirmedMsgId)?.cancel()

        updateMessages { list ->
            list.mapIndexed { idx, msg ->
                if (idx == targetIdx) msg.copy(deliveryStatus = DeliveryStatus.DELIVERED) else msg
            }
        }
    }

    // MeshCore PUSH_LOG_RX_DATA (0x88) delivers overheard LoRa packets (e.g. repeater re-broadcasts).
    // When our companion radio hears a repeater repeat our channel message (PAYLOAD_TYPE_GRP_TXT = 0x05),
    // mark the oldest matching pending group message as confirmed by at least one repeater.
    private fun handleRepeaterRxEcho(frame: ByteArray) {
        if (pendingGroupEchoes.isEmpty() || frame.size < 6) return
        // frame[0] = 0x88, frame[1] = snr, frame[2] = rssi, frame[3..] = raw LoRa packet
        // LoRa header byte at frame[3]: bits 2..5 = payload_type (0x05 = GRP_TXT)
        val header = frame[3].toInt() and 0xFF
        val payloadType = (header shr 2) and 0x0F
        if (payloadType != 0x05) return

        val nowMs = System.currentTimeMillis()
        val candidate = pendingGroupEchoes.values
            .filter { (nowMs - it.createdAtMs) in 150L..65_000L }
            .minByOrNull { it.createdAtMs } ?: return

        pendingGroupEchoes.remove(candidate.msgId)
        activeSendJobs.remove(candidate.msgId)?.cancel()
        updateMessages { list ->
            list.map { m ->
                if (m.id == candidate.msgId && m.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER) {
                    m.copy(deliveryStatus = DeliveryStatus.GROUP_REPEATER_HEARD)
                } else m
            }
        }
    }

    private fun enqueueCommand(data: ByteArray) {
        commandQueue.add(data)
        processNextCommand()
    }

    private fun releaseCommandLockAndContinue() {
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
        awaitingCommandAck = false
        isWriting = false
        processNextCommand()
    }

    private fun processNextCommand() {
        if (isWriting) return
        val next = commandQueue.poll() ?: return
        val rx = rxCharacteristic ?: return
        val gatt = bluetoothGatt ?: return

        lastWrittenCommand = next
        isWriting = true
        awaitingCommandAck = true
        try {
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rx, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.value = next
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rx)
            }
            if (!started) {
                isWriting = false
                awaitingCommandAck = false
                commandQueue.addFirst(next)
                scope.launch {
                    delay(200L)
                    processNextCommand()
                }
                return
            }
            ackTimeoutJob?.cancel()
            ackTimeoutJob = scope.launch {
                delay(1200L)
                if (awaitingCommandAck) {
                    awaitingCommandAck = false
                    isWriting = false
                    processNextCommand()
                }
            }
        } catch (_: Exception) {
            isWriting = false
            awaitingCommandAck = false
        }
    }

    // Resolves sender name in group chat using known contacts (preferring alias) and removing leading 'k' prefix.
    // Example: incoming sender "kOnkel" where contact has originalName="Onkel" and aliasName="Papa" -> "Papa".
    private fun resolveGroupSenderAndBody(rawPayload: String): Pair<String?, String> {
        val colonSpaceIdx = rawPayload.indexOf(": ")
        if (colonSpaceIdx in 1..47) {
            val rawSender = rawPayload.substring(0, colonSpaceIdx).trim()
            val body = rawPayload.substring(colonSpaceIdx + 2).trim()
            return resolveContactName(rawSender, _contacts.value) to body
        }
        val colonIdx = rawPayload.indexOf(":")
        if (colonIdx in 1..47) {
            val rawSender = rawPayload.substring(0, colonIdx).trim()
            val body = rawPayload.substring(colonIdx + 1).trim()
            if (body.isNotEmpty()) {
                return resolveContactName(rawSender, _contacts.value) to body
            }
        }
        return null to rawPayload.trim()
    }

    private fun resolveContactName(
        rawSender: String,
        contactsList: List<MeshContact>,
        manualAliases: Map<String, String> = _manualGroupAliases.value
    ): String {
        val trimmedSender = rawSender.trim()
        val senderLower = trimmedSender.lowercase()
        if (senderLower.isEmpty()) return trimmedSender

        for ((rawChatName, rawManualAlias) in manualAliases) {
            val chatRawLower = rawChatName.trim().lowercase()
            val aliasVal = rawManualAlias.trim()
            if (aliasVal.isNotEmpty() && senderLower == chatRawLower) {
                return aliasVal
            }
        }

        for (c in contactsList) {
            val aliasRaw = c.aliasName.trim()
            val origRaw = c.originalName.trim()
            val prefixLower = c.pubKeyPrefixHex.lowercase()

            val knownKeys = listOf(aliasRaw, origRaw)
                .map { it.lowercase() }
                .filter { it.isNotEmpty() }

            val matchesContact = knownKeys.contains(senderLower) ||
                (prefixLower.isNotEmpty() && senderLower.startsWith(prefixLower))

            if (matchesContact) {
                val chosen = if (aliasRaw.isNotEmpty()) aliasRaw else origRaw
                if (chosen.isNotEmpty()) return chosen
            }
        }

        return trimmedSender
    }

    private fun handleIncomingFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        val opcode = frame[0]
        // Only release command lock on synchronous command replies (0x00, 0x01, 0x05, 0x06, 0x12),
        // NEVER on async push notifications (0x80..0x89), so in-flight GATT writes are never collided/dropped!
        if (opcode == 0x00.toByte() || opcode == 0x01.toByte() || opcode == 0x05.toByte() ||
            opcode == 0x06.toByte() || opcode == 0x12.toByte()
        ) {
            releaseCommandLockAndContinue()
        }
        when (opcode) {
            // System frames -> never trigger message notification sound or unread blink
            PUSH_SEND_CONFIRMED -> {
                val ackTag = if (frame.size >= 5) {
                    ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
                } else null
                markDirectMessageDelivered(ackTag)
            }
            PUSH_MSG_WAITING -> {
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            PUSH_LOG_RX_DATA -> {
                handleRepeaterRxEcho(frame)
            }
            RESP_CONTACT -> {
                if (frame.size >= 132) {
                    val prefixBytes = frame.copyOfRange(1, 7)
                    val prefixHex = prefixBytes.joinToString("") { "%02x".format(it) }
                    val nameBytes = frame.copyOfRange(100, 132)
                    val origName = decodeCleanUtf8(nameBytes).ifBlank { "Node $prefixHex" }
                    val aliasName = prefs.getString("contact_alias_$prefixHex", "") ?: ""

                    val allowedSet = prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()
                    val isAllowed = allowedSet.contains(prefixHex)

                    val updated = _contacts.value.filterNot { it.pubKeyPrefixHex == prefixHex } +
                            MeshContact(prefixHex, prefixBytes, origName, aliasName, isAllowed)
                    _contacts.value = updated
                    saveCachedContacts(updated)
                    refreshExistingMessageSenderNames(updated)
                }
            }
            RESP_CHANNEL_INFO -> {
                if (frame.size >= 34) {
                    val idx = frame[1].toInt() and 0xFF
                    if (idx in 0..7) {
                        val decodedName = decodeCleanUtf8(frame.copyOfRange(2, 34))
                        val hasPsk = if (frame.size >= 50) {
                            frame.copyOfRange(34, 50).any { it.toInt() != 0 }
                        } else {
                            prefs.getBoolean("device_channel_has_psk_$idx", false)
                        }

                        if (decodedName.isNotBlank()) {
                            prefs.edit()
                                .putString("device_channel_name_$idx", decodedName)
                                .putBoolean("device_channel_has_psk_$idx", hasPsk)
                                .apply()
                        } else {
                            prefs.edit()
                                .remove("device_channel_name_$idx")
                                .remove("device_channel_has_psk_$idx")
                                .apply()
                        }

                        _channels.value = loadCachedDeviceChannels()
                    }
                }
            }
            RESP_CONTACT_MSG_RECV -> {
                if (frame.size > 13) {
                    val prefixHex = frame.copyOfRange(1, 7).joinToString("") { "%02x".format(it) }
                    val tsMs = extractEpochMs(frame, 9)
                    val text = decodeCleanUtf8(frame.copyOfRange(13, frame.size))
                    if (text.isNotBlank()) addIncomingContactMessageIfAllowed(prefixHex, text, tsMs)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CONTACT_MSG_RECV_V3 -> {
                if (frame.size > 16) {
                    val prefixHex = frame.copyOfRange(4, 10).joinToString("") { "%02x".format(it) }
                    val tsMs = extractEpochMs(frame, 12)
                    val text = decodeCleanUtf8(frame.copyOfRange(16, frame.size))
                    if (text.isNotBlank()) addIncomingContactMessageIfAllowed(prefixHex, text, tsMs)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CHANNEL_MSG_RECV -> {
                if (frame.size > 8) {
                    val chIdx = frame[1].toInt() and 0xFF
                    val tsMs = extractEpochMs(frame, 4)
                    val rawText = decodeCleanUtf8(frame.copyOfRange(8, frame.size))
                    if (rawText.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, rawText, tsMs)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CHANNEL_MSG_RECV_V3 -> {
                if (frame.size > 11) {
                    val chIdx = frame[4].toInt() and 0xFF
                    val tsMs = extractEpochMs(frame, 7)
                    val rawText = decodeCleanUtf8(frame.copyOfRange(11, frame.size))
                    if (rawText.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, rawText, tsMs)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
        }
    }


    private fun extractEpochMs(frame: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > frame.size) return System.currentTimeMillis()
        val sec = ByteBuffer.wrap(frame, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        // Reasonable Unix epoch check (after year 2020 and not far in future)
        return if (sec in 1_577_836_800L..4_102_444_800L) sec * 1000L else System.currentTimeMillis()
    }

    private fun decodeCleanUtf8(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
        return sanitizeText(raw)
    }

    private fun sanitizeText(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            val isControl = Character.isISOControl(cp) && cp != 10 && cp != 13
            val isReplacementChar = cp == 0xFFFD
            val isPrivateUse = cp in 0xE000..0xF8FF
            if (!isControl && !isReplacementChar && !isPrivateUse) {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString().replace('\r', ' ').replace('\n', ' ').trim()
    }

    private fun addIncomingContactMessageIfAllowed(prefixHex: String, text: String, msgTimestampMs: Long = System.currentTimeMillis()) {
        val allowedSet = prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()
        if (!allowedSet.contains(prefixHex)) return

        val senderDisplay = _contacts.value.firstOrNull { it.pubKeyPrefixHex == prefixHex }?.displayName
        val safeTs = if (msgTimestampMs > 0L) msgTimestampMs else System.currentTimeMillis()
        updateMessages { list ->
            (list + ChatMessage(
                targetId = prefixHex,
                senderName = senderDisplay,
                text = text,
                isFromMe = false,
                deliveryStatus = DeliveryStatus.DELIVERED,
                timestamp = safeTs
            )).sortedBy { it.timestamp }
        }
        markTargetUnreadAndPlaySound(prefixHex)
    }

    private fun addIncomingChannelMessageIfAllowed(channelIndex: Int, rawText: String, msgTimestampMs: Long = System.currentTimeMillis()) {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        if (!allowedSet.contains(channelIndex.toString())) return

        val (sender, body) = resolveGroupSenderAndBody(rawText)
        val targetId = "ch_$channelIndex"
        val safeTs = if (msgTimestampMs > 0L) msgTimestampMs else System.currentTimeMillis()
        updateMessages { list ->
            (list + ChatMessage(
                targetId = targetId,
                senderName = sender,
                text = body,
                isFromMe = false,
                deliveryStatus = DeliveryStatus.DELIVERED,
                timestamp = safeTs
            )).sortedBy { it.timestamp }
        }
        markTargetUnreadAndPlaySound(targetId)
    }

    fun toggleContactAllowed(prefixHex: String, allowed: Boolean) {
        val current = (prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()).toMutableSet()
        if (allowed) current.add(prefixHex) else current.remove(prefixHex)
        prefs.edit().putStringSet("allowed_contacts", current).apply()

        val updatedAllowed = _contacts.value.map {
            if (it.pubKeyPrefixHex == prefixHex) it.copy(isAllowedForKid = allowed) else it
        }
        _contacts.value = updatedAllowed
        saveCachedContacts(updatedAllowed)
    }

    fun toggleChannelAllowed(channelIndex: Int, allowed: Boolean) {
        val current = (prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()).toMutableSet()
        val key = channelIndex.toString()
        if (allowed) current.add(key) else current.remove(key)
        prefs.edit().putStringSet("allowed_channels", current).apply()

        _channels.value = _channels.value.map {
            if (it.index == channelIndex) it.copy(isAllowedForKid = allowed) else it
        }
    }
}
