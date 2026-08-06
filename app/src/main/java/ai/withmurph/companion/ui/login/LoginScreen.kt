package ai.withmurph.companion.ui.login

import ai.withmurph.companion.auth.CountryDialCode
import ai.withmurph.companion.auth.LoginUiState
import ai.withmurph.companion.core.LoginMethod
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphLogo
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.components.MurphTextField
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: LoginUiState,
    onMethodChanged: (LoginMethod) -> Unit,
    onPhoneCountryChanged: (CountryDialCode) -> Unit,
    onDestinationChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onSendCode: () -> Unit,
    onConfirmCode: () -> Unit,
    onResendCode: () -> Unit,
    onChangeDestination: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    var showsCountryPicker by rememberSaveable { mutableStateOf(false) }
    var hasFocusedOnce by remember { mutableStateOf(false) }
    val destinationFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(state.method, state.codeSent, showsCountryPicker) {
        if (showsCountryPicker) return@LaunchedEffect
        delay(if (hasFocusedOnce) 80 else 450)
        if (state.codeSent) {
            codeFocus.requestFocus()
        } else {
            destinationFocus.requestFocus()
        }
        hasFocusedOnce = true
        keyboard?.show()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val topSpacing = (maxHeight * 0.14f).coerceIn(40.dp, 112.dp)
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Spacer(Modifier.height(topSpacing))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MurphLogo()

                    if (state.codeSent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "We sent a code to ${state.normalizedDestination}.",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                color = MurphColors.SlateMuted,
                            )
                            MurphLinkButton(
                                text = "Resend",
                                onClick = onResendCode,
                                enabled = !state.isInFlight,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.height(44.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "Health challenges with friends.",
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                color = MurphColors.SlateMuted,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))

                if (state.codeSent) {
                    CodeStage(
                        state = state,
                        focusRequester = codeFocus,
                        onCodeChanged = onCodeChanged,
                        onConfirmCode = onConfirmCode,
                        onChangeDestination = onChangeDestination,
                    )
                } else {
                    DestinationStage(
                        state = state,
                        focusRequester = destinationFocus,
                        onChooseCountry = { showsCountryPicker = true },
                        onDestinationChanged = onDestinationChanged,
                        onSendCode = onSendCode,
                        onMethodChanged = {
                            focusManager.clearFocus()
                            onMethodChanged(it)
                        },
                    )
                }

                if (state.errorMessage != null) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MurphColors.SlateMuted,
                    )
                }
            }

            if (!imeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MurphLinkButton(
                        text = "Privacy",
                        onClick = onOpenPrivacy,
                        enabled = !state.isInFlight,
                    )
                    Spacer(Modifier.width(18.dp))
                    MurphLinkButton(
                        text = "Terms",
                        onClick = onOpenTerms,
                        enabled = !state.isInFlight,
                    )
                }
            }
        }
    }

    if (showsCountryPicker) {
        CountryPicker(
            selection = state.phoneCountry,
            onSelect = {
                onPhoneCountryChanged(it)
                showsCountryPicker = false
            },
            onDismiss = { showsCountryPicker = false },
        )
    }
}

@Composable
private fun DestinationStage(
    state: LoginUiState,
    focusRequester: FocusRequester,
    onChooseCountry: () -> Unit,
    onDestinationChanged: (String) -> Unit,
    onSendCode: () -> Unit,
    onMethodChanged: (LoginMethod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.method == LoginMethod.Phone) {
                CountryButton(
                    country = state.phoneCountry,
                    enabled = !state.isInFlight,
                    onClick = onChooseCountry,
                )
            }
            MurphTextField(
                value = state.destination,
                onValueChange = onDestinationChanged,
                label = if (state.method == LoginMethod.Phone) "Phone number" else "Email address",
                placeholder = if (state.method == LoginMethod.Phone) {
                    "555 555 0100"
                } else {
                    "you@example.com"
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (state.method == LoginMethod.Phone) {
                        KeyboardType.Phone
                    } else {
                        KeyboardType.Email
                    },
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { onSendCode() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                enabled = !state.isInFlight,
                autofillContentType = if (state.method == LoginMethod.Phone) {
                    ContentType.PhoneNumberNational
                } else {
                    ContentType.EmailAddress
                },
            )
        }

        MurphPrimaryButton(
            text = if (state.isInFlight) "Sending…" else "Send code",
            onClick = onSendCode,
            enabled = state.canSendCode,
        )

        MurphLinkButton(
            text = if (state.method == LoginMethod.Phone) {
                "Use email instead"
            } else {
                "Use phone number instead"
            },
            onClick = {
                onMethodChanged(
                    if (state.method == LoginMethod.Phone) LoginMethod.Email else LoginMethod.Phone,
                )
            },
            modifier = Modifier.align(Alignment.Start),
            enabled = !state.isInFlight,
        )
    }
}

@Composable
private fun CountryButton(
    country: CountryDialCode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(shape)
            .background(MurphColors.Card.copy(alpha = 0.9f))
            .border(1.dp, MurphColors.BorderWarm, shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Country or region, ${country.localizedName}, ${country.dialCode}"
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = country.dialCode,
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.Slate,
        )
        Canvas(Modifier.size(12.dp)) {
            drawLine(
                color = MurphColors.SlateMuted,
                start = Offset(size.width * 0.18f, size.height * 0.38f),
                end = Offset(size.width * 0.5f, size.height * 0.68f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = MurphColors.SlateMuted,
                start = Offset(size.width * 0.5f, size.height * 0.68f),
                end = Offset(size.width * 0.82f, size.height * 0.38f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CodeStage(
    state: LoginUiState,
    focusRequester: FocusRequester,
    onCodeChanged: (String) -> Unit,
    onConfirmCode: () -> Unit,
    onChangeDestination: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OtpInput(
            value = state.code,
            onValueChange = onCodeChanged,
            focusRequester = focusRequester,
            onComplete = onConfirmCode,
            enabled = !state.isInFlight,
        )
        MurphPrimaryButton(
            text = if (state.isInFlight) "Signing in…" else "Sign in",
            onClick = onConfirmCode,
            enabled = state.canConfirmCode,
        )
        MurphLinkButton(
            text = if (state.method == LoginMethod.Email) {
                "Use a different email"
            } else {
                "Use a different number"
            },
            onClick = onChangeDestination,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !state.isInFlight,
        )
    }
}

@Composable
private fun OtpInput(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onComplete: () -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(6) { index ->
                val active = focused && index == minOf(value.length, 5) && value.length < 6
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MurphColors.Card.copy(alpha = 0.9f))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) MurphColors.Ring else MurphColors.BorderWarm,
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.getOrNull(index)?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp),
                        color = MurphColors.Slate,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = { input ->
                val digits = input.filter(Char::isDigit).take(6)
                onValueChange(digits)
                if (value.length < 6 && digits.length == 6) {
                    onComplete()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "6-digit verification code"
                    contentType = ContentType.SmsOtpCode
                }
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onComplete() }),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
            decorationBox = { innerTextField -> innerTextField() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPicker(
    selection: CountryDialCode,
    onSelect: (CountryDialCode) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val results = remember(query) {
        val trimmed = query.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (trimmed.isEmpty()) {
            CountryDialCode.SortedByName
        } else {
            CountryDialCode.SortedByName.filter { country ->
                country.localizedName.contains(trimmed, ignoreCase = true) ||
                    country.region.contains(trimmed, ignoreCase = true) ||
                    (digits.isNotEmpty() && country.dialCode.drop(1).startsWith(digits))
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(500)
        searchFocus.requestFocus()
        keyboard?.show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MurphColors.Cream,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MurphColors.SlateMuted.copy(alpha = 0.35f)),
                )
                MurphLinkButton(
                    text = "Close",
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 440.dp, max = 680.dp)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MurphTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search countries",
                placeholder = "Search countries",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(results, key = { it.region }) { country ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = country == selection,
                                role = Role.RadioButton,
                                onClick = { onSelect(country) },
                            )
                            .padding(horizontal = 4.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = country.localizedName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                            color = MurphColors.Slate,
                        )
                        Text(
                            text = country.dialCode,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 15.sp),
                            color = MurphColors.SlateMuted,
                        )
                        if (country == selection) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MurphColors.SageDark,
                            )
                        }
                    }
                    HorizontalDivider(color = MurphColors.BorderWarm)
                }
            }
        }
    }
}
