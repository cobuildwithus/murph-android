package ai.withmurph.companion.ui.login

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors

@Composable
fun LoginScreen(
    state: LoginUiState,
    onMethodChanged: (LoginMethod) -> Unit,
    onDestinationChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onSendCode: () -> Unit,
    onConfirmCode: () -> Unit,
    onResendCode: () -> Unit,
    onChangeDestination: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MurphColors.Cream).padding(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Murph",
                style = MaterialTheme.typography.displayLarge,
                color = MurphColors.Slate,
            )
            Text(
                text = if (state.codeSent) {
                    "We sent a code to ${state.normalizedDestination}."
                } else {
                    "Sign in with your existing Murph account to connect Health Connect."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )

            if (!state.codeSent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoginMethod.entries.forEach { method ->
                        TextButton(onClick = { onMethodChanged(method) }) {
                            Text(
                                text = method.name,
                                color = if (state.method == method) {
                                    MurphColors.SageDark
                                } else {
                                    MurphColors.SlateMuted
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.destination,
                    onValueChange = onDestinationChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(if (state.method == LoginMethod.Phone) "Phone number" else "Email")
                    },
                    placeholder = {
                        Text(if (state.method == LoginMethod.Phone) "+14155552671" else "you@example.com")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (state.method == LoginMethod.Phone) {
                            KeyboardType.Phone
                        } else {
                            KeyboardType.Email
                        },
                    ),
                    shape = MaterialTheme.shapes.large,
                )
                if (state.method == LoginMethod.Phone) {
                    Text(
                        "Use full international format, including + and country code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MurphColors.SlateMuted,
                    )
                }
                MurphPrimaryButton(
                    text = if (state.isInFlight) "Sending…" else "Send code",
                    onClick = onSendCode,
                    enabled = state.canSendCode,
                )
            } else {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCodeChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("6-digit code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = MaterialTheme.shapes.large,
                )
                MurphPrimaryButton(
                    text = if (state.isInFlight) "Signing in…" else "Sign in",
                    onClick = onConfirmCode,
                    enabled = state.canConfirmCode,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onChangeDestination) { Text("Change") }
                    TextButton(onClick = onResendCode) { Text("Resend") }
                }
            }

            if (state.errorMessage != null) {
                Text(
                    state.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MurphColors.Sienna,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TextButton(onClick = onOpenPrivacy) { Text("Privacy") }
            TextButton(onClick = onOpenTerms) { Text("Terms") }
        }
    }
}
