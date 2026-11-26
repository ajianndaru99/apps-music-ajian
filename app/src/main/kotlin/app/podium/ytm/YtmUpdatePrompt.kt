package app.podium.ytm

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.update.UpdateCenter
import app.podium.update.UpdateProblem
import app.podium.update.UpdateState

@Composable
internal fun YtmUpdateDialog(updates: UpdateCenter) {
    val colors = YtmTheme.colors
    val state by updates.state.collectAsStateWithLifecycle()
    val shown by updates.shown.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (!shown || state == UpdateState.Idle) return

    when (val s = state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Checking for updates", style = YtmTheme.type.titleLarge) },
                text = {
                    Column {
                        Text("Checking GitHub releases for a newer version...")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.onSurface,
                            trackColor = colors.divider,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updates.dismiss() }) {
                        Text("Hide", color = colors.onSurface)
                    }
                },
            )
        }

        is UpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Up to date", style = YtmTheme.type.titleLarge) },
                text = { Text("You already have the latest version (${s.current}).") },
                confirmButton = {
                    Button(
                        onClick = { updates.dismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("OK", color = colors.background, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        }

        is UpdateState.Offer -> {
            val sizeLabel = s.release.apk?.size?.takeIf { it > 0 }?.let { bytes ->
                " (%.1f MB)".format(bytes / (1024.0 * 1024.0))
            }.orEmpty()
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Update available: v${s.release.version}", style = YtmTheme.type.titleLarge) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.highlights.isEmpty()) {
                            Text("A newer version is ready to install.")
                        } else {
                            s.highlights.forEach { line ->
                                Text("• $line", style = YtmTheme.type.bodyMedium)
                            }
                        }
                        if (!s.canInstall) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Debug builds cannot auto-install signed releases. Open GitHub Releases to download.",
                                style = YtmTheme.type.labelMedium,
                                color = colors.onSurfaceMuted,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (s.canInstall) {
                        Button(
                            onClick = { updates.install() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.background,
                            ),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Update$sizeLabel", color = colors.background, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.release.pageUrl))
                                runCatching { context.startActivity(intent) }
                                updates.dismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.background,
                            ),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Download", color = colors.background, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { updates.ignore() }) {
                            Text("Skip", color = colors.onSurfaceMuted)
                        }
                        TextButton(onClick = { updates.dismiss() }) {
                            Text("Later", color = colors.onSurfaceVariant)
                        }
                    }
                },
            )
        }

        is UpdateState.Downloading -> {
            val fraction = s.total?.takeIf { it > 0 }?.let { (s.done.toFloat() / it).coerceIn(0f, 1f) }
            val progressText = if (s.total != null && s.total > 0) {
                "%.1f MB / %.1f MB".format(s.done / (1024.0 * 1024.0), s.total / (1024.0 * 1024.0))
            } else {
                "%.1f MB".format(s.done / (1024.0 * 1024.0))
            }
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Downloading update", style = YtmTheme.type.titleLarge) },
                text = {
                    Column {
                        Text("Downloading v${s.release.version}... ($progressText)")
                        Spacer(Modifier.height(12.dp))
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.onSurface,
                                trackColor = colors.divider,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.onSurface,
                                trackColor = colors.divider,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updates.dismiss() }) {
                        Text("Background", color = colors.onSurface)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updates.cancel() }) {
                        Text("Cancel", color = colors.onSurfaceVariant)
                    }
                },
            )
        }

        is UpdateState.Verifying -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Verifying update", style = YtmTheme.type.titleLarge) },
                text = {
                    Column {
                        Text("Checking integrity and signature...")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.onSurface,
                            trackColor = colors.divider,
                        )
                    }
                },
                confirmButton = {},
            )
        }

        is UpdateState.NeedsPermission -> {
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Permission needed", style = YtmTheme.type.titleLarge) },
                text = {
                    Text("Allow installing updates from Ajian Music in Android settings, then return here to complete the update.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(updates.permissionPage().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("Open settings", color = colors.background, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updates.dismiss() }) {
                        Text("Cancel", color = colors.onSurfaceVariant)
                    }
                },
            )
        }

        is UpdateState.Installing -> {
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Installing v${s.release.version}", style = YtmTheme.type.titleLarge) },
                text = { Text("Confirm the Android prompt to finish updating.") },
                confirmButton = {
                    TextButton(onClick = { updates.dismiss() }) {
                        Text("Close", color = colors.onSurface)
                    }
                },
            )
        }

        is UpdateState.Failed -> {
            val message = when (s.problem) {
                UpdateProblem.NO_CONNECTION -> "No internet connection. Check your network and try again."
                UpdateProblem.RATE_LIMITED -> "GitHub asked to slow down requests. Try again in a few minutes."
                UpdateProblem.NOTHING_TO_DOWNLOAD -> "The latest release has no APK file attached."
                UpdateProblem.DOWNLOAD -> "Download interrupted. Please try again."
                UpdateProblem.CORRUPT -> "Downloaded APK verification failed."
                UpdateProblem.OTHER_APP -> "The update uses a different signing key. Please download and install manually once."
                UpdateProblem.NOT_NEWER -> "The release on GitHub is not newer than this installed build."
                UpdateProblem.INSTALL -> s.detail ?: "Android installer failed to install this update."
            }
            AlertDialog(
                onDismissRequest = { updates.dismiss() },
                containerColor = colors.surfaceElevated,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurfaceVariant,
                title = { Text("Update failed", style = YtmTheme.type.titleLarge) },
                text = { Text(message) },
                confirmButton = {
                    when (s.problem) {
                        UpdateProblem.NO_CONNECTION, UpdateProblem.RATE_LIMITED -> {
                            Button(
                                onClick = { updates.checkNow() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.background,
                                ),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Retry", color = colors.background, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        UpdateProblem.DOWNLOAD, UpdateProblem.CORRUPT, UpdateProblem.INSTALL -> {
                            Button(
                                onClick = { updates.install() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.background,
                                ),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Retry", color = colors.background, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        else -> {
                            OutlinedButton(
                                onClick = {
                                    val url = s.release?.pageUrl ?: UpdateCenter.RELEASES_PAGE
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    updates.dismiss()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Open GitHub")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updates.dismiss() }) {
                        Text("Close", color = colors.onSurfaceVariant)
                    }
                },
            )
        }
    }
}
