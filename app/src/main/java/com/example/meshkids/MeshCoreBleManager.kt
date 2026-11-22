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
        const val DIRECT_MSG_TIMEOUT_MS: Long = 180_000L
        const val RECONNECT_INTERVAL_MS: Long = 4_000L

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
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var isConnecting = false
    private var connectAttemptStartedAtMs = 0L

    private val commandQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    private val prefs = context.getSharedPreferences("meshkids_prefs", Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow("Getrennt")
    val connectionState: StateFlow<String> = _connectionState

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices

    private val _contacts = MutableStateFlow<List<MeshContact>>(emptyList())
    val contacts: StateFlow<List<MeshContact>> = _contacts

    private val _channels = MutableStateFlow<List<MeshChannel>>(loadCachedDeviceChannels())
    val channels: StateFlow<List<MeshChannel>> = _channels

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _unreadTargetIds = MutableStateFlow<Set<String>>(emptySet())
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

    private fun loadCachedDeviceChannels(): List<MeshChannel> {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        return (0..7).mapNotNull { idx ->
            val deviceName = prefs.getString("device_channel_name_$idx", null)
            if (deviceName.isNullOrBlank()) {
                null
            } else {
                val savedAlias = prefs.getString("channel_alias_$idx", "") ?: ""
                val hasPsk = prefs.getBoolean("device_channel_has_psk_$idx", false)
                MeshChannel(
                    index = idx,
                    originalName = deviceName,
                    aliasName = savedAlias,
                    isEncryptedPskSet = hasPsk,
                    isAllowedForKid = allowedSet.contains(idx.toString())
                )
            }
        }
    }

    fun markTargetRead(targetId: String) {
        if (_unreadTargetIds.value.contains(targetId)) {
            _unreadTargetIds.value = _unreadTargetIds.value - targetId
        }
    }

    private fun markTargetUnreadAndPlaySound(targetId: String) {
        _unreadTargetIds.value = _unreadTargetIds.value + targetId
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
            isWriting = false
            processNextCommand()
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
            put(0x03)
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

    fun setContactAlias(prefixHex: String, alias: String) {
        val cleanAlias = sanitizeText(alias).take(24)
        prefs.edit().putString("contact_alias_$prefixHex", cleanAlias).apply()
        val updatedContacts = _contacts.value.map {
            if (it.pubKeyPrefixHex == prefixHex) it.copy(aliasName = cleanAlias) else it
        }
        _contacts.value = updatedContacts
        refreshExistingMessageSenderNames(updatedContacts)
    }

    private fun refreshExistingMessageSenderNames(currentContacts: List<MeshContact>) {
        _messages.value = _messages.value.map { msg ->
            if (!msg.isFromMe && !msg.senderName.isNullOrBlank()) {
                val mapped = resolveContactName(msg.senderName, currentContacts)
                if (mapped != msg.senderName) msg.copy(senderName = mapped) else msg
            } else msg
        }
    }

    fun setChannelAlias(channelIndex: Int, alias: String) {
        val cleanAlias = sanitizeText(alias).take(24)
        prefs.edit().putString("channel_alias_$channelIndex", cleanAlias).apply()
        _channels.value = _channels.value.map {
            if (it.index == channelIndex) it.copy(aliasName = cleanAlias) else it
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

                val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + 6 + textBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(CMD_SEND_TXT_MSG)
                buffer.put(0x00)
                buffer.put(0x00)
                buffer.putInt(epochSec)
                buffer.put(contact.pubKeyPrefixBytes)
                buffer.put(textBytes)
                enqueueCommand(buffer.array())

                _messages.value = _messages.value + ChatMessage(
                    id = msgId,
                    targetId = target.id,
                    text = cleanText,
                    isFromMe = true,
                    deliveryStatus = DeliveryStatus.SENDING,
                    expectedAckTag = ackTag
                )

                scope.launch {
                    delay(DIRECT_MSG_TIMEOUT_MS)
                    _messages.value = _messages.value.map { m ->
                        if (m.id == msgId && m.deliveryStatus == DeliveryStatus.SENDING) {
                            m.copy(deliveryStatus = DeliveryStatus.FAILED)
                        } else m
                    }
                }
            }

            ChatTargetType.CHANNEL -> {
                val channel = target.channel ?: return
                val textBytes = cleanText.toByteArray(Charsets.UTF_8)

                val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + textBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(CMD_SEND_CHANNEL_TXT_MSG)
                buffer.put(0x00)
                buffer.put(channel.index.toByte())
                buffer.putInt(epochSec)
                buffer.put(textBytes)
                enqueueCommand(buffer.array())

                _messages.value = _messages.value + ChatMessage(
                    id = msgId,
                    targetId = target.id,
                    text = cleanText,
                    isFromMe = true,
                    deliveryStatus = DeliveryStatus.GROUP_SENT,
                    expectedAckTag = null
                )
            }
        }
    }

    private fun markDirectMessageDelivered(ackTag: Int?) {
        val currentList = _messages.value
        val exactMatchIdx = if (ackTag != null && ackTag != 0) {
            currentList.indexOfFirst {
                it.isFromMe &&
                    it.deliveryStatus == DeliveryStatus.SENDING &&
                    it.expectedAckTag == ackTag
            }
        } else -1

        val targetIdx = if (exactMatchIdx >= 0) {
            exactMatchIdx
        } else {
            currentList.indexOfFirst {
                it.isFromMe && it.deliveryStatus == DeliveryStatus.SENDING
            }
        }

        if (targetIdx < 0) return

        _messages.value = currentList.mapIndexed { idx, msg ->
            if (idx == targetIdx) msg.copy(deliveryStatus = DeliveryStatus.DELIVERED) else msg
        }
    }

    private fun enqueueCommand(data: ByteArray) {
        commandQueue.add(data)
        processNextCommand()
    }

    private fun processNextCommand() {
        if (isWriting) return
        val next = commandQueue.poll() ?: return
        val rx = rxCharacteristic ?: return
        val gatt = bluetoothGatt ?: return

        isWriting = true
        rx.value = next
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            gatt.writeCharacteristic(rx)
        } catch (_: Exception) {
            isWriting = false
        }
    }

    // Resolves sender name in group chat using known contacts (preferring alias) and removing leading 'k' prefix.
    // Example: incoming sender "kOnkel" where contact has originalName="Onkel" and aliasName="Papa" -> "Papa".
    private fun resolveGroupSenderAndBody(rawPayload: String): Pair<String?, String> {
        val colonIdx = rawPayload.indexOf(": ")
        if (colonIdx > 0 && colonIdx < 40) {
            val rawSender = rawPayload.substring(0, colonIdx).trim()
            val body = rawPayload.substring(colonIdx + 2).trim()
            return resolveContactName(rawSender, _contacts.value) to body
        }
        return null to rawPayload.trim()
    }

    private fun resolveContactName(rawSender: String, contactsList: List<MeshContact>): String {
        val trimmedSender = rawSender.trim()
        val cleanedSender = stripLeadingK(trimmedSender)

        val candidates = listOf(trimmedSender, cleanedSender)
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (c in contactsList) {
            val aliasRaw = c.aliasName.trim()
            val aliasClean = stripLeadingK(aliasRaw)
            val origRaw = c.originalName.trim()
            val origClean = stripLeadingK(origRaw)
            val prefixLower = c.pubKeyPrefixHex.lowercase()

            val knownKeys = listOf(aliasRaw, aliasClean, origRaw, origClean)
                .map { it.lowercase() }
                .filter { it.isNotEmpty() }

            val matchesContact = candidates.any { cand ->
                knownKeys.contains(cand) || (prefixLower.isNotEmpty() && cand.startsWith(prefixLower))
            }

            if (matchesContact) {
                val chosen = if (aliasClean.isNotEmpty()) aliasClean else origClean
                if (chosen.isNotEmpty()) return chosen
            }
        }

        return cleanedSender
    }

    private fun stripLeadingK(name: String): String {
        if (name.length > 1 && name.startsWith("k", ignoreCase = true)) {
            val rest = name.substring(1).removePrefix(":").trim()
            if (rest.isNotEmpty()) return rest
        }
        return name
    }

    private fun handleIncomingFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0]) {
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
                    val text = decodeCleanUtf8(frame.copyOfRange(13, frame.size))
                    if (text.isNotBlank()) addIncomingContactMessageIfAllowed(prefixHex, text)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CONTACT_MSG_RECV_V3 -> {
                if (frame.size > 16) {
                    val prefixHex = frame.copyOfRange(4, 10).joinToString("") { "%02x".format(it) }
                    val text = decodeCleanUtf8(frame.copyOfRange(16, frame.size))
                    if (text.isNotBlank()) addIncomingContactMessageIfAllowed(prefixHex, text)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CHANNEL_MSG_RECV -> {
                if (frame.size > 7) {
                    val chIdx = frame[1].toInt() and 0xFF
                    val rawText = decodeCleanUtf8(frame.copyOfRange(7, frame.size))
                    if (rawText.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, rawText)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CHANNEL_MSG_RECV_V3 -> {
                if (frame.size > 10) {
                    val chIdx = frame[4].toInt() and 0xFF
                    val rawText = decodeCleanUtf8(frame.copyOfRange(10, frame.size))
                    if (rawText.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, rawText)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
        }
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

    private fun addIncomingContactMessageIfAllowed(prefixHex: String, text: String) {
        val allowedSet = prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()
        if (!allowedSet.contains(prefixHex)) return

        val senderDisplay = _contacts.value.firstOrNull { it.pubKeyPrefixHex == prefixHex }?.displayName
        _messages.value = _messages.value + ChatMessage(
            targetId = prefixHex,
            senderName = senderDisplay,
            text = text,
            isFromMe = false,
            deliveryStatus = DeliveryStatus.DELIVERED
        )
        markTargetUnreadAndPlaySound(prefixHex)
    }

    private fun addIncomingChannelMessageIfAllowed(channelIndex: Int, rawText: String) {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        if (!allowedSet.contains(channelIndex.toString())) return

        val (sender, body) = resolveGroupSenderAndBody(rawText)
        val targetId = "ch_$channelIndex"
        _messages.value = _messages.value + ChatMessage(
            targetId = targetId,
            senderName = sender,
            text = body,
            isFromMe = false,
            deliveryStatus = DeliveryStatus.DELIVERED
        )
        markTargetUnreadAndPlaySound(targetId)
    }

    fun toggleContactAllowed(prefixHex: String, allowed: Boolean) {
        val current = (prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()).toMutableSet()
        if (allowed) current.add(prefixHex) else current.remove(prefixHex)
        prefs.edit().putStringSet("allowed_contacts", current).apply()

        _contacts.value = _contacts.value.map {
            if (it.pubKeyPrefixHex == prefixHex) it.copy(isAllowedForKid = allowed) else it
        }
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
