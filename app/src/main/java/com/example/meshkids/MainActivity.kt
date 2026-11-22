package com.example.meshkids

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    var selectedTargetId by remember { mutableStateOf<String?>(null) }
    val selectedTarget = remember(selectedTargetId, allowedTargets) {
        allowedTargets.firstOrNull { it.id == selectedTargetId }
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
                    onSelectTarget = { selectedTargetId = it.id }
                )
            } else {
                KidChatScreen(
                    target = selectedTarget,
                    messages = messages.filter { it.targetId == selectedTarget.id },
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
    onSelectTarget: (ChatTarget) -> Unit
) {
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
            items(targets) { target ->
                val lastMsg = messages.lastOrNull { it.targetId == target.id }
                val isGroup = target.type == ChatTargetType.CHANNEL
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectTarget(target) },
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
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
                            Text(text = target.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = lastMsg?.text ?: if (isGroup) "Familien-Gruppenchat" else "Tippe hier zum Schreiben!",
                                fontSize = 15.sp,
                                color = Color.Gray,
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
    isConnected: Boolean,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val quickReplies = listOf(
        "Hallo!",
        "Bin gut angekommen!",
        "Ich komme jetzt nach Hause",
        "Kannst du mich abholen?"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { msg ->
                val bubbleColor = when {
                    !msg.isFromMe -> Color.White
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> Color(0xFF2E7D32)
                    else -> Color(0xFFD32F2F)
                }
                val textColor = if (msg.isFromMe) Color.White else Color.Black
                val statusLabel = when (msg.deliveryStatus) {
                    DeliveryStatus.SENDING -> "Wird gesendet (bis zu 3 Min.)..."
                    DeliveryStatus.DELIVERED -> "Angekommen"
                    DeliveryStatus.FAILED -> "Nicht angekommen"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isFromMe) Arrangement.End else Arrangement.Start
                ) {
                    Column(horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start) {
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
                                color = if (msg.deliveryStatus == DeliveryStatus.DELIVERED) Color(0xFF2E7D32) else Color(0xFFD32F2F),
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
    val radioConfig by bleManager.radioConfig.collectAsState()

    var kidName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    var channelIndexText by remember { mutableStateOf("1") }
    var channelNameInput by remember { mutableStateOf("Familie") }
    var channelAliasInput by remember { mutableStateOf("Familie") }
    var channelPskInput by remember { mutableStateOf("") }

    var freqKhzInput by remember { mutableStateOf(radioConfig.freqKhz.toString()) }
    var bwKhzInput by remember { mutableStateOf(radioConfig.bwKhz.toString()) }
    var sfInput by remember { mutableStateOf(radioConfig.sf.toString()) }
    var crInput by remember { mutableStateOf(radioConfig.cr.toString()) }

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
                Text("2. Mesh-Funk-Region (EU Narrow 868 MHz)", fontWeight = FontWeight.Bold)
                Text("Aktiv: ${radioConfig.presetLabel} | ${radioConfig.freqKhz} kHz, BW ${radioConfig.bwKhz} kHz, SF${radioConfig.sf}, CR${radioConfig.cr}", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    val euNarrow = RadioConfig(
                        presetLabel = "EU Narrow (868 MHz)",
                        freqKhz = 869618,
                        bwKhz = 62,
                        sf = 8,
                        cr = 8
                    )
                    freqKhzInput = euNarrow.freqKhz.toString()
                    bwKhzInput = euNarrow.bwKhz.toString()
                    sfInput = euNarrow.sf.toString()
                    crInput = euNarrow.cr.toString()
                    bleManager.applyRadioConfig(euNarrow)
                    infoBanner = "EU Narrow (869.618 MHz, BW 62 kHz, SF8, CR8) gesetzt."
                }) {
                    Text("EU Narrow (868 MHz) anwenden")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = freqKhzInput,
                        onValueChange = { freqKhzInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Freq (kHz)") }
                    )
                    OutlinedTextField(
                        value = bwKhzInput,
                        onValueChange = { bwKhzInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("BW") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sfInput,
                        onValueChange = { sfInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(76.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("SF") }
                    )
                    OutlinedTextField(
                        value = crInput,
                        onValueChange = { crInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(76.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("CR") }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val f = freqKhzInput.toIntOrNull() ?: 869618
                        val b = bwKhzInput.toIntOrNull() ?: 62
                        val s = sfInput.toIntOrNull() ?: 8
                        val c = crInput.toIntOrNull() ?: 8
                        bleManager.applyRadioConfig(RadioConfig("Benutzerdefiniert (868 MHz)", f, b, s, c))
                        infoBanner = "Funkwerte ($f kHz) an Funkgeraet gesendet."
                    }) {
                        Text("Uebernehmen")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("3. Kontakte & Alias-Namen (z. B. Mama, Papa)", fontWeight = FontWeight.Bold)
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
                Text("4. Gruppenchat / Kanal & PSK einrichten (ohne MeshCore-App)", fontWeight = FontWeight.Bold)
                Text("Gleicher Kanal-Index, Name und Schluessel (PSK) auf allen Familiengeraeten verhindert Zeichensalat.", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = channelIndexText,
                        onValueChange = { if (it.length <= 1) channelIndexText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(72.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Nr.") }
                    )
                    OutlinedTextField(
                        value = channelNameInput,
                        onValueChange = { channelNameInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Kanalname") },
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = channelAliasInput,
                        onValueChange = { channelAliasInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Alias im Chat (z. B. Familie)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = channelPskInput,
                        onValueChange = { channelPskInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("PSK / Geheimwort") },
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val idx = channelIndexText.toIntOrNull()
                        if (idx != null && idx in 0..7 && channelNameInput.isNotBlank()) {
                            bleManager.configureChannelOnDevice(idx, channelNameInput, channelPskInput)
                            if (channelAliasInput.isNotBlank()) {
                                bleManager.setChannelAlias(idx, channelAliasInput)
                            }
                            bleManager.toggleChannelAllowed(idx, true)
                            infoBanner = "Kanal $idx (${channelAliasInput.ifBlank { channelNameInput }}) gespeichert und freigegeben."
                        }
                    }) {
                        Text("Kanal speichern & freigeben")
                    }
                    OutlinedButton(onClick = { bleManager.syncChannels() }) {
                        Text("Laden")
                    }
                }
            }

            items(channels) { channel ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Kanal ${channel.index}: ${channel.displayName}", fontWeight = FontWeight.Medium)
                        Text(
                            text = "Geraetename: ${channel.originalName} • ${if (channel.isEncryptedPskSet) "Schluessel aktiv" else "Kein PSK"}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = channel.isAllowedForKid,
                        onCheckedChange = { bleManager.toggleChannelAllowed(channel.index, it) }
                    )
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("5. Anzeigename des Kindes im Mesh", fontWeight = FontWeight.Bold)
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
                Text("6. Eltern-PIN aendern", fontWeight = FontWeight.Bold)
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
        }
    }
}
