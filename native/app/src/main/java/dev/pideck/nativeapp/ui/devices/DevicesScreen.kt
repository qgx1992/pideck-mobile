package dev.pideck.nativeapp.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.ui.theme.PillButton
import dev.pideck.nativeapp.ui.theme.PillStyle
import dev.pideck.nativeapp.ui.theme.RoundIconButton
import dev.pideck.nativeapp.ui.pairing.ScanScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onOpenDevice: (Device) -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf("") }

    if (showScan) {
        ScanScreen(
            onBack = { showScan = false },
            onScanned = { text ->
                // 扫到的原始串与手动粘贴走同一条解析路径（PairingLink 在 ViewModel 里）
                showScan = false
                scanned = text
                showAdd = true
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备") },
                actions = {
                    RoundIconButton(
                        icon = Icons.Default.QrCodeScanner,
                        contentDescription = "扫码配对",
                        onClick = { showScan = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        icon = Icons.Default.Add,
                        contentDescription = "添加设备",
                        onClick = { showAdd = true },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                },
            )
        },
    ) { padding ->
        if (devices.isEmpty()) {
            EmptyDevices(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onAdd = { showAdd = true },
                onScan = { showScan = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = { onOpenDevice(device) },
                        onRemove = { viewModel.remove(device.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddDeviceDialog(
            busy = viewModel.busy,
            error = viewModel.error,
            initialLink = scanned,
            onDismiss = {
                showAdd = false
                scanned = ""
                viewModel.clearError()
            },
            onConfirm = { link, token ->
                viewModel.add(link, token) { device ->
                    showAdd = false
                    scanned = ""
                    onOpenDevice(device)
                }
            },
        )
    }
}

@Composable
private fun EmptyDevices(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有配对设备", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "在桌面端 PiDeck 的「设置 → 连接面板」里扫二维码，或者复制地址（带令牌）粘进来。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PillButton(text = "扫码配对", onClick = onScan, icon = Icons.Default.QrCodeScanner, fillWidth = true)
        Spacer(Modifier.height(10.dp))
        PillButton(text = "手动输入地址", onClick = onAdd, style = PillStyle.Tonal, fillWidth = true)
    }
}

@Composable
private fun DeviceRow(device: Device, onClick: () -> Unit, onRemove: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    device.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RoundIconButton(
                icon = Icons.Default.DeleteOutline,
                contentDescription = "删除设备",
                onClick = onRemove,
                size = 34,
                container = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun AddDeviceDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (link: String, token: String) -> Unit,
    initialLink: String = "",
) {
    var link by remember(initialLink) { mutableStateOf(initialLink) }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("地址") },
                    placeholder = { Text("http://10.10.0.172:8765/?token=…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("令牌（链接里带了就不用填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在连接…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && link.isNotBlank(),
                onClick = { onConfirm(link, token) },
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}
