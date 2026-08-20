package com.mlmvpn.scanner.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.mlmvpn.scanner.MyVpnService
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.NodeManager
import com.mlmvpn.scanner.engines.hybrid.PsiphonV2rayEngine
import com.mlmvpn.scanner.ui.theme.*
import com.mlmvpn.scanner.utils.VpnConfig

@Composable
fun HybridTab(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isFa = com.mlmvpn.scanner.utils.AppLocaleManager.getResolvedLocale().language == "fa"
    val prefs = remember { context.getSharedPreferences("hybrid_psiphon_v2ray", android.content.Context.MODE_PRIVATE) }
    val defaultPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val nodeManager = remember { NodeManager(context) }
    val nodes by nodeManager.nodesFlow.collectAsState()

    val isRunning by MyVpnService.isRunningFlow.collectAsState()
    val connectedId by MyVpnService.connectedNodeIdFlow.collectAsState()
    val phase by MyVpnService.connectionPhaseFlow.collectAsState()
    val hopStatus by PsiphonV2rayEngine.hopStatusFlow.collectAsState()
    val hopMessage by PsiphonV2rayEngine.hopMessageFlow.collectAsState()
    val hybridActive = isRunning && connectedId == PsiphonV2rayEngine.NODE_ID

    var configText by remember { mutableStateOf(prefs.getString("last_uri", "") ?: "") }
    var hop by remember { mutableStateOf(prefs.getString("hop", PsiphonV2rayEngine.HOP_FRAGMENT) ?: PsiphonV2rayEngine.HOP_FRAGMENT) }
    var profile by remember { mutableStateOf(prefs.getString("profile", PsiphonV2rayEngine.PROFILE_BALANCED) ?: PsiphonV2rayEngine.PROFILE_BALANCED) }
    var socksPort by remember { mutableStateOf(prefs.getString("socks_port", PsiphonV2rayEngine.DEFAULT_SOCKS_PORT.toString()) ?: "1081") }
    var selectedNodeId by remember { mutableStateOf<String?>(prefs.getString("node_id", null)) }
    var pickerOpen by remember { mutableStateOf(false) }

    val usableNodes = remember(nodes) {
        nodes.filter { node ->
            val u = node.uri.trim()
            u.startsWith("vless://") || u.startsWith("trojan://") || u.startsWith("vmess://")
        }
    }
    val selectedNode = usableNodes.find { it.id == selectedNodeId }

    var isConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(phase, isRunning) {
        if (phase != MyVpnService.Phase.CONNECTING) isConnecting = false
    }

    fun persist() {
        prefs.edit()
            .putString("last_uri", configText)
            .putString("hop", hop)
            .putString("profile", profile)
            .putString("socks_port", socksPort)
            .putString("node_id", selectedNodeId)
            .apply()
    }

    fun resolvedUri(): String {
        val typed = configText.trim()
        if (typed.isNotEmpty()) return typed
        return selectedNode?.uri?.trim().orEmpty()
    }

    fun startHybrid() {
        val uri = resolvedUri()
        val parsed = VpnConfig.parseUri(uri)
        if (parsed == null) {
            Toast.makeText(
                context,
                if (isFa) "یک کانفیگ VLESS / VMess / Trojan معتبر وارد کنید" else "Paste a valid VLESS / VMess / Trojan config",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        persist()
        val hopMode = hop
        val port = socksPort.toIntOrNull() ?: PsiphonV2rayEngine.DEFAULT_SOCKS_PORT
        val payload = PsiphonV2rayEngine.buildConfigJson(
            v2rayUri = uri,
            hop = hopMode,
            fragmentProfile = profile,
            socksPort = port
        )
        val localPort = defaultPrefs.getString("local_port", "10808")
        val proxyMode = defaultPrefs.getBoolean("proxy_mode", false)
        val startIntent = Intent(context, MyVpnService::class.java).apply {
            putExtra("NODE_URI", payload)
            putExtra("NODE_ID", PsiphonV2rayEngine.NODE_ID)
            putExtra("PROXY_MODE", proxyMode)
            putExtra("LOCAL_PORT", localPort)
            if (hopMode == PsiphonV2rayEngine.HOP_SOCKS) {
                putExtra("EXCLUDE_PACKAGES", PsiphonV2rayEngine.PSIPHON_PACKAGES)
            }
        }
        isConnecting = true
        context.startService(startIntent)
        MyVpnService.isRunning = true
        MyVpnService.connectedNodeId = PsiphonV2rayEngine.NODE_ID
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startHybrid()
        } else {
            isConnecting = false
            Toast.makeText(context, if (isFa) "مجوز VPN رد شد" else "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun onPowerClick() {
        if (hybridActive || (isRunning && connectedId == PsiphonV2rayEngine.NODE_ID)) {
            stopVpnSafely(context)
            return
        }
        if (isRunning) {
            Toast.makeText(
                context,
                if (isFa) "ابتدا اتصال فعلی را قطع کنید" else "Disconnect the current tunnel first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (isWireguardTrialActive()) {
            Toast.makeText(
                context,
                if (isFa) "وایرگارد فعال است؛ ابتدا آن را خاموش کنید" else "WireGuard is active — turn it off first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) vpnLauncher.launch(prepare) else startHybrid()
    }

    val statusColor = when {
        hybridActive && phase == MyVpnService.Phase.CONNECTED -> GreenOk
        isConnecting || phase == MyVpnService.Phase.CONNECTING -> YellowWarn
        hopStatus == PsiphonV2rayEngine.HopStatus.FAILED || phase == MyVpnService.Phase.FAILED -> RedError
        else -> TextMuted
    }
    val statusText = when {
        hybridActive && phase == MyVpnService.Phase.CONNECTED ->
            if (isFa) "متصل — دو لایه فعال است" else "Connected — both hops are up"
        isConnecting || phase == MyVpnService.Phase.CONNECTING || hopStatus == PsiphonV2rayEngine.HopStatus.HOPPING ->
            if (isFa) "در حال برقراری لایه سایفون…" else "Starting the Psiphon hop…"
        hopStatus == PsiphonV2rayEngine.HopStatus.CHAINING ->
            if (isFa) "در حال اتصال V2Ray از داخل تونل…" else "Chaining V2Ray through the hop…"
        hopStatus == PsiphonV2rayEngine.HopStatus.FAILED || phase == MyVpnService.Phase.FAILED ->
            if (isFa) "اتصال ناموفق${if (hopMessage.isNotBlank()) " — $hopMessage" else ""}"
            else "Failed${if (hopMessage.isNotBlank()) " — $hopMessage" else ""}"
        else -> if (isFa) "آماده اتصال" else "Ready"
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth().height(64.dp), shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.AltRoute, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.hybrid_title),
                        color = Primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.hybrid_desc), color = TextPrimary.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 20.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HopBadge(if (isFa) "لایه ۱ · سایفون" else "Hop 1 · Psiphon", Color(0xFF26A69A))
                            HopBadge(if (isFa) "لایه ۲ · V2Ray" else "Hop 2 · V2Ray", Primary)
                        }
                    }
                }

                Text(stringResource(R.string.hybrid_config_label), color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = {
                        Text(
                            if (isFa) "vless://…  یا  vmess://…  یا  trojan://…" else "vless://…  or  vmess://…  or  trojan://…",
                            color = TextDim,
                            fontSize = 13.sp
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderDark
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val text = clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isNotBlank()) configText = text.trim()
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Primary)
                        }
                    }
                )

                if (usableNodes.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { pickerOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            selectedNode?.name ?: stringResource(R.string.hybrid_pick_node),
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = pickerOpen,
                        onDismissRequest = { pickerOpen = false },
                        modifier = Modifier.background(Color(0xFF2D2E31)).heightIn(max = 280.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isFa) "— هیچ‌کدام (فقط متن بالا) —" else "— none (use the field above) —", color = TextMuted) },
                            onClick = { selectedNodeId = null; pickerOpen = false }
                        )
                        usableNodes.take(40).forEach { node ->
                            DropdownMenuItem(
                                text = { Text(node.name, color = TextPrimary) },
                                onClick = {
                                    selectedNodeId = node.id
                                    if (configText.isBlank()) configText = node.uri
                                    pickerOpen = false
                                }
                            )
                        }
                    }
                    }
                }

                Text(stringResource(R.string.hybrid_hop_label), color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                HopChoice(
                    selected = hop == PsiphonV2rayEngine.HOP_FRAGMENT,
                    title = stringResource(R.string.hybrid_hop_fragment),
                    subtitle = stringResource(R.string.hybrid_hop_fragment_desc),
                    icon = Icons.Default.Shield
                ) { hop = PsiphonV2rayEngine.HOP_FRAGMENT }
                HopChoice(
                    selected = hop == PsiphonV2rayEngine.HOP_SOCKS,
                    title = stringResource(R.string.hybrid_hop_socks),
                    subtitle = stringResource(R.string.hybrid_hop_socks_desc),
                    icon = Icons.Default.Tune
                ) { hop = PsiphonV2rayEngine.HOP_SOCKS }

                if (hop == PsiphonV2rayEngine.HOP_FRAGMENT) {
                    Text(stringResource(R.string.hybrid_profile_label), color = TextMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ProfileChip(stringResource(R.string.hybrid_profile_light), profile == PsiphonV2rayEngine.PROFILE_LIGHT, Modifier.weight(1f)) {
                            profile = PsiphonV2rayEngine.PROFILE_LIGHT
                        }
                        ProfileChip(stringResource(R.string.hybrid_profile_balanced), profile == PsiphonV2rayEngine.PROFILE_BALANCED, Modifier.weight(1f)) {
                            profile = PsiphonV2rayEngine.PROFILE_BALANCED
                        }
                        ProfileChip(stringResource(R.string.hybrid_profile_aggressive), profile == PsiphonV2rayEngine.PROFILE_AGGRESSIVE, Modifier.weight(1f)) {
                            profile = PsiphonV2rayEngine.PROFILE_AGGRESSIVE
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = socksPort,
                        onValueChange = { socksPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.hybrid_socks_port), color = TextMuted) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderDark
                        )
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.hybrid_status), color = TextMuted, fontSize = 11.sp)
                            Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (hybridActive && phase == MyVpnService.Phase.CONNECTED) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenOk)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                val connectingNow = isConnecting || phase == MyVpnService.Phase.CONNECTING
                Button(
                    onClick = { onPowerClick() },
                    enabled = !connectingNow || hybridActive,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hybridActive) RedError else Primary,
                        contentColor = if (hybridActive) Color.White else BgDark,
                        disabledContainerColor = SurfaceDark
                    )
                ) {
                    if (connectingNow && !hybridActive) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            hybridActive -> stringResource(R.string.hybrid_disconnect)
                            connectingNow -> stringResource(R.string.hybrid_connecting)
                            else -> stringResource(R.string.hybrid_connect)
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HopBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HopChoice(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Primary.copy(alpha = 0.12f) else SurfaceDark)
            .border(1.dp, if (selected) Primary.copy(alpha = 0.6f) else BorderDark, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = TextMuted)
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, tint = if (selected) Primary else TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ProfileChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary else SurfaceDark)
            .border(1.dp, if (selected) Primary else BorderDark, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) BgDark else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
