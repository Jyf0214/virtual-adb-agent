package com.virtual.adb.agent.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virtual.adb.agent.AppLogger
import com.virtual.adb.agent.DebugScreenshotManager
import com.virtual.adb.agent.R
import com.virtual.adb.agent.ResolutionMode
import com.virtual.adb.agent.RotationMode
import com.virtual.adb.agent.ServerConfig

/**
 * 主界面 Compose UI
 *
 * Material 3 动态配色设计，包含：
 * - 权限状态卡片（无障碍 + 屏幕捕捉）
 * - 服务控制开关
 * - 服务器配置卡片
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
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = stringResource(R.string.app_subtitle),
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

            // ─── 首次启动引导卡片 ───
            if (!ServerConfig.isFirstLaunchDone && (!a11yEnabled || !screenCaptureEnabled)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.guide_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.guide_step1),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.guide_step2),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.guide_step3),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.guide_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                        OutlinedButton(
                            onClick = { viewModel.dismissFirstLaunchGuide() }
                        ) {
                            Text(stringResource(R.string.guide_dismiss))
                        }
                    }
                }
            }

            // ─── 无障碍服务状态卡片 ───
            PermissionCard(
                title = stringResource(R.string.perm_a11y_title),
                description = if (a11yEnabled)
                    stringResource(R.string.perm_a11y_desc_enabled)
                else
                    stringResource(R.string.perm_a11y_desc_disabled),
                icon = Icons.Default.Accessibility,
                enabled = a11yEnabled,
                onAction = onOpenA11ySettings,
                actionText = if (a11yEnabled)
                    stringResource(R.string.perm_a11y_action_ready)
                else
                    stringResource(R.string.perm_a11y_action_setup)
            )

            // ─── 屏幕捕捉状态卡片 ───
            PermissionCard(
                title = stringResource(R.string.perm_sc_title),
                description = if (screenCaptureEnabled)
                    stringResource(R.string.perm_sc_desc_enabled)
                else
                    stringResource(R.string.perm_sc_desc_disabled),
                icon = Icons.Default.ScreenRotation,
                enabled = screenCaptureEnabled,
                onAction = {
                    if (captureRunning) onStopScreenCapture()
                    else onRequestScreenCapture()
                },
                actionText = if (captureRunning)
                    stringResource(R.string.perm_sc_action_stop)
                else
                    stringResource(R.string.perm_sc_action_start)
            )

            // ─── 服务器配置 ───
            ServerConfigCard(
                viewModel = viewModel,
                isDeveloperMode = isDeveloperMode
            )

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
                                text = stringResource(R.string.tcp_service_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (tcpRunning)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (tcpRunning) {
                                    val host = if (lanMode) "0.0.0.0" else "127.0.0.1"
                                    stringResource(R.string.tcp_listening_lan, host, tcpPort)
                                } else stringResource(R.string.tcp_hint_start),
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
                                    text = stringResource(R.string.tcp_lan_mode),
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

                    // ─── TCP 日志 ───
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tcp_log_title, tcpLogs.size),
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
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.tcp_log_copied, tcpLogs.size),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.tcp_btn_copy),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.tcp_btn_copy), style = MaterialTheme.typography.labelSmall)
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.clearTcpLogs() },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(stringResource(R.string.tcp_btn_clear), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (tcpLogs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tcp_log_empty),
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

            // ─── 系统日志（仅开发者模式） ───
            if (isDeveloperMode) {
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
                                text = stringResource(R.string.syslog_title, systemLogs.size),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (systemLogs.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            val text = systemLogs.joinToString("\n") { entry ->
                                                val ts = formatTimestamp(entry.timestamp)
                                                "[${ts}][${entry.level}/${entry.tag}] ${entry.message}"
                                            }
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("system_logs", text)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.syslog_copied, systemLogs.size),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = stringResource(R.string.tcp_btn_copy),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.tcp_btn_copy), style = MaterialTheme.typography.labelSmall)
                                    }
                                    FilledTonalButton(
                                        onClick = { AppLogger.clear() },
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(stringResource(R.string.tcp_btn_clear), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (systemLogs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.syslog_empty),
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
                                                text = "${formatTimestamp(entry.timestamp)} ${entry.tag}: ${entry.message}",
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
            }

            // ─── 调试截图列表（仅开发者模式） ───
            if (isDeveloperMode) {
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
                                text = stringResource(R.string.debug_screenshot_title, screenshots.size),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (screenshots.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = { DebugScreenshotManager.clearAll() },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(stringResource(R.string.debug_screenshot_clear), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (screenshots.isEmpty()) {
                            Text(
                                text = stringResource(R.string.debug_screenshot_empty),
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
                                                    Toast.makeText(
                                                        context,
                                                        R.string.debug_screenshot_open_failed,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.debug_screenshot_view), style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        FilledTonalButton(
                                            onClick = {
                                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "image/png"
                                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(
                                                    android.content.Intent.createChooser(
                                                        shareIntent,
                                                        context.getString(R.string.debug_screenshot_share_title)
                                                    )
                                                )
                                            },
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(stringResource(R.string.debug_screenshot_share), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
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
 * 包含：分辨率模式、截图旋转模式、自定义分辨率、截图性能、调试选项
 */
@Composable
private fun ServerConfigCard(
    viewModel: MainViewModel,
    isDeveloperMode: Boolean
) {
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
                text = stringResource(R.string.config_resolution_mode),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.config_resolution_desc),
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
                            ResolutionMode.REAL_SYSTEM -> stringResource(R.string.config_resolution_real)
                            ResolutionMode.FORCE_LANDSCAPE -> stringResource(R.string.config_resolution_landscape)
                            ResolutionMode.FORCE_PORTRAIT -> stringResource(R.string.config_resolution_portrait)
                            ResolutionMode.CUSTOM -> stringResource(R.string.config_resolution_custom)
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
                        label = { Text(stringResource(R.string.config_width)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customHeight.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { viewModel.setCustomResolution(customWidth, it) }
                        },
                        label = { Text(stringResource(R.string.config_height)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            HorizontalDivider()

            // ─── 截图旋转模式 ───
            Text(
                text = stringResource(R.string.config_rotation_mode),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.config_rotation_desc),
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
                            RotationMode.AUTO_SENSOR -> stringResource(R.string.config_rotation_auto)
                            RotationMode.NONE -> stringResource(R.string.config_rotation_none)
                            RotationMode.ROTATE_90 -> stringResource(R.string.config_rotation_90)
                            RotationMode.ROTATE_270 -> stringResource(R.string.config_rotation_270)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider()

            // ─── 截图性能配置 ───
            Text(
                text = stringResource(R.string.config_perf_title),
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
                        text = stringResource(R.string.config_smart_scale),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.config_smart_scale_desc),
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
                        text = stringResource(R.string.config_smart_scale_target),
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
                        text = stringResource(R.string.config_jpeg_quality, jpegQuality),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.config_jpeg_quality_desc),
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

            // ─── 调试选项（仅开发者模式可见） ───
            if (isDeveloperMode) {
                HorizontalDivider()

                Text(
                    text = stringResource(R.string.config_debug_title),
                    style = MaterialTheme.typography.titleSmall
                )

                // 开发者模式开关自身
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.config_dev_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.config_dev_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = true, // 已经在这个分支里了，所以必定是 true
                        onCheckedChange = { viewModel.toggleDeveloperMode() }
                    )
                }

                // 调试存图
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.config_debug_save),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.config_debug_save_desc),
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
                            text = stringResource(R.string.config_verbose_log),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.config_verbose_log_desc),
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
                            text = stringResource(R.string.config_chunk_log),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.config_chunk_log_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enableChunkLog,
                        onCheckedChange = { viewModel.toggleChunkLog() }
                    )
                }
            } else {
                // 非开发者模式：只显示一个进入开发者模式的入口
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.config_dev_mode),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.config_dev_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = false,
                        onCheckedChange = { viewModel.toggleDeveloperMode() }
                    )
                }
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
                text = "${formatTimestamp(entry.timestamp)} ${entry.client}",
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

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    return sdf.format(java.util.Date(ts))
}
