package com.example.meshkids

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: MeshCoreBleManager
    private lateinit var pinManager: ParentPinManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = MeshCoreBleManager(this)
        pinManager = ParentPinManager(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1E88E5),
                    secondary = Color(0xFF2E7D32),
                    background = Color(0xFFF4F8FB)
                )
            ) {
                MeshKidsApp(bleManager, pinManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshKidsApp(bleManager: MeshCoreBleManager, pinManager: ParentPinManager) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        permissionsGranted = granted.values.all { it }
        if (permissionsGranted) {
            bleManager.autoConnectIfSaved()
        }
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }

    DisposableEffect(lifecycleOwner, permissionsGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionsGranted) {
                bleManager.autoConnectIfSaved()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isConnected by bleManager.isConnected.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()
    val allContacts by bleManager.contacts.collectAsState()
    val allChannels by bleManager.channels.collectAsState()
    val unreadTargetIds by bleManager.unreadTargetIds.collectAsState()
    val alertCounter by bleManager.incomingAlertCounter.collectAsState()

    val allowedTargets = remember(allContacts, allChannels) {
        val channelTargets = allChannels.filter { it.isAllowedForKid }.map {
            ChatTarget(
                type = ChatTargetType.CHANNEL,
                id = "ch_${it.index}",
                title = it.displayName,
                channel = it
            )
        }
        val contactTargets = allContacts.filter { it.isAllowedForKid }.map {
            ChatTarget(
                type = ChatTargetType.CONTACT,
                id = it.pubKeyPrefixHex,
                title = it.displayName,
                contact = it
            )
        }
        channelTargets + contactTargets
    }

    val messages by bleManager.messages.collectAsState()
    val quickReplies by bleManager.quickReplies.collectAsState()

    var selectedTargetId by remember { mutableStateOf<String?>(null) }
    val selectedTarget = remember(selectedTargetId, allowedTargets) {
        allowedTargets.firstOrNull { it.id == selectedTargetId }
    }

    LaunchedEffect(selectedTarget?.id) {
        selectedTarget?.let { bleManager.markTargetRead(it.id) }
    }

    var showPinDialog by remember { mutableStateOf(false) }
    var showParentSettings by remember { mutableStateOf(false) }

    if (showPinDialog) {
        ParentPinDialog(
            pinManager = pinManager,
            onDismiss = { showPinDialog = false },
            onUnlocked = {
                showPinDialog = false
                showParentSettings = true
            }
        )
    }

    if (showParentSettings) {
        ParentSettingsScreen(
            bleManager = bleManager,
            pinManager = pinManager,
            onBack = { showParentSettings = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedTarget != null) {
                            IconButton(onClick = { selectedTargetId = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck", tint = Color.White)
                            }
                        }
                        Column {
                            Text(
                                text = selectedTarget?.title ?: "Meine Nachrichten",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (isConnected) "Funkgeraet bereit" else connectionState,
                                fontSize = 13.sp,
                                color = if (isConnected) Color(0xFFB9F6CA) else Color(0xFFFFCDD2)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showPinDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Eltern-Einstellungen",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedTarget == null) {
                KidTargetListScreen(
                    targets = allowedTargets,
                    messages = messages,
                    unreadTargetIds = unreadTargetIds,
                    alertCounter = alertCounter,
                    onSelectTarget = {
                        selectedTargetId = it.id
                        bleManager.markTargetRead(it.id)
                    }
                )
            } else {
                KidChatScreen(
                    target = selectedTarget,
                    messages = messages.filter { it.targetId == selectedTarget.id }.takeLast(20),
                    quickReplies = quickReplies,
                    isConnected = isConnected,
                    onSendMessage = { text -> bleManager.sendMessage(selectedTarget, text) }
                )
            }
        }
    }
}

@Composable
fun KidTargetListScreen(
    targets: List<ChatTarget>,
    messages: List<ChatMessage>,
    unreadTargetIds: Set<String>,
    alertCounter: Long,
    onSelectTarget: (ChatTarget) -> Unit
) {
    // Explicit discrete blinking loop whenever unreadTargetIds is non-empty or a new message arrives
    var blinkPhase by remember { mutableStateOf(false) }
    LaunchedEffect(unreadTargetIds, alertCounter) {
        if (unreadTargetIds.isEmpty()) {
            blinkPhase = false
        } else {
            while (true) {
                blinkPhase = !blinkPhase
                delay(450L)
            }
        }
    }

    if (targets.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Noch keine Chats freigegeben",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bitte deine Eltern, oben rechts auf das Zahnrad zu tippen, um Kontakte oder den Familien-Chat freizugeben.",
                fontSize = 16.sp,
                color = Color.DarkGray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(targets, key = { it.id }) { target ->
                val lastMsg = messages.filter { it.targetId == target.id }.maxByOrNull { it.timestamp }
                val isGroup = target.type == ChatTargetType.CHANNEL
                val isUnread = unreadTargetIds.contains(target.id)
                val isHighlightedNow = isUnread && blinkPhase
                val containerColor = if (isHighlightedNow) Color(0xFFFFEE58) else Color.White
                val border = if (isUnread) {
                    BorderStroke(if (isHighlightedNow) 3.dp else 1.5.dp, Color(0xFFD32F2F))
                } else null

                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectTarget(target) },
                    shape = RoundedCornerShape(20.dp),
                    border = border,
                    elevation = CardDefaults.cardElevation(if (isHighlightedNow) 10.dp else 4.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (isGroup) Color(0xFFC8E6C9) else Color(0xFFBBDEFB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isGroup) Icons.Default.Groups else Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = if (isGroup) Color(0xFF2E7D32) else Color(0xFF1565C0)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = target.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (isUnread) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isHighlightedNow) Color(0xFFD32F2F) else Color(0xFFE57373),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "NEU",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = lastMsg?.text ?: if (isGroup) "Familien-Gruppenchat" else "Tippe hier zum Schreiben!",
                                fontSize = 15.sp,
                                fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isUnread) Color.Black else Color.Gray,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KidChatScreen(
    target: ChatTarget,
    messages: List<ChatMessage>,
    quickReplies: List<String>,
    isConnected: Boolean,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    val blueGroupColor = Color(0xFF1E88E5)
    val greenDeliveredColor = Color(0xFF2E7D32)
    val redPendingOrFailedColor = Color(0xFFD32F2F)

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isGroupMsg = target.type == ChatTargetType.CHANNEL || msg.deliveryStatus == DeliveryStatus.GROUP_SENT
                val bubbleColor = when {
                    !msg.isFromMe -> Color.White
                    isGroupMsg -> blueGroupColor
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> greenDeliveredColor
                    else -> redPendingOrFailedColor
                }
                val textColor = if (msg.isFromMe) Color.White else Color.Black
                val statusLabel = when {
                    isGroupMsg -> "Gesendet (Gruppe)"
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> "Zugestellt"
                    msg.deliveryStatus == DeliveryStatus.FAILED -> "Keine Zustellbestaetigung"
                    else -> "Warte auf Zustellbestaetigung..."
                }
                val statusColor = when {
                    isGroupMsg -> blueGroupColor
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> greenDeliveredColor
                    else -> redPendingOrFailedColor
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isFromMe) Arrangement.End else Arrangement.Start
                ) {
                    Column(horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start) {
                        if (!msg.isFromMe && target.type == ChatTargetType.CHANNEL && !msg.senderName.isNullOrBlank()) {
                            Text(
                                text = msg.senderName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0),
                                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = bubbleColor,
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                fontSize = 18.sp,
                                color = textColor
                            )
                        }
                        if (msg.isFromMe) {
                            Text(
                                text = statusLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quickReplies) { reply ->
                AssistChip(
                    onClick = { if (isConnected) onSendMessage(reply) },
                    label = { Text(reply, fontSize = 15.sp) },
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { if (it.length <= 133) inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nachricht an ${target.title}...") },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = {
                    if (inputText.isNotBlank() && isConnected) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                },
                containerColor = if (isConnected) Color(0xFF2E7D32) else Color.Gray,
                shape = CircleShape
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden", tint = Color.White)
            }
        }
    }
}

@Composable
fun ParentPinDialog(
    pinManager: ParentPinManager,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eltern-Bereich geschuetzt") },
        text = {
            Column {
                Text("Bitte Eltern-PIN eingeben (Standard: 1234):")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        enteredPin = it
                        error = false
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error
                )
                if (error) {
                    Text("Falsche PIN!", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pinManager.verifyPin(enteredPin)) onUnlocked() else error = true
            }) {
                Text("Entsperren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSettingsScreen(
    bleManager: MeshCoreBleManager,
    pinManager: ParentPinManager,
    onBack: () -> Unit
) {
    val devices by bleManager.discoveredDevices.collectAsState()
    val contacts by bleManager.contacts.collectAsState()
    val channels by bleManager.channels.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()

    var kidName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    var infoBanner by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Eltern-Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (infoBanner != null) {
                item {
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = infoBanner!!,
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item {
                Text("1. MeshCore Funkgeraet koppeln ($connectionState)", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { bleManager.startScan() }) {
                        Text("BLE suchen")
                    }
                    OutlinedButton(onClick = {
                        bleManager.sendAdvertNow()
                        infoBanner = "Geraetesignal (Advert) gesendet."
                    }) {
                        Text("Advert senden")
                    }
                }
            }

            items(devices) { device ->
                OutlinedButton(
                    onClick = { bleManager.connectToDevice(device) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${device.name ?: "MeshCore Geraet"} (${device.address})")
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("2. Kontakte & Alias-Namen (z. B. Mama, Papa)", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { bleManager.syncContacts() }) {
                    Text("Kontakte vom Funkgeraet laden")
                }
            }

            items(contacts) { contact ->
                var aliasField by remember(contact.pubKeyPrefixHex, contact.aliasName) {
                    mutableStateOf(contact.aliasName)
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = contact.displayName, fontWeight = FontWeight.SemiBold)
                            Text(text = "${contact.originalName} (${contact.pubKeyPrefixHex})", fontSize = 12.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = contact.isAllowedForKid,
                            onCheckedChange = { bleManager.toggleContactAllowed(contact.pubKeyPrefixHex, it) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aliasField,
                            onValueChange = { aliasField = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Alias (z. B. Mama)") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            bleManager.setContactAlias(contact.pubKeyPrefixHex, aliasField)
                            infoBanner = "Alias fuer Kontakt gespeichert."
                        }) {
                            Text("Alias")
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("3. Gruppenchat / Kanaele aus Geraet uebernehmen", fontWeight = FontWeight.Bold)
                Text("Kanaele werden nur aus dem Funkgeraet ausgelesen und nicht neu angelegt oder ueberschrieben. Du kannst hier einen Alias vergeben und den Chat freigeben.", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    bleManager.syncChannelsFromDevice()
                    infoBanner = "Kanaele werden aus dem Funkgeraet geladen..."
                }) {
                    Text("Kanaele aus Geraet laden")
                }
                if (channels.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Noch keine Kanaele vom Geraet uebernommen. Bitte mit Funkgeraet verbinden und auf 'Kanaele aus Geraet laden' tippen.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }

            items(channels) { channel ->
                var aliasField by remember(channel.index, channel.aliasName) {
                    mutableStateOf(channel.aliasName)
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Kanal ${channel.index}: ${channel.displayName}", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Geraetekanal: ${channel.originalName} • ${if (channel.isEncryptedPskSet) "PSK im Geraet" else "Offen / kein PSK"}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = channel.isAllowedForKid,
                            onCheckedChange = { bleManager.toggleChannelAllowed(channel.index, it) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aliasField,
                            onValueChange = { aliasField = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Alias (z. B. Familie)") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            bleManager.setChannelAlias(channel.index, aliasField)
                            infoBanner = "Alias fuer Kanal ${channel.index} gespeichert."
                        }) {
                            Text("Alias")
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("4. Anzeigename des Kindes im Mesh", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = kidName,
                        onValueChange = { kidName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("z. B. Leo") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (kidName.isNotBlank()) {
                            bleManager.setKidNodeName(kidName)
                            infoBanner = "Geraetename '$kidName' gesetzt."
                        }
                    }) {
                        Text("Speichern")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("5. Eltern-PIN aendern", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        placeholder = { Text("Neue PIN (min. 4 Ziffern)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (newPin.length >= 4) {
                            pinManager.setPin(newPin)
                            newPin = ""
                            infoBanner = "Eltern-PIN wurde geaendert."
                        }
                    }) {
                        Text("PIN setzen")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                val currentReplies by bleManager.quickReplies.collectAsState()
                var reply1 by remember(currentReplies) { mutableStateOf(currentReplies.getOrElse(0) { "" }) }
                var reply2 by remember(currentReplies) { mutableStateOf(currentReplies.getOrElse(1) { "" }) }
                var reply3 by remember(currentReplies) { mutableStateOf(currentReplies.getOrElse(2) { "" }) }
                var reply4 by remember(currentReplies) { mutableStateOf(currentReplies.getOrElse(3) { "" }) }

                Text("6. Voreingestellte Schnelltexte (unten im Chat)", fontWeight = FontWeight.Bold)
                Text("Diese Felder erscheinen direkt ueber dem Eingabefeld im Chat und koennen angepasst werden.", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = reply1,
                    onValueChange = { if (it.length <= 60) reply1 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feld 1") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = reply2,
                    onValueChange = { if (it.length <= 60) reply2 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feld 2") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = reply3,
                    onValueChange = { if (it.length <= 60) reply3 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feld 3") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = reply4,
                    onValueChange = { if (it.length <= 60) reply4 = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feld 4") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        bleManager.setQuickReplies(listOf(reply1, reply2, reply3, reply4))
                        infoBanner = "Schnelltexte gespeichert."
                    }) {
                        Text("Schnelltexte speichern")
                    }
                    OutlinedButton(onClick = {
                        bleManager.setQuickReplies(MeshCoreBleManager.DEFAULT_QUICK_REPLIES)
                        infoBanner = "Standard-Schnelltexte wiederhergestellt."
                    }) {
                        Text("Standard")
                    }
                }
            }
        }
    }
}
