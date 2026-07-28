package com.virtual.adb.agent.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virtual.adb.agent.AppLogger
import com.virtual.adb.agent.DebugScreenshotManager
import com.virtual.adb.agent.ResolutionMode
import com.virtual.adb.agent.RotationMode

/**
 * 主界面 Compose UI
 *
 * Material 3 动态配色设计，包含：
 * - 权限状态卡片（无障碍 + 屏幕捕捉）
 * - 服务控制开关
 * - 命令行工具箱
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenA11ySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    val a11yEnabled by viewModel.a11yEnabled.collectAsState()
    val screenCaptureEnabled by viewModel.screenCaptureEnabled.collectAsState()
    val tcpRunning by viewModel.tcpRunning.collectAsState()
    val tcpPort by viewModel.tcpPort.collectAsState()
    val lanMode by viewModel.lanMode.collectAsState()
    val captureRunning by viewModel.captureRunning.collectAsState()
    val logMessage by viewModel.logMessage.collectAsState()
    val tcpLogs by viewModel.tcpLogs.collectAsState()
    val tcpStartError by viewModel.tcpStartError.collectAsState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Virtual ADB Agent")
                        Text(
                            text = "本地 TCP 代理服务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ─── 无障碍服务状态卡片 ───
            PermissionCard(
                title = "无障碍服务",
                description = if (a11yEnabled) "已启用，可执行手势操作" else "未启用，需要授权后才能执行点击/滑动",
                icon = Icons.Default.Accessibility,
                enabled = a11yEnabled,
                onAction = onOpenA11ySettings,
                actionText = if (a11yEnabled) "已就绪" else "前往设置"
            )

            // ─── 屏幕捕捉状态卡片 ───
            PermissionCard(
                title = "屏幕捕捉",
                description = if (screenCaptureEnabled) "已授权，正在截取屏幕" else "未授权，需要授权后才能截图",
                icon = Icons.Default.ScreenRotation,
                enabled = screenCaptureEnabled,
                onAction = {
                    if (captureRunning) {
                        onStopScreenCapture()
                    } else {
                        onRequestScreenCapture()
                    }
                },
                actionText = if (captureRunning) "停止捕捉" else "授权并启动"
            )

            // ─── 服务器配置 ───
            ServerConfigCard(viewModel = viewModel)

            // ─── TCP 服务控制 ───
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (tcpRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TCP 服务",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (tcpRunning)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (tcpRunning) {
                                    if (lanMode) "监听 0.0.0.0:$tcpPort（局域网）"
                                    else "监听 127.0.0.1:$tcpPort"
                                } else "点击开关启动服务",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (tcpRunning)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (tcpStartError.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = tcpStartError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "局域网模式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = lanMode,
                                    onCheckedChange = { viewModel.toggleLanMode() },
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }

                        Switch(
                            checked = tcpRunning,
                            onCheckedChange = { viewModel.toggleTcpServer() },
                            thumbContent = {
                                Icon(
                                    imageVector = if (tcpRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    // ─── TCP 日志（始终显示在开关下方）───
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "日志 (${tcpLogs.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (tcpLogs.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        val text = tcpLogs.joinToString("\n") { "${it.direction} [${it.client}] ${it.content}" }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("tcp_logs", text)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "已复制 ${tcpLogs.size} 条日志", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("复制", style = MaterialTheme.typography.labelSmall)
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.clearTcpLogs() },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("清空", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (tcpLogs.isEmpty()) {
                        Text(
                            text = "等待连接...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            tcpLogs.forEach { entry ->
                                LogEntryItem(entry)
                            }
                        }
                    }
                }
            }

            // ─── 系统日志 ───
            val systemLogs by AppLogger.logs.collectAsState()
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "系统日志 (${systemLogs.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (systemLogs.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        val text = systemLogs.joinToString("\n") { "[${it.level}/${it.tag}] ${it.message}" }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("system_logs", text)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "已复制 ${systemLogs.size} 条日志", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("复制", style = MaterialTheme.typography.labelSmall)
                                }
                                FilledTonalButton(
                                    onClick = { AppLogger.clear() },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("清空", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (systemLogs.isEmpty()) {
                        Text(
                            text = "无系统日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            systemLogs.takeLast(100).forEach { entry ->
                                val levelColor = when (entry.level) {
                                    "E" -> MaterialTheme.colorScheme.error
                                    "W" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = entry.level,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = levelColor,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${entry.tag}: ${entry.message}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ─── 调试截图列表 ───
            val screenshots by DebugScreenshotManager.screenshots.collectAsState()
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "调试截图 (${screenshots.size}/10)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (screenshots.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { DebugScreenshotManager.clearAll() },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("清空全部", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (screenshots.isEmpty()) {
                        Text(
                            text = "无调试截图（需开启「调试存图」开关）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            screenshots.reversed().forEach { info ->
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    java.io.File(info.filePath)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = info.fileName,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatFileSize(info.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "image/png")
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "打开图片失败", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("查看", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, "分享截图"))
                                        },
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("分享", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ─── 状态日志 ───
            if (logMessage.isNotEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = logMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 权限状态卡片
 */
@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onAction: () -> Unit,
    actionText: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!enabled) {
                FilledTonalButton(onClick = onAction) {
                    Text(text = actionText, maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = onAction) {
                    Text(text = actionText, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 服务器配置卡片
 *
 * 包含：分辨率模式、截图旋转模式、自定义分辨率输入
 */
@Composable
private fun ServerConfigCard(viewModel: MainViewModel) {
    val resolutionMode by viewModel.resolutionMode.collectAsState()
    val rotationMode by viewModel.rotationMode.collectAsState()
    val customWidth by viewModel.customWidth.collectAsState()
    val customHeight by viewModel.customHeight.collectAsState()
    val enableSmartScale by viewModel.enableSmartScale.collectAsState()
    val smartScaleTargetWidth by viewModel.smartScaleTargetWidth.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val enableDebugSave by viewModel.enableDebugSave.collectAsState()
    val enableVerboseLog by viewModel.enableVerboseLog.collectAsState()
    val enableChunkLog by viewModel.enableChunkLog.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── 分辨率模式 ───
            Text(
                text = "分辨率返回模式",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "控制 wm size 命令返回给 ADB 客户端的分辨率",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ResolutionMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = resolutionMode == mode,
                        onClick = { viewModel.setResolutionMode(mode) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            ResolutionMode.REAL_SYSTEM -> "跟随系统真实"
                            ResolutionMode.FORCE_LANDSCAPE -> "强制横屏 (宽>高)"
                            ResolutionMode.FORCE_PORTRAIT -> "强制竖屏 (宽<高)"
                            ResolutionMode.CUSTOM -> "自定义"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ─── 自定义分辨率输入 ───
            if (resolutionMode == ResolutionMode.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customWidth.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { viewModel.setCustomResolution(it, customHeight) }
                        },
                        label = { Text("宽度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customHeight.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { viewModel.setCustomResolution(customWidth, it) }
                        },
                        label = { Text("高度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            HorizontalDivider()

            // ─── 截图旋转模式 ───
            Text(
                text = "截图旋转矫正",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "控制截图返回给 ADB 客户端时的旋转角度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RotationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = rotationMode == mode,
                        onClick = { viewModel.setRotationMode(mode) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (mode) {
                            RotationMode.AUTO_SENSOR -> "智能感应 (Display.rotation)"
                            RotationMode.NONE -> "不旋转 (原图)"
                            RotationMode.ROTATE_90 -> "强制 90°"
                            RotationMode.ROTATE_270 -> "强制 270°"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()

            // ─── 截图性能配置 ───
            Text(
                text = "截图性能",
                style = MaterialTheme.typography.titleSmall
            )

            // 智能缩放开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "智能缩放",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "宽度超过目标值时等比缩放，减小图片体积",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableSmartScale,
                    onCheckedChange = { viewModel.toggleSmartScale() }
                )
            }

            // 缩放目标宽度
            if (enableSmartScale) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "目标宽度:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    listOf(720, 1280, 1920).forEach { width ->
                        OutlinedButton(
                            onClick = { viewModel.setSmartScaleTargetWidth(width) },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = "${width}p",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // JPEG 质量
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JPEG 质量: ${jpegQuality}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "100% = 无损，80% = 有损压缩",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(80, 90, 100).forEach { quality ->
                        OutlinedButton(
                            onClick = { viewModel.setJpegQuality(quality) },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = "${quality}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ─── 调试配置 ───
            Text(
                text = "调试选项",
                style = MaterialTheme.typography.titleSmall
            )

            // 调试存图
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "调试存图",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "每次截图保存一份到本地: files/adb_screencap_debug.png",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableDebugSave,
                    onCheckedChange = { viewModel.toggleDebugSave() }
                )
            }

            // 详细日志
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "详细日志",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "打印截图尺寸、耗时、文件大小等信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableVerboseLog,
                    onCheckedChange = { viewModel.toggleVerboseLog() }
                )
            }

            // 分块传输日志
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "分块传输日志",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "打印每个数据块的传输详情（调试用）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableChunkLog,
                    onCheckedChange = { viewModel.toggleChunkLog() }
                )
            }
        }
    }
}

/**
 * 单条日志条目
 */
@Composable
private fun LogEntryItem(entry: com.virtual.adb.agent.TcpBridgeServer.LogEntry) {
    val directionColor = when (entry.direction) {
        "→" -> MaterialTheme.colorScheme.primary
        "←" -> MaterialTheme.colorScheme.tertiary
        "✗" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.direction,
            style = MaterialTheme.typography.labelMedium,
            color = directionColor,
            modifier = Modifier.width(20.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.client,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}
