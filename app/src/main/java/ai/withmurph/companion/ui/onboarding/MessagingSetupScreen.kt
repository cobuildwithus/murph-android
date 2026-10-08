package ai.withmurph.companion.ui.onboarding

import ai.withmurph.companion.auth.MessagingSetupState
import ai.withmurph.companion.auth.MessagingStage
import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.ui.login.CountryButton
import ai.withmurph.companion.ui.login.CountryPicker
import ai.withmurph.companion.ui.login.OtpInput
import ai.withmurph.companion.ui.components.MurphTextField
import androidx.compose.foundation.layout.heightIn
import ai.withmurph.companion.ui.components.MurphOutlineButton
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
    onCancelTelegram: () -> Unit = {},
    onChangeNumber: () -> Unit = {},
    onOpenAccountSettings: () -> Unit,
    onConfirmConnected: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Like iOS, progress renders inline in the content flow, never over it,
    // so no control is covered at any text size.
    val refreshing = state.isMessagingSetupRefreshing
    val busy = refreshing || link.busy
    val telegramBusy = link.telegramLogin && link.busy
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
                val (title, body) = when (link.stage) {
                    MessagingStage.Connected -> "Account connected" to "Let’s continue setting up Murph."
                    else -> "Choose how to message Murph" to "Message Murph from your phone."
                }
                Text(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 42.sp),
                    color = MurphColors.Slate,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
                    color = MurphColors.SlateMuted,
                )
            }
            val confirmingInPlaceOfControls = telegramBusy ||
                refreshing && (link.stage == MessagingStage.Phone || link.stage == MessagingStage.Code)
            if (confirmingInPlaceOfControls) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessagingStatus("Confirming your account…")
                    if (telegramBusy) MurphLinkButton("Cancel", onCancelTelegram,
                        modifier = Modifier.semantics { contentDescription = "Cancel Telegram login" })
                }
            } else when (link.stage) {
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
                        if (!link.telegramError) MessagingError(link, !busy, onOpenAccountSettings)
                    }
                    MurphPrimaryButton(
                        "Send code", onSend, enabled = !busy,
                        leadingContent = if (link.busy) {
                            { CircularProgressIndicator(Modifier.size(16.dp), color = MurphColors.OnPrimary, strokeWidth = 2.dp) }
                        } else null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(1.dp).background(MurphColors.BorderWarm))
                        Text("or", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MurphColors.SlateMuted)
                        Box(Modifier.weight(1f).height(1.dp).background(MurphColors.BorderWarm))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MurphOutlineButton("Connect Telegram", onTelegram, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                        if (link.telegramError) MessagingError(link, !busy, onOpenAccountSettings)
                    }
                }
                MessagingStage.Code -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("We sent a code to ${link.displayPhone}.", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = MurphColors.SlateMuted)
                        MurphLinkButton("Resend", onSend, enabled = !busy)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OtpInput(link.code, onCode, codeFocus, onVerify, !busy)
                        if (link.busy) MessagingStatus("Checking code…") else MessagingError(link, !busy, onOpenAccountSettings)
                    }
                    MurphLinkButton("Use a different number", onChangeNumber, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                MessagingStage.Connected -> {
                    if (refreshing) MessagingStatus("Confirming your account…", Modifier.align(Alignment.CenterHorizontally))
                    state.messagingSetupMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MurphColors.SlateMuted)
                        MurphPrimaryButton("Try again", onConfirmConnected, enabled = !busy)
                    }
                }
            }
        }
    }
}

/** Inline progress line; it sits in the content flow so it never covers a control. */
@Composable
private fun MessagingStatus(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MurphColors.SageDark, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = MurphColors.SlateMuted)
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
