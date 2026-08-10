package com.example.antiwispr.ui.account

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.antiwispr.ui.AppViewModel
import com.example.antiwispr.ui.SetupStatus
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.SectionHeader
import com.example.antiwispr.ui.components.SettingsGroup
import com.example.antiwispr.ui.components.TacitIcons
import com.example.antiwispr.ui.components.relativeTime
import com.example.antiwispr.ui.theme.Dimens

/**
 * The Account screen (doc 05): profile, backup and sync, sign out. Settings keeps only a
 * doorway row into here. Plan card, usage meters, devices, export, and deletion arrive with
 * the entitlement system; until then this screen shows only what is real.
 */
@Composable
fun AccountScreen(
    setup: SetupStatus,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    // Signing out (or arriving here signed out) leaves nothing to show — return to Settings.
    LaunchedEffect(setup.signedIn) { if (!setup.signedIn) onBack() }

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Account",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(Modifier.padding(horizontal = Dimens.screenPad)) {
                Spacer(Modifier.height(16.dp))
                ProfileBlock(setup)

                Spacer(Modifier.height(Dimens.sectionGap - 12.dp))
                SectionHeader("Backup and sync")
                Spacer(Modifier.height(8.dp))
                SettingsGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            TacitIcons.Cloud, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    setup.syncing -> "Syncing…"
                                    setup.lastSyncMs > 0 -> "Synced ${relativeTime(setup.lastSyncMs).lowercase()}"
                                    else -> "Not synced yet"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Transcripts and briefs back up to this account. Audio never leaves this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GhostButton("Sync now", onClick = { vm.syncNow() }, enabled = !setup.syncing)
                    }
                }

                Spacer(Modifier.height(Dimens.sectionGap))
                GhostButton(
                    "Sign out",
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Your notes stay on this phone. Backup and cloud features pause until you sign in again." +
                        if (!setup.modelReady) " Without the offline pack, new notes can't be read until you sign in or download it." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; vm.signOut() }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

@Composable
private fun ProfileBlock(setup: SetupStatus) {
    val cs = MaterialTheme.colorScheme
    val email = setup.accountEmail.orEmpty()
    val name = setup.accountName ?: email.substringBefore('@').ifBlank { "Signed in" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (name.trim().firstOrNull() ?: 'T').uppercaseChar().toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            if (email.isNotBlank()) {
                Text(email, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
