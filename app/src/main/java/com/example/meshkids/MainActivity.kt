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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

    fun checkBlePermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                bleManager.appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    bleManager.appContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                bleManager.appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val allOk = checkBlePermissionsGranted()
        permissionsGranted = allOk
        if (allOk) {
            bleManager.autoConnectIfSaved()
        }
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkBlePermissionsGranted()) {
            permissionsGranted = true
            bleManager.autoConnectIfSaved()
        } else {
            permissionLauncher.launch(perms)
        }
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
    val messages by bleManager.messages.collectAsState()

    val allowedTargets = remember(allContacts, allChannels, messages, unreadTargetIds) {
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
        val latestTsByTarget = mutableMapOf<String, Long>()
        for (m in messages) {
            val prev = latestTsByTarget[m.targetId]
            if (prev == null || m.timestamp > prev) {
                latestTsByTarget[m.targetId] = m.timestamp
            }
        }
        (channelTargets + contactTargets).sortedWith(
            compareByDescending<ChatTarget> { unreadTargetIds.contains(it.id) }
                .thenByDescending { latestTsByTarget[it.id] ?: 0L }
        )
    }

    val quickReplies by bleManager.quickReplies.collectAsState()

    var selectedTargetId by remember { mutableStateOf<String?>(null) }
    val selectedTarget = remember(selectedTargetId, allowedTargets) {
        allowedTargets.firstOrNull { it.id == selectedTargetId }
    }

    LaunchedEffect(selectedTarget?.id) {
        selectedTarget?.let { target ->
            bleManager.markTargetRead(target.id)
            val channelScope = target.channel?.regionScope?.trim().orEmpty()
            if (target.type == ChatTargetType.CHANNEL && channelScope.isNotEmpty()) {
                bleManager.applyChannelFloodScope(channelScope)
            }
        }
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
            checkBlePermissionsGranted = { checkBlePermissionsGranted() },
            requestBlePermissions = { perms -> permissionLauncher.launch(perms) },
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
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
                                text = if (isConnected) "Funkgerät bereit" else connectionState,
                                fontSize = 13.sp,
                                color = if (isConnected) Color(0xFFB9F6CA) else Color(0xFFFFCDD2)
                            )
                        }
                    }
                },
                actions = {
                    val hasOtherUnread = remember(unreadTargetIds, selectedTarget?.id) {
                        unreadTargetIds.any { it != selectedTarget?.id }
                    }
                    if (hasOtherUnread) {
                        val infiniteTransition = rememberInfiniteTransition(label = "otherChatBeacon")
                        val sweepAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1200, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "beaconAngle"
                        )
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.45f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 550, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "beaconPulse"
                        )
                        Box(
                            modifier = Modifier
                                .padding(end = 2.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD54F).copy(alpha = 0.22f * pulseAlpha))
                                .clickable { selectedTargetId = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(sweepAngle)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .align(Alignment.TopCenter)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFEB3B))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .align(Alignment.BottomCenter)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF7043).copy(alpha = 0.85f))
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5252))
                            )
                        }
                    }
                    IconButton(onClick = { showPinDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Admin-Einstellungen",
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
                text = "Bitte den Admin, oben rechts auf das Zahnrad zu tippen, um Kontakte oder Gruppenchats freizugeben.",
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
                                text = lastMsg?.text ?: if (isGroup) "Gruppenchat" else "Tippe hier zum Schreiben!",
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

private val LINK_REGEX = Regex("(https?://[^\\s]+|www\\.[^\\s]+)", RegexOption.IGNORE_CASE)

private fun buildLinkAnnotatedText(text: String, baseColor: Color, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var lastIdx = 0
        for (match in LINK_REGEX.findAll(text)) {
            if (match.range.first > lastIdx) {
                withStyle(SpanStyle(color = baseColor)) {
                    append(text.substring(lastIdx, match.range.first))
                }
            }
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(match.value)
            }
            lastIdx = match.range.last + 1
        }
        if (lastIdx < text.length) {
            withStyle(SpanStyle(color = baseColor)) {
                append(text.substring(lastIdx))
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
    val clipboardManager = LocalClipboardManager.current

    var replyTargetMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var replyDraftText by remember { mutableStateOf("") }
    var copiedLinkText by remember { mutableStateOf<String?>(null) }

    val blueGroupColor = Color(0xFF1E88E5)
    val greenDeliveredColor = Color(0xFF2E7D32)
    val redPendingOrFailedColor = Color(0xFFD32F2F)
    val channelScope = target.channel?.regionScope?.trim().orEmpty()

    if (copiedLinkText != null) {
        AlertDialog(
            onDismissRequest = { copiedLinkText = null },
            title = { Text("Link erkannt") },
            text = {
                Text(
                    text = copiedLinkText!!,
                    color = Color(0xFF0D47A1),
                    textDecoration = TextDecoration.Underline
                )
            },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString(copiedLinkText!!))
                    copiedLinkText = null
                }) {
                    Text("Link kopieren")
                }
            },
            dismissButton = {
                TextButton(onClick = { copiedLinkText = null }) {
                    Text("Schließen")
                }
            }
        )
    }

    if (replyTargetMsg != null) {
        val targetMessage = replyTargetMsg!!
        val recipientChannelName = targetMessage.rawSenderName?.trim().orEmpty()
            .ifBlank { targetMessage.senderName?.trim().orEmpty() }
            .ifBlank { "Sender" }
        AlertDialog(
            onDismissRequest = { replyTargetMsg = null },
            title = { Text("Antwort an $recipientChannelName") },
            text = {
                Column {
                    Text(
                        text = "Nachricht: ${targetMessage.text}",
                        fontSize = 13.sp,
                        color = Color.DarkGray,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = replyDraftText,
                        onValueChange = { if (it.length <= 133) replyDraftText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("$recipientChannelName: ...") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = isConnected && replyDraftText.isNotBlank(),
                    onClick = {
                        val prefix = "$recipientChannelName: "
                        val trimmedBody = replyDraftText.trim()
                        val fullMsg = if (trimmedBody.startsWith(prefix, ignoreCase = true)) {
                            trimmedBody
                        } else {
                            "$prefix$trimmedBody"
                        }.take(133)
                        onSendMessage(fullMsg)
                        replyTargetMsg = null
                        replyDraftText = ""
                    }
                ) {
                    Text("Senden")
                }
            },
            dismissButton = {
                TextButton(onClick = { replyTargetMsg = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (target.type == ChatTargetType.CHANNEL) {
            Surface(
                color = Color(0xFFE3F2FD),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (channelScope.isNotEmpty()) "Scope / Region: $channelScope" else "Scope / Region: Standard (kein Scope gesetzt)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0D47A1),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isGroupMsg = target.type == ChatTargetType.CHANNEL ||
                    msg.deliveryStatus == DeliveryStatus.GROUP_SENT ||
                    msg.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER ||
                    msg.deliveryStatus == DeliveryStatus.GROUP_REPEATER_HEARD
                val bubbleColor = when {
                    !msg.isFromMe -> Color.White
                    msg.deliveryStatus == DeliveryStatus.GROUP_REPEATER_HEARD -> greenDeliveredColor
                    msg.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER -> Color(0xFFEF6C00)
                    isGroupMsg -> blueGroupColor
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> greenDeliveredColor
                    else -> redPendingOrFailedColor
                }
                val textColor = if (msg.isFromMe) Color.White else Color.Black
                val linkColor = if (msg.isFromMe) Color(0xFFFFF59D) else Color(0xFF1565C0)
                val foundLink = remember(msg.text) { LINK_REGEX.find(msg.text)?.value }
                val canReplyInGroup = !msg.isFromMe && target.type == ChatTargetType.CHANNEL

                val statusLabel = when {
                    msg.deliveryStatus == DeliveryStatus.GROUP_REPEATER_HEARD -> "Vom Repeater gehört"
                    msg.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER -> "Sende / warte auf Repeater..."
                    isGroupMsg -> "Gesendet (Gruppe)"
                    msg.deliveryStatus == DeliveryStatus.DELIVERED -> "Zugestellt"
                    msg.deliveryStatus == DeliveryStatus.FAILED -> "Keine Zustellbestätigung"
                    else -> "Warte auf Zustellbestätigung..."
                }
                val statusColor = when {
                    msg.deliveryStatus == DeliveryStatus.GROUP_REPEATER_HEARD -> greenDeliveredColor
                    msg.deliveryStatus == DeliveryStatus.GROUP_WAITING_REPEATER -> Color(0xFFEF6C00)
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
                            shadowElevation = 2.dp,
                            modifier = Modifier.pointerInput(msg.id, canReplyInGroup, foundLink) {
                                detectTapGestures(
                                    onTap = {
                                        if (foundLink != null) {
                                            copiedLinkText = foundLink
                                        }
                                    },
                                    onPress = {
                                        if (!canReplyInGroup) return@detectTapGestures
                                        tryAwaitRelease()
                                    },
                                    onLongPress = {
                                        if (canReplyInGroup) {
                                            val recipientChannelName = msg.rawSenderName?.trim().orEmpty()
                                                .ifBlank { msg.senderName?.trim().orEmpty() }
                                                .ifBlank { "Sender" }
                                            replyTargetMsg = msg
                                            replyDraftText = "$recipientChannelName: "
                                        }
                                    }
                                )
                            }
                        ) {
                            Text(
                                text = buildLinkAnnotatedText(msg.text, textColor, linkColor),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                fontSize = 18.sp
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

    fun appendDigit(d: String) {
        if (enteredPin.length < 12) {
            enteredPin += d
            error = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin-Bereich geschützt") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Bitte Admin-PIN eingeben (Standard: 1234):")
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { raw ->
                        enteredPin = raw.filter { it.isDigit() }.take(12)
                        error = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error
                )
                if (error) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Falsche PIN!", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))

                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "OK")
                )
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            val isOk = key == "OK"
                            val isBack = key == "⌫"
                            if (isOk) {
                                Button(
                                    onClick = {
                                        if (pinManager.verifyPin(enteredPin)) onUnlocked() else error = true
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(key, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            } else if (isBack) {
                                OutlinedButton(
                                    onClick = {
                                        if (enteredPin.isNotEmpty()) {
                                            enteredPin = enteredPin.dropLast(1)
                                            error = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(key, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { appendDigit(key) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(key, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
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
    checkBlePermissionsGranted: () -> Boolean,
    requestBlePermissions: (Array<String>) -> Unit,
    onBack: () -> Unit
) {
    val devices by bleManager.discoveredDevices.collectAsState()
    val contacts by bleManager.contacts.collectAsState()
    val channels by bleManager.channels.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()

    val manualGroupAliases by bleManager.manualGroupAliases.collectAsState()
    var manualChatName by remember { mutableStateOf("") }
    var manualAliasName by remember { mutableStateOf("") }
    var kidName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    var infoBanner by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Admin-Einstellungen")
                        Text(
                            text = "MeshKidsChat v${MeshCoreBleManager.APP_VERSION}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
                Text("1. MeshCore Funkgerät koppeln ($connectionState)", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (!checkBlePermissionsGranted()) {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            infoBanner = "Bitte Bluetooth-Berechtigungen erteilen..."
                            requestBlePermissions(perms)
                        } else {
                            val started = bleManager.startScan()
                            infoBanner = if (started) {
                                "BLE-Suche läuft (8 Sek.)..."
                            } else {
                                bleManager.connectionState.value
                            }
                        }
                    }) {
                        Text("BLE suchen")
                    }
                    OutlinedButton(onClick = {
                        bleManager.sendAdvertNow()
                        infoBanner = "Gerätesignal (Advert) gesendet."
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
                    Text("${device.name ?: "MeshCore Gerät"} (${device.address})")
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("2. Kontakte & Alias-Namen (z. B. Mama, Papa)", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = { bleManager.syncContacts() }) {
                    Text("Kontakte vom Funkgerät laden")
                }
            }

            item {
                var contactSearch by remember { mutableStateOf("") }
                var contactPage by remember { mutableStateOf(0) }
                val pageSize = 10

                val sortedContacts = remember(contacts, contactSearch) {
                    val q = contactSearch.trim().lowercase()
                    contacts
                        .filter { c -> c.nodeType != 2 }
                        .filter { c ->
                            q.isEmpty() ||
                                c.displayName.lowercase().contains(q) ||
                                c.originalName.lowercase().contains(q) ||
                                c.pubKeyPrefixHex.lowercase().contains(q)
                        }
                        .sortedWith(
                            compareByDescending<MeshContact> { it.isAllowedForKid }
                                .thenBy { it.displayName.lowercase() }
                        )
                }

                val totalPages = ((sortedContacts.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                val safePage = contactPage.coerceIn(0, totalPages - 1)
                LaunchedEffect(totalPages) {
                    if (contactPage >= totalPages) contactPage = (totalPages - 1).coerceAtLeast(0)
                }

                val pageContacts = remember(sortedContacts, safePage) {
                    val fromIdx = safePage * pageSize
                    val toIdx = minOf(fromIdx + pageSize, sortedContacts.size)
                    if (fromIdx < toIdx) sortedContacts.subList(fromIdx, toIdx) else emptyList()
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAFC))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        OutlinedTextField(
                            value = contactSearch,
                            onValueChange = {
                                contactSearch = it
                                contactPage = 0
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Kontakte filtern (Name oder Hex)...") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { if (safePage > 0) contactPage = safePage - 1 },
                                enabled = safePage > 0
                            ) {
                                Text("◀ Zurück")
                            }
                            Text(
                                text = "Seite ${safePage + 1} / $totalPages (${sortedContacts.size} Kontakte)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedButton(
                                onClick = { if (safePage < totalPages - 1) contactPage = safePage + 1 },
                                enabled = safePage < totalPages - 1
                            ) {
                                Text("Weiter ▶")
                            }
                        }

                        if (pageContacts.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Keine Kontakte gefunden.",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        } else {
                            pageContacts.forEach { contact ->
                                var aliasField by remember(contact.pubKeyPrefixHex, contact.aliasName) {
                                    mutableStateOf(contact.aliasName)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val typeTag = if (contact.nodeType == 3) " • Roomserver" else ""
                                            Text(text = contact.displayName, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "${contact.originalName} (${contact.pubKeyPrefixHex})$typeTag",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                        }
                                        Switch(
                                            checked = contact.isAllowedForKid,
                                            onCheckedChange = {
                                                bleManager.toggleContactAllowed(contact.pubKeyPrefixHex, it)
                                            }
                                        )
                                    }
                                    var aliasFocused by remember(contact.pubKeyPrefixHex) { mutableStateOf(false) }
                                    OutlinedTextField(
                                        value = aliasField,
                                        onValueChange = { aliasField = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                val nowFocused = focusState.isFocused
                                                if (aliasFocused && !nowFocused && aliasField != contact.aliasName) {
                                                    bleManager.setContactAlias(contact.pubKeyPrefixHex, aliasField)
                                                    infoBanner = "Alias für Kontakt gespeichert."
                                                }
                                                aliasFocused = nowFocused
                                            },
                                        placeholder = { Text("Alias (z. B. Mama) – wird beim Verlassen gespeichert") },
                                        singleLine = true
                                    )
                                }
                            }
                        }

                        if (totalPages > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { if (safePage > 0) contactPage = safePage - 1 },
                                    enabled = safePage > 0
                                ) {
                                    Text("◀ Zurück")
                                }
                                Text(
                                    text = "Seite ${safePage + 1} / $totalPages",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                                OutlinedButton(
                                    onClick = { if (safePage < totalPages - 1) contactPage = safePage + 1 },
                                    enabled = safePage < totalPages - 1
                                ) {
                                    Text("Weiter ▶")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Zusätzliche Alias-Namen nur für Gruppenchats (ohne Geräte-Kontakt)", fontWeight = FontWeight.SemiBold)
                Text("Falls ein Absender aus dem Chat nicht in der Geräte-Kontaktliste steht, hier Namen aus dem Chat und Alias eintragen.", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = manualChatName,
                    onValueChange = { if (it.length <= 48) manualChatName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name aus dem Chat (z. B. NW-E-4711)") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualAliasName,
                        onValueChange = { if (it.length <= 24) manualAliasName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Gewünschter Alias (z. B. Dieter)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (manualChatName.isNotBlank() && manualAliasName.isNotBlank()) {
                            bleManager.addOrUpdateManualGroupAlias(manualChatName, manualAliasName)
                            infoBanner = "Gruppen-Alias '${manualAliasName.trim()}' für '${manualChatName.trim()}' gespeichert."
                            manualChatName = ""
                            manualAliasName = ""
                        }
                    }) {
                        Text("Hinzufügen")
                    }
                }
            }

            items(manualGroupAliases.entries.toList(), key = { "manual_${it.key}" }) { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = entry.value, fontWeight = FontWeight.SemiBold)
                        Text(text = "Chat-Name: ${entry.key}", fontSize = 12.sp, color = Color.Gray)
                    }
                    TextButton(onClick = {
                        bleManager.removeManualGroupAlias(entry.key)
                        infoBanner = "Gruppen-Alias für '${entry.key}' entfernt."
                    }) {
                        Text("Löschen")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("3. Gruppenchat / Kanäle aus Gerät übernehmen", fontWeight = FontWeight.Bold)
                Text("Kanäle werden nur aus dem Funkgerät ausgelesen und nicht neu angelegt oder überschrieben. Du kannst hier einen Alias vergeben und den Chat freigeben.", fontSize = 13.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    bleManager.syncChannelsFromDevice()
                    infoBanner = "Kanäle werden aus dem Funkgerät geladen..."
                }) {
                    Text("Kanäle aus Gerät laden")
                }
                if (channels.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Noch keine Kanäle vom Gerät übernommen. Bitte mit Funkgerät verbinden und auf 'Kanäle aus Gerät laden' tippen.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }

            item {
                var channelSearch by remember { mutableStateOf("") }
                var channelPage by remember { mutableStateOf(0) }
                val channelPageSize = 5

                val sortedChannels = remember(channels, channelSearch) {
                    val q = channelSearch.trim().lowercase()
                    channels
                        .filter { ch ->
                            q.isEmpty() ||
                                ch.displayName.lowercase().contains(q) ||
                                ch.originalName.lowercase().contains(q) ||
                                ch.regionScope.lowercase().contains(q) ||
                                ch.index.toString() == q
                        }
                        .sortedWith(
                            compareByDescending<MeshChannel> { it.isAllowedForKid }
                                .thenBy { it.index }
                        )
                }

                val totalChannelPages = ((sortedChannels.size + channelPageSize - 1) / channelPageSize).coerceAtLeast(1)
                val safeChannelPage = channelPage.coerceIn(0, totalChannelPages - 1)
                LaunchedEffect(totalChannelPages) {
                    if (channelPage >= totalChannelPages) channelPage = (totalChannelPages - 1).coerceAtLeast(0)
                }

                val pageChannels = remember(sortedChannels, safeChannelPage) {
                    val fromIdx = safeChannelPage * channelPageSize
                    val toIdx = minOf(fromIdx + channelPageSize, sortedChannels.size)
                    if (fromIdx < toIdx) sortedChannels.subList(fromIdx, toIdx) else emptyList()
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAFC))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        OutlinedTextField(
                            value = channelSearch,
                            onValueChange = {
                                channelSearch = it
                                channelPage = 0
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Kanäle filtern (Name, Scope oder Nr.)...") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { if (safeChannelPage > 0) channelPage = safeChannelPage - 1 },
                                enabled = safeChannelPage > 0
                            ) {
                                Text("◀ Zurück")
                            }
                            Text(
                                text = "Seite ${safeChannelPage + 1} / $totalChannelPages (${sortedChannels.size} Kanäle)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedButton(
                                onClick = { if (safeChannelPage < totalChannelPages - 1) channelPage = safeChannelPage + 1 },
                                enabled = safeChannelPage < totalChannelPages - 1
                            ) {
                                Text("Weiter ▶")
                            }
                        }

                        if (pageChannels.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Keine Kanäle gefunden.",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        } else {
                            pageChannels.forEach { channel ->
                                var aliasField by remember(channel.index, channel.aliasName) {
                                    mutableStateOf(channel.aliasName)
                                }
                                var scopeField by remember(channel.index, channel.regionScope) {
                                    mutableStateOf(channel.regionScope)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "Kanal ${channel.index}: ${channel.displayName}", fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "Gerätekanal: ${channel.originalName} • ${if (channel.isEncryptedPskSet) "PSK im Gerät" else "Offen / kein PSK"}",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                            Text(
                                                text = "Scope / Region: ${channel.regionScope.ifBlank { "Standard" }}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1565C0)
                                            )
                                        }
                                        Switch(
                                            checked = channel.isAllowedForKid,
                                            onCheckedChange = { bleManager.toggleChannelAllowed(channel.index, it) }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    var chAliasFocused by remember(channel.index) { mutableStateOf(false) }
                                    OutlinedTextField(
                                        value = aliasField,
                                        onValueChange = { aliasField = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                val nowFocused = focusState.isFocused
                                                if (chAliasFocused && !nowFocused && aliasField != channel.aliasName) {
                                                    bleManager.setChannelAlias(channel.index, aliasField)
                                                    infoBanner = "Alias für Kanal ${channel.index} gespeichert."
                                                }
                                                chAliasFocused = nowFocused
                                            },
                                        placeholder = { Text("Alias (z. B. Familie) – wird beim Verlassen gespeichert") },
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    var scopeFocused by remember(channel.index) { mutableStateOf(false) }
                                    var scopeMenuExpanded by remember(channel.index) { mutableStateOf(false) }
                                    val filteredScopes = remember(scopeField) {
                                        val q = scopeField.trim().lowercase()
                                        if (q.isEmpty()) {
                                            MeshCoreBleManager.KNOWN_SCOPES.take(10)
                                        } else {
                                            val startsWithMatches = MeshCoreBleManager.KNOWN_SCOPES.filter {
                                                it.lowercase().startsWith(q)
                                            }
                                            val containsMatches = MeshCoreBleManager.KNOWN_SCOPES.filter {
                                                !it.lowercase().startsWith(q) && it.lowercase().contains(q)
                                            }
                                            (startsWithMatches + containsMatches).take(10)
                                        }
                                    }

                                    ExposedDropdownMenuBox(
                                        expanded = scopeMenuExpanded && filteredScopes.isNotEmpty(),
                                        onExpandedChange = { expanded ->
                                            scopeMenuExpanded = expanded && filteredScopes.isNotEmpty()
                                        }
                                    ) {
                                        OutlinedTextField(
                                            value = scopeField,
                                            onValueChange = { raw ->
                                                if (raw.length <= 32) {
                                                    scopeField = raw
                                                    scopeMenuExpanded = filteredScopes.isNotEmpty()
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor()
                                                .onFocusChanged { focusState ->
                                                    val nowFocused = focusState.isFocused
                                                    if (nowFocused && filteredScopes.isNotEmpty()) {
                                                        scopeMenuExpanded = true
                                                    } else if (scopeFocused && !nowFocused) {
                                                        scopeMenuExpanded = false
                                                        if (scopeField != channel.regionScope) {
                                                            bleManager.setChannelRegionScope(channel.index, scopeField)
                                                            infoBanner = "Scope für Kanal ${channel.index} gespeichert."
                                                        }
                                                    }
                                                    scopeFocused = nowFocused
                                                },
                                            placeholder = { Text("Scope / Region (z. B. de-nw, nrw, oder frei eintippen)") },
                                            singleLine = true
                                        )
                                        ExposedDropdownMenu(
                                            expanded = scopeMenuExpanded && filteredScopes.isNotEmpty(),
                                            onDismissRequest = { scopeMenuExpanded = false }
                                        ) {
                                            filteredScopes.forEach { candidateScope ->
                                                DropdownMenuItem(
                                                    text = { Text(candidateScope) },
                                                    onClick = {
                                                        scopeField = candidateScope
                                                        scopeMenuExpanded = false
                                                        bleManager.setChannelRegionScope(channel.index, candidateScope)
                                                        infoBanner = "Scope '$candidateScope' für Kanal ${channel.index} gespeichert."
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (totalChannelPages > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { if (safeChannelPage > 0) channelPage = safeChannelPage - 1 },
                                    enabled = safeChannelPage > 0
                                ) {
                                    Text("◀ Zurück")
                                }
                                Text(
                                    text = "Seite ${safeChannelPage + 1} / $totalChannelPages",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                                OutlinedButton(
                                    onClick = { if (safeChannelPage < totalChannelPages - 1) channelPage = safeChannelPage + 1 },
                                    enabled = safeChannelPage < totalChannelPages - 1
                                ) {
                                    Text("Weiter ▶")
                                }
                            }
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
                            infoBanner = "Gerätename '$kidName' gesetzt."
                        }
                    }) {
                        Text("Speichern")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("5. Admin-PIN ändern", fontWeight = FontWeight.Bold)
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
                            infoBanner = "Admin-PIN wurde geändert."
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
                Text("Diese Felder erscheinen direkt über dem Eingabefeld im Chat und können angepasst werden.", fontSize = 13.sp, color = Color.Gray)
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
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "App-Version: ${MeshCoreBleManager.APP_VERSION}",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
