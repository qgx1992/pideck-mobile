package dev.pideck.nativeapp.ui.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pideck.nativeapp.data.AuthNotice
import dev.pideck.nativeapp.data.Device
import dev.pideck.nativeapp.ui.theme.AppButton
import dev.pideck.nativeapp.ui.theme.AppButtonStyle
import dev.pideck.nativeapp.ui.theme.AppIconButton
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
    // 长按设备行 → 改名
    var renaming by remember { mutableStateOf<Device?>(null) }

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
                    AppIconButton(
                        icon = Icons.Default.QrCodeScanner,
                        contentDescription = "扫码配对",
                        onClick = { showScan = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    AppIconButton(
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
                // 授权失效是「不处理就一直红字」的状态，摆最上面，别让人以为是网络问题
                AuthNotice.message?.let { message ->
                    item(key = "auth-notice") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "知道了",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier
                                        .padding(start = 10.dp)
                                        .clickable { AuthNotice.clear() },
                                )
                            }
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "长按设备可以改名",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        onClick = { onOpenDevice(device) },
                        onRename = { renaming = device },
                        onRemove = { viewModel.remove(device.id) },
                    )
                }
            }
        }
    }

    renaming?.let { device ->
        RenameDeviceDialog(
            device = device,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(device.id, name)
                renaming = null
            },
        )
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
        AppButton(text = "扫码配对", onClick = onScan, icon = Icons.Default.QrCodeScanner, fillWidth = true)
        Spacer(Modifier.height(10.dp))
        AppButton(text = "手动输入地址", onClick = onAdd, style = AppButtonStyle.Tonal, fillWidth = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: Device,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            // 长按改名：设备名默认是主机名/IP，多台电脑时根本分不清
            .combinedClickable(onClick = onClick, onLongClick = onRename),
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
            AppIconButton(
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

@Composable
private fun RenameDeviceDialog(
    device: Device,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 进来就全选：多数时候是整条替换，不是插字
    var value by remember(device.id) {
        mutableStateOf(TextFieldValue(device.name, selection = TextRange(0, device.name.length)))
    }
    val submit = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给设备起个名字") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    device.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = value.text.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
