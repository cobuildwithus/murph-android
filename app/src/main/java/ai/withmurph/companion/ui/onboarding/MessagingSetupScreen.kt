package ai.withmurph.companion.ui.onboarding

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphLogo
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown while the member has no phone or Telegram conversation with Murph.
 * Shared account settings prove and commit the link; returning here only
 * rereads canonical admission.
 */
@Composable
fun MessagingSetupScreen(
    state: AppUiState,
    onOpenAccountSettings: () -> Unit,
    onConfirmConnected: () -> Unit,
    onSignOut: () -> Unit,
) {
    val busy = state.isMessagingSetupRefreshing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            MurphLogo()
            Text(
                text = "Choose how to message Murph",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 39.sp),
                color = MurphColors.Slate,
            )
            Text(
                text = "Add your phone number or connect Telegram in secure account settings, " +
                    "then return here. Opening settings keeps this app signed in.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = MurphColors.SlateMuted,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MurphPrimaryButton("Open account settings", onOpenAccountSettings, enabled = !busy)
                val message = if (busy) "Confirming your account…" else state.messagingSetupMessage
                if (message != null) {
                    Text(
                        text = message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MurphColors.SlateMuted,
                    )
                }
                MurphLinkButton("I've connected an account", onConfirmConnected, enabled = !busy)
                MurphLinkButton(
                    "Sign out",
                    onSignOut,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = !busy,
                )
            }
        }
    }
}
