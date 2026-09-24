package com.example.itantra

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    ) { results ->
        results.forEach { (perm, granted) ->
            Log.i(AppConstants.TAG, "Permission $perm granted: $granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        permLauncher.launch(perms.toTypedArray())

        ServiceLocator.ttsManager.init(this)

        myDeviceId = DeviceIdStore.getOrCreateId(this)
        val myName = DeviceIdStore.getName(this).ifBlank { myDeviceId }

        discovery = DiscoveryService(this)
        discovery.start(lifecycleScope, myDeviceId, myName)

        server.start(lifecycleScope)

        // --- Wi-Fi incoming ---
        lifecycleScope.launch {
            server.incoming.collect { msg -> handleIncoming(msg) }
        }

        // --- Bluetooth SERVER incoming (this phone was the "Start Listening" side) ---
        lifecycleScope.launch {
            btServer.incoming.collect { msg ->
                Log.i(AppConstants.TAG, "BT server incoming: ${msg.payload}")
                handleIncoming(msg)
            }
        }

        // --- Bluetooth CLIENT incoming (this phone connected to a server) ---
        lifecycleScope.launch {
            btTransport.incoming.collect { msg ->
                Log.i(AppConstants.TAG, "BT client incoming: ${msg.payload}")
                handleIncoming(msg)
            }
        }

        lifecycleScope.launch {
            btServer.status.collect { s ->
                Log.i(AppConstants.TAG, "BT status: $s")
                btStatus.value = s
            }
        }

        lifecycleScope.launch {
            BluetoothConnection.connectedPeer.collect { peer ->
                Log.i(AppConstants.TAG, "BT peer: $peer")
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

    /**
     * Updates UI + speaks the message.
     * All state writes go through runOnUiThread so Compose always picks them up.
     */
    private fun handleIncoming(msg: WireMessage) {
        Log.i(AppConstants.TAG, "handleIncoming: '${msg.payload}' from ${msg.from} lang ${msg.lang}")
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
                                "You: $myName",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showNameDialog = true }) {
                            Text("Edit name", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // ---- Status ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isListening)
                            Color(0xFFFFF3E0)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (isListening) Color(0xFFFF9800) else Color(0xFF4CAF50),
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(10.dp))
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
                                    "Hearing: $liveText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF0D47A1)
                                )
                            }
                        }
                    }
                }

                // ---- Bluetooth ----
                SectionHeader("Bluetooth")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (btConnected) Color(0xFF00C853) else Color(0xFF9E9E9E),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                btStatusVal,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    Log.i(AppConstants.TAG, "=== Start Listening tapped ===")
                                    btServer.start(ctx, lifecycleScope)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Start Listening") }

                            OutlinedButton(
                                onClick = {
                                    pairedDevices = btTransport.listPairedDevices(ctx)
                                    Log.i(AppConstants.TAG, "Paired: ${pairedDevices.size}")
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Refresh Paired") }
                        }

                        if (pairedDevices.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("Tap a device to connect:", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            pairedDevices.forEach { device ->
                                val name = try { device.name } catch (_: SecurityException) { "Unknown" }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            Log.i(AppConstants.TAG, "=== Tapped device: $name ===")
                                            lifecycleScope.launch {
                                                status = "Connecting BT to $name…"
                                                val r = btTransport.connect(ctx, device)
                                                status = if (r.isSuccess) "BT connected to $name"
                                                else "BT failed: ${r.exceptionOrNull()?.message}"
                                            }
                                        },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔵", modifier = Modifier.padding(end = 8.dp))
                                        Column {
                                            Text(name, fontWeight = FontWeight.SemiBold)
                                            Text(device.address, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        if (btConnected) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    btTransport.disconnect()
                                    status = "BT disconnected"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Disconnect") }
                        }
                    }
                }

                // ---- Sending-to indicator ----
                if (selectedContact != null) {
                    SectionHeader("Sending to")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
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
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = { selectedContact = null }) {
                                Text("Change")
                            }
                        }
                    }
                } else if (btConnected) {
                    SectionHeader("Sending to")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🔵", color = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    btPeerVal ?: "Bluetooth peer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "via Bluetooth",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "⚠  Select a contact below OR connect Bluetooth to start",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE65100)
                        )
                    }
                }

                // ---- Nearby ----
                SectionHeader("Nearby devices (Wi-Fi)")
                val others = nearby.filter { it.deviceId != myDeviceId }
                if (others.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Searching… (requires Wi-Fi ON)",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

                // ---- Contacts ----
                SectionHeader("Saved contacts")
                if (contacts.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No saved contacts yet.",
                            modifier = Modifier.padding(14.dp),
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

                // ---- Language ----
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

                // ---- Mic button ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isListening)
                            Color(0xFFFFEBEE)
                        else
                            MaterialTheme.colorScheme.primaryContainer
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
                                Log.i(AppConstants.TAG, "=== Mic tapped ===")
                                Log.i(AppConstants.TAG, "selectedContact=$selectedContact, btConnected=$btConnected, isListening=$isListening")

                                if (isListening) {
                                    ServiceLocator.voskSttManager.stop()
                                    isListening = false
                                    liveText = ""
                                    status = "Stopped"
                                    return@FilledIconButton
                                }

                                if (selectedContact == null && !btConnected) {
                                    status = "⚠ Select a contact OR connect Bluetooth first"
                                    return@FilledIconButton
                                }

                                isListening = true
                                liveText = ""
                                status = "Loading ${lang.display} model…"

                                ServiceLocator.voskSttManager.start(
                                    context = ctx,
                                    lang = lang,
                                    onResult = { text ->
                                        Log.i(AppConstants.TAG, "Vosk result: $text")
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
                                                        payload = text
                                                    )
                                                    val target = selectedContact
                                                    if (btTransport.isConnected()) {
                                                        val ok = btTransport.send(msg)
                                                        lastSentTo = btPeerVal ?: "peer"
                                                        status = if (ok) "Sent via Bluetooth"
                                                        else "Send failed"
                                                    } else if (target != null) {
                                                        val ip = resolveIp(target.deviceId, nearby, target)
                                                        if (ip != null) {
                                                            transport.connect(ip, AppConstants.DEFAULT_PORT)
                                                            transport.send(msg)
                                                            transport.close()
                                                            lastSentTo = target.name
                                                            status = "Sent to ${target.name}"
                                                        } else {
                                                            status = "Contact offline"
                                                        }
                                                    }
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
                                        Log.e(AppConstants.TAG, "Vosk error: $err")
                                        isListening = false
                                        liveText = ""
                                        status = "STT: $err"
                                    }
                                )
                            },
                            modifier = Modifier.size(110.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isListening)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                if (isListening) "⏹" else "🎤",
                                style = MaterialTheme.typography.displayMedium
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when {
                                isListening -> "Listening… tap to stop"
                                selectedContact == null && !btConnected -> "Select contact or connect BT"
                                else -> "Tap to speak"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (btTransport.isConnected()) "Mode: Bluetooth"
                            else "Mode: Wi-Fi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ---- Last sent ----
                if (lastSent.isNotBlank()) {
                    SectionHeader("Last sent")
                    MessageBubble(
                        header = "You → ${lastSentTo.ifBlank { selectedContact?.name ?: btPeerVal ?: "peer" }}",
                        subtitle = "${lang.display}  •  " + if (btTransport.isConnected()) "BT" else "Wi-Fi",
                        text = lastSent,
                        outgoing = true
                    )
                }

                // ---- Last received ----
                if (recvText.isNotBlank()) {
                    SectionHeader("Last received")
                    MessageBubble(
                        header = "From: ${friendlyName(recvFrom, contacts)}",
                        subtitle = "$recvLang  •  " + if (btTransport.isConnected()) "BT" else "Wi-Fi",
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
                            Text("➕ Save ${friendlyName(recvFrom, contacts)} as contact")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ---- Dialogs ----
            if (showNameDialog) {
                AlertDialog(
                    onDismissRequest = { showNameDialog = false },
                    title = { Text("Your display name") },
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
                    title = { Text("Save ${saving.displayName}") },
                    text = {
                        Column {
                            Text("Device: ${saving.deviceId}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))
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
                    title = { Text("Save sender") },
                    text = {
                        Column {
                            Text("Device: $senderId", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(8.dp))
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
    private fun SectionHeader(t: String) {
        Text(
            t.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }

    @Composable
    private fun NearbyDeviceRow(device: DiscoveredDevice, onSave: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFE3F2FD), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📡")
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(device.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        device.deviceId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
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
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        contact.name.take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(contact.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        contact.deviceId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (outgoing) Color(0xFFDCE9FF) else Color(0xFFE8F5E9)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        header,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (outgoing) Color(0xFF0D47A1) else Color(0xFF1B5E20)
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
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