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
import org.json.JSONArray
import org.json.JSONObject
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
    val isAllowedForKid: Boolean = false,
    val nodeType: Int = 1
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
    val rawSenderName: String? = null,
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
        const val APP_VERSION: String = "2.9"
        const val MAX_CHANNELS: Int = 32
        const val MAX_MESSAGES_PER_TARGET: Int = 20
        val DEFAULT_QUICK_REPLIES: List<String> = listOf(
            "Hallo!",
            "Bin gut angekommen!",
            "Ich komme jetzt nach Hause",
            "Kannst du mich abholen?"
        )
        val KNOWN_SCOPES: List<String> = listOf(
            "ruhrgebiet", "ruhr", "pott", "nrw", "de-nw",
            "essen", "bochum", "dortmund", "duisburg", "gelsenkirchen",
            "oberhausen", "bottrop", "recklinghausen", "mulheim", "muelheim",
            "herne", "hagen", "unna", "en-kreis", "rheinland",
            "niederrhein", "muensterland", "sauerland", "bergischesland",
            "koeln", "duesseldorf", "bonn", "aachen", "bielefeld", "muenster",
            "de", "deutschland", "dach", "at", "oesterreich", "ch", "schweiz",
            "eu", "europa", "norddeutschland", "sueddeutschland",
            "ostdeutschland", "westdeutschland", "de-by", "bayern", "muenchen",
            "de-bw", "stuttgart", "de-ni", "hannover", "de-he", "frankfurt",
            "de-sn", "leipzig", "dresden", "de-rp", "de-sl", "de-bb", "berlin",
            "de-mv", "de-sh", "de-hh", "hamburg", "de-hb", "bremen",
            "de-st", "de-th", "nuernberg", "nl", "be", "familie", "zuhause",
            "radeln", "wandern", "notfunk", "mesh", "lora", "local",
            "region", "world", "test"
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
        const val DEFAULT_WIFI_PORT: Int = 5000
        const val WIFI_CONNECT_TIMEOUT_MS: Int = 4000
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
                    put("nodeType", c.nodeType)
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
                val nodeType = obj.optInt("nodeType", 1)
                if (nodeType != 2) {
                    result.add(MeshContact(prefixHex, prefixBytes, origName, savedAlias, isAllowed, nodeType))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

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

    private val _connectionMode = MutableStateFlow(prefs.getString("connection_mode", "BLE") ?: "BLE")
    val connectionMode: StateFlow<String> = _connectionMode

    private val _wifiHostOrDyndns = MutableStateFlow(prefs.getString("wifi_host_or_dyndns", "") ?: "")
    val wifiHostOrDyndns: StateFlow<String> = _wifiHostOrDyndns

    private val _wifiPort = MutableStateFlow(prefs.getInt("wifi_port", DEFAULT_WIFI_PORT).takeIf { it in 1..65535 } ?: DEFAULT_WIFI_PORT)
    val wifiPort: StateFlow<Int> = _wifiPort

    private var wifiSocket: java.net.Socket? = null
    private var wifiOut: java.io.OutputStream? = null
    private var wifiReaderJob: Job? = null
    private var wifiConnecting: Boolean = false

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
                    put("rawSenderName", msg.rawSenderName ?: JSONObject.NULL)
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
                val rawSenderName =
