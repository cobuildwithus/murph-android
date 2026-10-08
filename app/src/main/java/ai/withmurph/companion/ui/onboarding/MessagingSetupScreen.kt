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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * rereads canonical admission. Layout mirrors the iOS MessagingSetupView.
 */
@Composable
fun MessagingSetupScreen(
    state: AppUiState,
    onOpenAccountSettings: () -> Unit,
    onConfirmConnected: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Like iOS, a re-check blocks input without dimming the controls; the
    // centered capsule carries the progress state.
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
            MurphLogo(Modifier.height(34.dp).width(152.dp))
            Text(
                text = "Choose how to message Murph",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 42.sp),
                color = MurphColors.Slate,
            )
            Text(
                text = "Add your phone number or connect Telegram in secure account settings, " +
                    "then return here. Opening settings keeps this app signed in.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                color = MurphColors.SlateMuted,
            )
            MurphPrimaryButton("Open account settings", { if (!busy) onOpenAccountSettings() })
            state.messagingSetupMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MurphColors.SlateMuted,
                )
            }
            MurphLinkButton("I've connected an account", { if (!busy) onConfirmConnected() })
            MurphLinkButton(
                "Sign out",
                { if (!busy) onSignOut() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (busy) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(50),
                color = MurphColors.NavigationSurface,
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MurphColors.SageDark,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Confirming your account…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MurphColors.Slate,
                    )
                }
            }
        }
    }
}
