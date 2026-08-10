package com.example.antiwispr.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.antiwispr.cloud.CloudAuth
import com.example.antiwispr.ui.components.GhostButton
import com.example.antiwispr.ui.components.TacitButton
import com.example.antiwispr.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * Email + password sign-in, the alternative to the Google one-tap. Two modes (sign in / create
 * an account) in one sheet; a reset link for forgotten passwords. Errors arrive pre-translated
 * to product language ([CloudAuth.friendlyEmailError]); Firebase is never named.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSignInSheet(onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var create by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPad)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                if (create) "Create your account." else "Sign in with email.",
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Your library backs up to this account.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null; notice = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email", style = MaterialTheme.typography.labelMedium) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password", style = MaterialTheme.typography.labelMedium) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                enabled = !busy,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary)
            }
            Spacer(Modifier.height(16.dp))
            TacitButton(
                if (create) "Create account" else "Sign in",
                onClick = {
                    busy = true; error = null; notice = null
                    scope.launch {
                        val result =
                            if (create) CloudAuth.createAccountWithEmail(context, email, password)
                            else CloudAuth.signInWithEmail(context, email, password)
                        result
                            .onSuccess { onSuccess() }
                            .onFailure { error = CloudAuth.friendlyEmailError(it) }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank(),
                loading = busy,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(
                    if (create) "I already have an account" else "Create an account instead",
                    onClick = { create = !create; error = null; notice = null },
                    enabled = !busy,
                )
                Spacer(Modifier.weight(1f))
                if (!create) {
                    GhostButton(
                        "Forgot password?",
                        onClick = {
                            if (email.isBlank()) {
                                error = "Enter your email above first."
                            } else {
                                error = null
                                scope.launch {
                                    CloudAuth.sendPasswordReset(context, email)
                                        .onSuccess { notice = "Password reset email sent to ${email.trim()}." }
                                        .onFailure { error = CloudAuth.friendlyEmailError(it) }
                                }
                            }
                        },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}
