package ai.withmurph.companion.ui.onboarding

import ai.withmurph.companion.auth.MessagingSetupState
import ai.withmurph.companion.auth.MessagingStage
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.ui.login.CountryButton
import ai.withmurph.companion.ui.login.CountryPicker
import ai.withmurph.companion.ui.login.OtpInput
import ai.withmurph.companion.ui.components.MurphTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.autofill.ContentType
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
 * Native credential routes prove and commit the link; returning here
 * rereads canonical admission. Layout mirrors the iOS MessagingSetupView.
 */
@Composable
fun MessagingSetupScreen(
    state: AppUiState,
    link: MessagingSetupState = MessagingSetupState(),
    autofocusCode: Boolean = true,
    onPhone: (String) -> Unit = {},
    onCountry: (CountryDialCode) -> Unit = {},
    onCode: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onVerify: () -> Unit = {},
    onTelegram: () -> Unit = {},
    onSelectTelegram: () -> Unit = {},
    onChangeNumber: () -> Unit = {},
    onOpenAccountSettings: () -> Unit,
    onConfirmConnected: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Like iOS, a re-check blocks input without dimming the controls; the
    // centered capsule carries the progress state.
    val busy = state.isMessagingSetupRefreshing || link.busy
    var countryPicker by remember { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(link.stage) {
        if (link.stage == MessagingStage.Code && autofocusCode) codeFocus.requestFocus()
        else focusManager.clearFocus()
    }
    if (countryPicker) CountryPicker(link.country, { onCountry(it); countryPicker = false }, { countryPicker = false })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding().imePadding(),
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MurphLogo(Modifier.height(34.dp).width(152.dp))
                Spacer(Modifier.weight(1f))
                MurphLinkButton("Sign out", onSignOut)
            }
            if (link.stage != MessagingStage.Code) {
                Text(
                    "Choose how to message Murph",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 42.sp),
                    color = MurphColors.Slate,
                )
                Text(
                    if (link.stage == MessagingStage.Phone) "Message Murph from your phone."
                    else "We’ll open Telegram to connect.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = MurphColors.SlateMuted,
                )
            }
            if (!(link.stage == MessagingStage.Telegram && link.busy)) when (link.stage) {
                MessagingStage.Phone -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CountryButton(link.country, !busy) { countryPicker = true }
                            MurphTextField(
                                value = link.phone, onValueChange = onPhone, label = "Phone number", placeholder = "555 555 0100",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (!busy) onSend() }),
                                modifier = Modifier.weight(1f), enabled = !busy, autofillContentType = ContentType.PhoneNumberNational,
                            )
                        }
                        MessagingError(link, !busy, onOpenAccountSettings)
                    }
                    MurphPrimaryButton("Send code", onSend, enabled = !busy)
                    MurphLinkButton("Use Telegram instead", onSelectTelegram, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                MessagingStage.Code -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("We sent a code to ${link.displayPhone}.", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = MurphColors.SlateMuted)
                        MurphLinkButton("Resend", onSend, enabled = !busy)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OtpInput(link.code, onCode, codeFocus, onVerify, !busy)
                        MessagingError(link, !busy, onOpenAccountSettings)
                    }
                    MurphLinkButton("Use a different number", onChangeNumber, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                MessagingStage.Telegram -> {
                    if (link.telegramPending) Text("Finish connecting in Telegram.",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp), color = MurphColors.SlateMuted)
                    MessagingError(link, !busy, onOpenAccountSettings)
                    MurphPrimaryButton(if (link.telegramPending) "Open Telegram again" else "Connect Telegram", onTelegram, enabled = !busy)
                    MurphLinkButton("Use phone number instead", onChangeNumber, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                MessagingStage.Connected -> {
                    Text("Account connected. Let’s continue setting up Murph.", color = MurphColors.SlateMuted)
                    state.messagingSetupMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MurphColors.SlateMuted)
                        MurphPrimaryButton("Try again", onConfirmConnected, enabled = !busy)
                    }
                }
            }
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

@Composable
private fun MessagingError(link: MessagingSetupState, enabled: Boolean, onSettings: () -> Unit) {
    link.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MurphColors.SlateMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (link.offersAccountSettings) MurphLinkButton("Manage in account settings", onSettings, enabled = enabled)
    }
}
