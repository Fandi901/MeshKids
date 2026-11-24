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
import java.util.concurrent.ConcurrentLinkedQueue

enum class DeliveryStatus {
    SENDING,
    DELIVERED,
    FAILED,
    GROUP_SENT
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
        const val RECONNECT_INTERVAL_MS: Long = 4_000L
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
        const val CMD_SET_FLOOD_SCOPE_KEY: Byte = 0x36

        const val RESP_CONTACT: Byte = 0x03
        const val RESP_CONTACT_MSG_RECV: Byte = 0x07
        const val RESP_CHANNEL_MSG_RECV: Byte = 0x08
        const val RESP_CONTACT_MSG_RECV_V3: Byte = 0x10
        const val RESP_CHANNEL_MSG_RECV_V3: Byte = 0x11
        const val RESP_CHANNEL_INFO: Byte = 0x12

        const val PUSH_SEND_CONFIRMED: Byte = 0x82.toByte()
        const val PUSH_MSG_WAITING: Byte = 0x83.toByte()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSendJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var isConnecting = false
    private var connectAttemptStartedAtMs = 0L

    private val commandQueue = ConcurrentLinkedQueue<ByteArray>()
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

    private val _contacts = MutableStateFlow<List<MeshContact>>(emptyList())
    val contacts: StateFlow<List<MeshContact>> = _contacts

    private val _manualGroupAliases = MutableStateFlow>(loadManualGroupAliases())
    val manualGroupAliases: StateFlow> = _manualGroupAliases

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
                val normalizedStatus = if (status == DeliveryStatus.SENDING) DeliveryStatus.FAILED else status
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
