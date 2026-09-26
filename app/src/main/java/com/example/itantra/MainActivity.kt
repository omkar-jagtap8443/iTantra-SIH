package com.example.itantra

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.itantra.core.AppConstants
import com.example.itantra.core.Language
import com.example.itantra.core.ServiceLocator
import com.example.itantra.data.model.WireMessage
import com.example.itantra.data.prefs.Contact
import com.example.itantra.data.prefs.ContactsStore
import com.example.itantra.data.prefs.DeviceIdStore
import com.example.itantra.service.MessageService
import com.example.itantra.transport.BluetoothConnection
import com.example.itantra.transport.BluetoothServer
import com.example.itantra.transport.BluetoothTransport
import com.example.itantra.transport.DiscoveredDevice
import com.example.itantra.transport.DiscoveryService
import com.example.itantra.transport.LocalTcpTransport
import com.example.itantra.transport.TcpServer
import com.example.itantra.ui.theme.ITantraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var myDeviceId: String
    private val transport = LocalTcpTransport()
    private val server = TcpServer()
    private lateinit var discovery: DiscoveryService

    private val btServer = BluetoothServer()
    private val btTransport = BluetoothTransport()

    private val lastReceived = mutableStateOf("")
    private val lastReceivedFrom = mutableStateOf("")
    private val lastReceivedLang = mutableStateOf("")

    private val btStatus = mutableStateOf("Not connected")
    private val btPeerName = mutableStateOf<String?>(null)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())

        ServiceLocator.ttsManager.init(this)

        // Start background service so SOS works even when app is closed
        MessageService.start(this)

        myDeviceId = DeviceIdStore.getOrCreateId(this)
        val myName = DeviceIdStore.getName(this).ifBlank { myDeviceId }

        discovery = DiscoveryService(this)
        discovery.start(lifecycleScope, myDeviceId, myName)

        server.start(lifecycleScope)

        lifecycleScope.launch {
            server.incoming.collect { msg -> handleIncoming(msg) }
        }
        lifecycleScope.launch {
            btServer.incoming.collect { msg -> handleIncoming(msg) }
        }
        lifecycleScope.launch {
            btTransport.incoming.collect { msg -> handleIncoming(msg) }
        }
        lifecycleScope.launch {
            btServer.status.collect { s -> btStatus.value = s }
        }
        lifecycleScope.launch {
            BluetoothConnection.connectedPeer.collect { peer ->
                btPeerName.value = peer
                if (peer == null) btStatus.value = "Not connected"
            }
        }

        setContent {
            ITantraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    private fun handleIncoming(msg: WireMessage) {
        Log.i(AppConstants.TAG, "handleIncoming: '${msg.payload}' priority=${msg.priority}")
        runOnUiThread {
            lastReceived.value = msg.payload
            lastReceivedFrom.value = msg.from
            lastReceivedLang.value = Language.fromTag(msg.lang).display
        }
        lifecycleScope.launch {
            ServiceLocator.ttsManager.speak(msg.payload, Language.fromTag(msg.lang))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        val ctx = this@MainActivity

        var myName by remember {
            mutableStateOf(DeviceIdStore.getName(ctx).ifBlank { myDeviceId })
        }
        var showNameDialog by remember { mutableStateOf(false) }
        var showSaveContactDialog by remember { mutableStateOf<DiscoveredDevice?>(null) }
        var showSaveSenderDialog by remember { mutableStateOf<String?>(null) }
        var saveName by remember { mutableStateOf("") }

        val nearby by discovery.devices.collectAsState()
        var contacts by remember { mutableStateOf(ContactsStore.load(ctx).toList()) }
        var selectedContact by remember { mutableStateOf<Contact?>(null) }

        var lang by remember { mutableStateOf(Language.HINDI) }
        var status by remember { mutableStateOf("Ready") }
        var lastSent by remember { mutableStateOf("") }
        var lastSentTo by remember { mutableStateOf("") }
        var isListening by remember { mutableStateOf(false) }
        var liveText by remember { mutableStateOf("") }

        val recvText by lastReceived
        val recvFrom by lastReceivedFrom
        val recvLang by lastReceivedLang
        val btStatusVal by btStatus
        val btPeerVal by btPeerName
        var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        val btConnected = btPeerVal != null

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "iTANTRA",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                myName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showNameDialog = true }) {
                            Icon(Icons.Default.Person, contentDescription = "Edit name")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ---- STATUS CARD ----
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Status",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            if (liveText.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    liveText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // ---- BLUETOOTH SECTION ----
                SectionHeader("Bluetooth")
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (btConnected) Icons.Default.BluetoothConnected
                                else Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (btConnected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                btStatusVal,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    btServer.start(ctx, lifecycleScope)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.BluetoothSearching,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Listen")
                            }
                            OutlinedButton(
                                onClick = {
                                    pairedDevices = btTransport.listPairedDevices(ctx)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Paired")
                            }
                        }

                        if (pairedDevices.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Paired Devices",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            pairedDevices.forEach { device ->
                                val name = try { device.name } catch (_: SecurityException) { "Unknown" }
                                OutlinedCard(
                                    onClick = {
                                        lifecycleScope.launch {
                                            status = "Connecting to $name…"
                                            val r = btTransport.connect(ctx, device)
                                            status = if (r.isSuccess) "Connected to $name"
                                            else "Failed: ${r.exceptionOrNull()?.message}"
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(name, fontWeight = FontWeight.Medium)
                                            Text(
                                                device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (btConnected) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    btTransport.disconnect()
                                    status = "Disconnected"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }

                // ---- SENDING TO ----
                if (selectedContact != null) {
                    SectionHeader("Recipient")
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    selectedContact!!.name.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    selectedContact!!.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    selectedContact!!.deviceId,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { selectedContact = null }) {
                                Text("Change")
                            }
                        }
                    }
                } else if (btConnected) {
                    SectionHeader("Recipient")
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    btPeerVal ?: "Bluetooth peer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "via Bluetooth",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // ---- NEARBY ----
                SectionHeader("Nearby Devices")
                val others = nearby.filter { it.deviceId != myDeviceId }
                if (others.isEmpty()) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Searching…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    others.forEach { dev ->
                        NearbyDeviceRow(
                            device = dev,
                            onSave = {
                                saveName = dev.displayName
                                showSaveContactDialog = dev
                            }
                        )
                    }
                }

                // ---- CONTACTS ----
                SectionHeader("Contacts")
                if (contacts.isEmpty()) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No contacts saved yet",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    contacts.forEach { c ->
                        ContactRow(
                            contact = c,
                            isSelected = selectedContact?.deviceId == c.deviceId,
                            onSelect = { selectedContact = c },
                            onDelete = {
                                ContactsStore.remove(ctx, c.deviceId)
                                contacts = ContactsStore.load(ctx).toList()
                                if (selectedContact?.deviceId == c.deviceId)
                                    selectedContact = null
                            }
                        )
                    }
                }

                // ---- LANGUAGE ----
                SectionHeader("Language")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Language.entries.forEach { l ->
                        FilterChip(
                            selected = lang == l,
                            onClick = { lang = l },
                            label = { Text(l.display) }
                        )
                    }
                }

                // ---- SOS BUTTON ----
                SectionHeader("Emergency")
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Send an emergency alert to the selected contact. The receiver's phone will ring, vibrate and show a full-screen alert — even if their app is closed.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val target = selectedContact
                                if (target == null && !btConnected) {
                                    status = "Select a contact or connect Bluetooth first"
                                    return@Button
                                }
                                val sosText = if (lastSent.isNotBlank()) lastSent
                                else "SOS — Emergency. Please respond immediately."
                                lifecycleScope.launch {
                                    try {
                                        val msg = WireMessage(
                                            from = myDeviceId,
                                            lang = lang.name,
                                            payload = sosText,
                                            priority = "SOS",
                                            senderName = myName
                                        )
                                        val ok = if (btTransport.isConnected()) {
                                            btTransport.send(msg)
                                        } else {
                                            val t = selectedContact
                                            val ip = if (t != null) resolveIp(t.deviceId, nearby, t) else null
                                            if (ip != null) {
                                                transport.connect(ip, AppConstants.DEFAULT_PORT)
                                                transport.send(msg)
                                                transport.close()
                                                true
                                            } else false
                                        }
                                        lastSent = sosText
                                        lastSentTo = btPeerVal ?: selectedContact?.name ?: "peer"
                                        status = if (ok) "SOS SENT" else "SOS send failed"
                                    } catch (e: Exception) {
                                        Log.e(AppConstants.TAG, "SOS send failed", e)
                                        status = "SOS error: ${e.message}"
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = RoundedCornerShape(32.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "SEND SOS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ---- TALK BUTTON ----
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (isListening) {
                                    ServiceLocator.voskSttManager.stop()
                                    isListening = false
                                    liveText = ""
                                    status = "Stopped"
                                    return@FilledIconButton
                                }

                                val target = selectedContact
                                if (target == null && !btConnected) {
                                    status = "Select a contact or connect Bluetooth"
                                    return@FilledIconButton
                                }

                                isListening = true
                                liveText = ""
                                status = "Listening in ${lang.display}…"

                                ServiceLocator.voskSttManager.start(
                                    context = ctx,
                                    lang = lang,
                                    onResult = { text ->
                                        isListening = false
                                        liveText = ""
                                        status = "Ready"
                                        lastSent = text

                                        if (text.isNotBlank()) {
                                            lifecycleScope.launch {
                                                try {
                                                    val msg = WireMessage(
                                                        from = myDeviceId,
                                                        lang = lang.name,
                                                        payload = text,
                                                        priority = "NORMAL",
                                                        senderName = myName
                                                    )
                                                    val ok = if (btTransport.isConnected()) {
                                                        btTransport.send(msg)
                                                    } else {
                                                        val t = selectedContact
                                                        val ip = if (t != null)
                                                            resolveIp(t.deviceId, nearby, t)
                                                        else null
                                                        if (ip != null) {
                                                            transport.connect(ip, AppConstants.DEFAULT_PORT)
                                                            transport.send(msg)
                                                            transport.close()
                                                            true
                                                        } else false
                                                    }
                                                    lastSentTo = btPeerVal ?: selectedContact?.name ?: "peer"
                                                    status = if (ok) {
                                                        if (btTransport.isConnected()) "Sent via Bluetooth"
                                                        else "Sent to ${selectedContact?.name}"
                                                    } else "Send failed"
                                                } catch (e: Exception) {
                                                    Log.e(AppConstants.TAG, "send failed", e)
                                                    status = "Send error: ${e.message}"
                                                }
                                            }
                                        }
                                    },
                                    onPartial = { partial ->
                                        liveText = partial
                                        status = "Hearing…"
                                    },
                                    onError = { err ->
                                        isListening = false
                                        liveText = ""
                                        status = err
                                    }
                                )
                            },
                            modifier = Modifier.size(96.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isListening)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Push to talk",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                isListening -> "Listening… tap to stop"
                                selectedContact == null && !btConnected -> "Select a contact first"
                                else -> "Tap to speak"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (btTransport.isConnected()) "Mode: Bluetooth"
                            else "Mode: Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ---- LAST SENT ----
                if (lastSent.isNotBlank()) {
                    SectionHeader("Last Sent")
                    MessageBubble(
                        header = "You → ${lastSentTo}",
                        subtitle = lang.display,
                        text = lastSent,
                        outgoing = true
                    )
                }

                // ---- LAST RECEIVED ----
                if (recvText.isNotBlank()) {
                    SectionHeader("Last Received")
                    MessageBubble(
                        header = friendlyName(recvFrom, contacts),
                        subtitle = recvLang,
                        text = recvText,
                        outgoing = false
                    )

                    val alreadySaved = contacts.any { it.deviceId == recvFrom }
                    if (!alreadySaved && recvFrom.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                saveName = friendlyName(recvFrom, contacts)
                                showSaveSenderDialog = recvFrom
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save ${friendlyName(recvFrom, contacts)}")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ---- DIALOGS ----
            if (showNameDialog) {
                AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { Text("Your Display Name") },
                    text = {
                        OutlinedTextField(
                            value = myName,
                            onValueChange = { myName = it },
                            singleLine = true,
                            label = { Text("Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = myName.trim().ifBlank { myDeviceId }
                            DeviceIdStore.setName(ctx, n)
                            myName = n
                            showNameDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
                    }
                )
            }

            val saving = showSaveContactDialog
            if (saving != null) {
                AlertDialog(
                    onDismissRequest = { showSaveContactDialog = null },
                    title = { Text("Save Contact") },
                    text = {
                        Column {
                            Text("Device: ${saving.deviceId}",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                singleLine = true,
                                label = { Text("Save as") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = saveName.trim().ifBlank { saving.deviceId }
                            ContactsStore.addOrUpdate(ctx, Contact(saving.deviceId, n, saving.ipAddress))
                            contacts = ContactsStore.load(ctx).toList()
                            showSaveContactDialog = null
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveContactDialog = null }) { Text("Cancel") }
                    }
                )
            }

            val senderId = showSaveSenderDialog
            if (senderId != null) {
                AlertDialog(
                    onDismissRequest = { showSaveSenderDialog = null },
                    title = { Text("Save Sender") },
                    text = {
                        Column {
                            Text("Device: $senderId",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                singleLine = true,
                                label = { Text("Save as") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = saveName.trim().ifBlank { senderId }
                            ContactsStore.addOrUpdate(ctx, Contact(senderId, n, ""))
                            contacts = ContactsStore.load(ctx).toList()
                            showSaveSenderDialog = null
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveSenderDialog = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }

    private fun resolveIp(
        deviceId: String,
        nearby: List<DiscoveredDevice>,
        contact: Contact
    ): String? {
        nearby.firstOrNull { it.deviceId == deviceId }?.let { return it.ipAddress }
        return contact.lastKnownIp.ifBlank { null }
    }

    private fun friendlyName(deviceId: String, contacts: List<Contact>): String {
        return contacts.firstOrNull { it.deviceId == deviceId }?.name ?: deviceId
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    @Composable
    private fun NearbyDeviceRow(device: DiscoveredDevice, onSave: () -> Unit) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        device.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onSave) { Text("Save") }
            }
        }
    }

    @Composable
    private fun ContactRow(
        contact: Contact,
        isSelected: Boolean,
        onSelect: () -> Unit,
        onDelete: () -> Unit
    ) {
        ElevatedCard(
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        contact.name.take(1).uppercase(),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(contact.name, fontWeight = FontWeight.Medium)
                    Text(
                        contact.deviceId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }

    @Composable
    private fun MessageBubble(
        header: String,
        subtitle: String,
        text: String,
        outgoing: Boolean
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (outgoing)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        header,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    override fun onDestroy() {
        ServiceLocator.voskSttManager.release()
        ServiceLocator.ttsManager.shutdown()
        transport.close()
        server.stop()
        discovery.stop()
        btServer.stop()
        btTransport.disconnect()
        super.onDestroy()
    }
}