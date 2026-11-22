package com.example.meshkids

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class DeliveryStatus {
    SENDING,
    DELIVERED,
    FAILED
}

data class MeshContact(
    val pubKeyPrefixHex: String,
    val pubKeyPrefixBytes: ByteArray,
    val originalName: String,
    val aliasName: String,
    val isAllowedForKid: Boolean = false
) {
    val displayName: String
        get() = aliasName.ifBlank { originalName }
}

data class MeshChannel(
    val index: Int,
    val originalName: String,
    val aliasName: String,
    val isEncryptedPskSet: Boolean = false,
    val isAllowedForKid: Boolean = false
) {
    val displayName: String
        get() = aliasName.ifBlank { originalName }
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
    val text: String,
    val isFromMe: Boolean,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENDING,
    val expectedAckTag: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class RadioConfig(
    val presetLabel: String = "EU Narrow (868 MHz)",
    val freqKhz: Int = 869618,
    val bwKhz: Int = 62,
    val sf: Int = 8,
    val cr: Int = 8
)

@SuppressLint("MissingPermission")
class MeshCoreBleManager(private val context: Context) {

    companion object {
        const val DIRECT_MSG_TIMEOUT_MS: Long = 180_000L

        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_APP_START: Byte = 0x01
        const val CMD_SEND_TXT_MSG: Byte = 0x02
        const val CMD_SEND_CHANNEL_TXT_MSG: Byte = 0x03
        const val CMD_GET_CONTACTS: Byte = 0x04
        const val CMD_SET_DEVICE_TIME: Byte = 0x06
        const val CMD_SET_ADVERT_NAME: Byte = 0x08
        const val CMD_SYNC_NEXT_MESSAGE: Byte = 0x0A
        const val CMD_SET_RADIO_PARAMS: Byte = 0x0B
        const val CMD_SEND_SELF_ADVERT: Byte = 0x07
        const val CMD_GET_CHANNEL: Byte = 0x1F
        const val CMD_SET_CHANNEL: Byte = 0x20

        const val RESP_OK: Byte = 0x00
        const val RESP_ERR: Byte = 0x01
        const val RESP_CONTACT: Byte = 0x03
        const val RESP_SENT: Byte = 0x06
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

    private val _channels = MutableStateFlow<List<MeshChannel>>(loadInitialChannels())
    val channels: StateFlow<List<MeshChannel>> = _channels

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _radioConfig = MutableStateFlow(loadSavedRadioConfig())
    val radioConfig: StateFlow<RadioConfig> = _radioConfig

    private fun loadSavedRadioConfig(): RadioConfig {
        return RadioConfig(
            presetLabel = prefs.getString("radio_preset", "EU Narrow (868 MHz)") ?: "EU Narrow (868 MHz)",
            freqKhz = prefs.getInt("radio_freq_khz", 869618),
            bwKhz = prefs.getInt("radio_bw_khz", 62),
            sf = prefs.getInt("radio_sf", 8),
            cr = prefs.getInt("radio_cr", 8)
        )
    }

    private fun loadInitialChannels(): List<MeshChannel> {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        return (0..7).map { idx ->
            val defaultOrig = if (idx == 0) "Oeffentlich" else "Kanal $idx"
            val savedOrig = prefs.getString("channel_orig_$idx", defaultOrig) ?: defaultOrig
            val savedAlias = prefs.getString("channel_alias_$idx", if (idx == 1) "Familie" else "") ?: ""
            val hasPsk = prefs.getBoolean("channel_has_psk_$idx", false)
            MeshChannel(
                index = idx,
                originalName = savedOrig,
                aliasName = savedAlias,
                isEncryptedPskSet = hasPsk,
                isAllowedForKid = allowedSet.contains(idx.toString())
            )
        }
    }

    fun autoConnectIfSaved() {
        val savedAddress = prefs.getString("saved_mac", null) ?: return
        val device = bluetoothAdapter?.getRemoteDevice(savedAddress) ?: return
        connectToDevice(device)
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
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
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
        _connectionState.value = "Verbinde..."
        prefs.edit().putString("saved_mac", device.address).apply()
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = "Suche Dienste..."
                gatt.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _isConnected.value = false
                _connectionState.value = "Verbindung unterbrochen"
                rxCharacteristic = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
            val txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)

            if (txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
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

        applyRadioConfig(_radioConfig.value, saveToPrefs = false)

        syncContacts()
        syncChannels()
        enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
    }

    fun applyRadioConfig(config: RadioConfig, saveToPrefs: Boolean = true) {
        if (saveToPrefs) {
            prefs.edit()
                .putString("radio_preset", config.presetLabel)
                .putInt("radio_freq_khz", config.freqKhz)
                .putInt("radio_bw_khz", config.bwKhz)
                .putInt("radio_sf", config.sf)
                .putInt("radio_cr", config.cr)
                .apply()
        }
        _radioConfig.value = config

        val buffer = ByteBuffer.allocate(1 + 4 + 4 + 1 + 1).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(CMD_SET_RADIO_PARAMS)
        buffer.putInt(config.freqKhz)
        buffer.putInt(config.bwKhz)
        buffer.put(config.sf.toByte())
        buffer.put(config.cr.toByte())
        enqueueCommand(buffer.array())
    }

    fun sendAdvertNow() {
        enqueueCommand(byteArrayOf(CMD_SEND_SELF_ADVERT, 0x01))
    }

    fun syncContacts() {
        enqueueCommand(byteArrayOf(CMD_GET_CONTACTS))
    }

    fun syncChannels() {
        for (idx in 0..7) {
            enqueueCommand(byteArrayOf(CMD_GET_CHANNEL, idx.toByte()))
        }
    }

    fun configureChannelOnDevice(channelIndex: Int, channelName: String, pskInput: String = "") {
        val cleanName = sanitizeText(channelName).take(31)
        if (cleanName.isEmpty() || channelIndex !in 0..7) return

        val pskBytes: ByteArray = if (channelIndex == 0 && pskInput.isBlank()) {
            ByteArray(16)
        } else if (pskInput.trim().matches(Regex("^[0-9a-fA-F]{32}$"))) {
            pskInput.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            val secretSource = if (pskInput.isNotBlank()) pskInput.trim() else "MeshKidsFamily:$cleanName:$channelIndex"
            MessageDigest.getInstance("SHA-256").digest(secretSource.toByteArray(Charsets.UTF_8)).copyOf(16)
        }

        val hasNonZeroPsk = pskBytes.any { it.toInt() != 0 }
        val nameBytes = cleanName.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(1 + 1 + 32 + 16)
        buffer.put(CMD_SET_CHANNEL)
        buffer.put(channelIndex.toByte())
        buffer.put(nameBytes.copyOf(32))
        buffer.put(pskBytes)
        enqueueCommand(buffer.array())

        prefs.edit()
            .putString("channel_orig_$channelIndex", cleanName)
            .putBoolean("channel_has_psk_$channelIndex", hasNonZeroPsk)
            .apply()

        _channels.value = _channels.value.map {
            if (it.index == channelIndex) {
                it.copy(
                    originalName = cleanName,
                    isEncryptedPskSet = hasNonZeroPsk
                )
            } else it
        }
    }

    fun setContactAlias(prefixHex: String, alias: String) {
        val cleanAlias = sanitizeText(alias).take(24)
        prefs.edit().putString("contact_alias_$prefixHex", cleanAlias).apply()
        _contacts.value = _contacts.value.map {
            if (it.pubKeyPrefixHex == prefixHex) it.copy(aliasName = cleanAlias) else it
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
                    deliveryStatus = DeliveryStatus.DELIVERED
                )
            }
        }
    }

    private fun markMessageDelivered(ackTag: Int?) {
        val lastSendingIdx = _messages.value.indexOfLast { it.isFromMe && it.deliveryStatus == DeliveryStatus.SENDING }
        _messages.value = _messages.value.mapIndexed { idx, msg ->
            val isMatch = if (ackTag != null && ackTag != 0) {
                msg.isFromMe && msg.deliveryStatus == DeliveryStatus.SENDING && msg.expectedAckTag == ackTag
            } else {
                idx == lastSendingIdx
            }
            if (isMatch) msg.copy(deliveryStatus = DeliveryStatus.DELIVERED) else msg
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
        gatt.writeCharacteristic(rx)
    }

    private fun handleIncomingFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0]) {
            PUSH_SEND_CONFIRMED -> {
                val ackTag = if (frame.size >= 5) {
                    ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
                } else null
                markMessageDelivered(ackTag)
            }
            RESP_SENT -> {
                val ackTag = if (frame.size >= 6) {
                    ByteBuffer.wrap(frame, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
                } else null
                markMessageDelivered(ackTag)
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
                }
            }
            RESP_CHANNEL_INFO -> {
                if (frame.size >= 34) {
                    val idx = frame[1].toInt() and 0xFF
                    if (idx in 0..7) {
                        val decodedName = decodeCleanUtf8(frame.copyOfRange(2, 34))
                        val fallbackOrig = if (idx == 0) "Oeffentlich" else "Kanal $idx"
                        val finalOrig = decodedName.ifBlank {
                            prefs.getString("channel_orig_$idx", fallbackOrig) ?: fallbackOrig
                        }
                        val hasPsk = if (frame.size >= 50) {
                            frame.copyOfRange(34, 50).any { it.toInt() != 0 }
                        } else {
                            prefs.getBoolean("channel_has_psk_$idx", false)
                        }

                        prefs.edit()
                            .putString("channel_orig_$idx", finalOrig)
                            .putBoolean("channel_has_psk_$idx", hasPsk)
                            .apply()

                        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
                        val isAllowed = allowedSet.contains(idx.toString())
                        val savedAlias = prefs.getString("channel_alias_$idx", if (idx == 1) "Familie" else "") ?: ""

                        _channels.value = _channels.value.map {
                            if (it.index == idx) {
                                it.copy(
                                    originalName = finalOrig,
                                    aliasName = savedAlias,
                                    isEncryptedPskSet = hasPsk,
                                    isAllowedForKid = isAllowed
                                )
                            } else it
                        }
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
                    val text = decodeCleanUtf8(frame.copyOfRange(7, frame.size))
                    if (text.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, text)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
            RESP_CHANNEL_MSG_RECV_V3 -> {
                if (frame.size > 10) {
                    val chIdx = frame[4].toInt() and 0xFF
                    val text = decodeCleanUtf8(frame.copyOfRange(10, frame.size))
                    if (text.isNotBlank()) addIncomingChannelMessageIfAllowed(chIdx, text)
                }
                enqueueCommand(byteArrayOf(CMD_SYNC_NEXT_MESSAGE))
            }
        }
    }

    private fun decodeCleanUtf8(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.UTF_8).trimEnd(' ')
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
        return sb.toString().replace('', ' ').replace('
', ' ').trim()
    }

    private fun addIncomingContactMessageIfAllowed(prefixHex: String, text: String) {
        val allowedSet = prefs.getStringSet("allowed_contacts", emptySet()) ?: emptySet()
        if (!allowedSet.contains(prefixHex)) return

        _messages.value = _messages.value + ChatMessage(
            targetId = prefixHex,
            text = text,
            isFromMe = false,
            deliveryStatus = DeliveryStatus.DELIVERED
        )
    }

    private fun addIncomingChannelMessageIfAllowed(channelIndex: Int, text: String) {
        val allowedSet = prefs.getStringSet("allowed_channels", emptySet()) ?: emptySet()
        if (!allowedSet.contains(channelIndex.toString())) return

        _messages.value = _messages.value + ChatMessage(
            targetId = "ch_$channelIndex",
            text = text,
            isFromMe = false,
            deliveryStatus = DeliveryStatus.DELIVERED
        )
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
