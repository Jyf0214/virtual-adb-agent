package com.virtual.adb.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virtual.adb.agent.R

/**
 * 首次启动时的「用户协议及隐私政策」同意弹窗。
 *
 * 用户拒绝时关闭弹窗但不清除标记，下次启动仍会弹出。
 * 用户同意后永久记录，不再弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsConsentDialog(
    onAgree: () -> Unit,
    onReject: () -> Unit
) {
    val context = LocalContext.current
    val docsBaseUrl = "https://jyf0214.github.io/virtual-adb-agent/legal"

    Dialog(
        onDismissRequest = {}, // 禁止点击外部关闭
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // 标题
                Text(
                    text = stringResource(R.string.terms_consent_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 可滚动正文
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.terms_consent_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 用户协议链接
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(stringResource(R.string.terms_consent_terms_link))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$docsBaseUrl/terms"))
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 隐私政策链接
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(stringResource(R.string.terms_consent_privacy_link))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$docsBaseUrl/privacy"))
                            context.startActivity(intent)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.terms_consent_reject))
                    }

                    Button(
                        onClick = onAgree,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.terms_consent_agree))
                    }
                }
            }
        }
    }
}
