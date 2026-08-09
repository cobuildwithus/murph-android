package ai.withmurph.companion.ui.onboarding

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.InitialOnboardingDraft
import ai.withmurph.companion.app.InitialOnboardingStage
import ai.withmurph.companion.core.InitialOnboardingContactAvatar
import ai.withmurph.companion.core.InitialOnboardingPersona
import ai.withmurph.companion.core.InitialOnboardingVoice
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.components.MurphGhostButton
import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphMark
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun InitialOnboardingScreen(
    state: AppUiState,
    actions: MurphActions,
    onOpenSettings: () -> Unit,
    contactAvatarPainters: Map<String, Painter> = emptyMap(),
) {
    val onboarding = state.initialOnboarding ?: return
    val catalog = onboarding.catalog ?: return
    val draft = state.initialOnboardingDraft ?: return
    val stage = state.initialOnboardingStage ?: return
    var loadingVoiceId by remember { mutableStateOf<String?>(null) }
    var previewingVoiceId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        player?.release()
        player = null
        loadingVoiceId = null
        previewingVoiceId = null
    }

    fun togglePreview(voice: InitialOnboardingVoice) {
        if (previewingVoiceId == voice.id || loadingVoiceId == voice.id) {
            stopPreview()
            return
        }
        stopPreview()
        val next = MediaPlayer()
        try {
            next.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setDataSource(voice.previewUrl)
                setOnPreparedListener { prepared ->
                    if (player !== prepared) {
                        prepared.release()
                    } else {
                        loadingVoiceId = null
                        previewingVoiceId = voice.id
                        prepared.start()
                    }
                }
                setOnCompletionListener {
                    it.release()
                    if (player === it) {
                        player = null
                        loadingVoiceId = null
                        previewingVoiceId = null
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (player === failed) {
                        player = null
                        loadingVoiceId = null
                        previewingVoiceId = null
                    }
                    true
                }
            }
            player = next
            loadingVoiceId = voice.id
            next.prepareAsync()
        } catch (_: Exception) {
            next.release()
            if (player === next) player = null
            stopPreview()
        }
    }

    DisposableEffect(stage) {
        if (stage != InitialOnboardingStage.Voice) stopPreview()
        onDispose { stopPreview() }
    }

    BackHandler(
        enabled = state.isInitialOnboardingSaving || stage.hasVisibleBackAction(),
    ) {
        if (!state.isInitialOnboardingSaving) {
            actions.onSetInitialOnboardingStage(stage.previousStage())
        }
    }

    if (stage == InitialOnboardingStage.Welcome) {
        WelcomeScreen(state, actions, onOpenSettings)
        return
    }

    val stageScrollState = key(stage) { rememberScrollState() }
    val stageHeadingFocus = remember(stage) { FocusRequester() }
    LaunchedEffect(stage) {
        stageHeadingFocus.requestFocus()
        stageScrollState.scrollTo(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(stageScrollState)
                    .padding(horizontal = 20.dp)
                    .padding(top = 62.dp, bottom = 18.dp)
                    .semantics { paneTitle = stage.onboardingTitle() },
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                OnboardingHeader(stage, draft, catalog.personas, stageHeadingFocus)
                when (stage) {
                    InitialOnboardingStage.Contact -> ContactChoices(
                        avatars = onboarding.contactCard?.avatars.orEmpty(),
                        selectedId = draft.avatarId,
                        avatarPainters = contactAvatarPainters,
                        onSelect = actions.onSelectInitialOnboardingAvatar,
                    )
                    InitialOnboardingStage.MainPersona -> PersonaChoices(
                        personas = catalog.personas,
                        selectedId = draft.mainPersonaId,
                        onSelect = actions.onSelectInitialOnboardingMainPersona,
                    )
                    InitialOnboardingStage.SupportingPersona -> SupportingPersonaChoices(
                        personas = catalog.personas,
                        draft = draft,
                        onSelect = actions.onSelectInitialOnboardingSupportingPersona,
                    )
                    InitialOnboardingStage.Voice -> VoiceChoices(
                        personas = catalog.personas,
                        voices = catalog.voices,
                        draft = draft,
                        loadingVoiceId = loadingVoiceId,
                        previewingVoiceId = previewingVoiceId,
                        onSelect = actions.onSelectInitialOnboardingVoice,
                        onPreview = ::togglePreview,
                    )
                    InitialOnboardingStage.Tone -> Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        catalog.tones.forEach { tone ->
                            ChoiceCard(
                                title = tone.label,
                                description = tone.sample,
                                icon = MurphIconKind.Quote,
                                selected = draft.toneId == tone.id,
                                onClick = { actions.onSelectInitialOnboardingTone(tone.id) },
                            )
                        }
                    }
                    InitialOnboardingStage.Welcome -> Unit
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MurphColors.Cream),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalDivider(color = MurphColors.BorderWarm)
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.initialOnboardingMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MurphColors.Sienna,
                            textAlign = TextAlign.Center,
                        )
                        MurphLinkButton(
                            text = "Sign out and stop syncing",
                            onClick = actions.onSignOut,
                            enabled = !state.isInitialOnboardingSaving,
                        )
                    }
                    OnboardingFooter(stage, state.isInitialOnboardingSaving, actions)
                }
            }
        }

        OnboardingSettingsBar(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun OnboardingSettingsBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MurphColors.Cream),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Open Settings" },
            contentAlignment = Alignment.Center,
        ) {
            MurphIcon(
                kind = MurphIconKind.GearFilled,
                modifier = Modifier.size(20.dp),
                tint = MurphColors.Slate,
            )
        }
    }
}

@Composable
private fun OnboardingHeader(
    stage: InitialOnboardingStage,
    draft: InitialOnboardingDraft,
    personas: List<InitialOnboardingPersona>,
    headingFocus: FocusRequester,
) {
    val mainLabel = personas.firstOrNull { it.id == draft.mainPersonaId }?.label ?: "Murph"
    val step = when (stage) {
        InitialOnboardingStage.MainPersona -> 1
        InitialOnboardingStage.SupportingPersona -> 2
        InitialOnboardingStage.Voice -> 3
        InitialOnboardingStage.Tone -> 4
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        step?.let {
            Row(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Step $it of 4"
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STEP $it OF 4",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = MurphColors.SlateMuted,
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(
                                    if (index < it) MurphColors.SageDark
                                    else MurphColors.BorderWarm,
                                ),
                        )
                    }
                }
            }
        }
        Text(
            text = stage.onboardingTitle(),
            modifier = Modifier
                .focusRequester(headingFocus)
                .focusable()
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            style = onboardingTitleStyle(),
            color = MurphColors.Slate,
        )
        val description = when (stage) {
            InitialOnboardingStage.Contact ->
                "Pick the photo Murph shows up with in your contacts. Same Murph either way."
            InitialOnboardingStage.MainPersona ->
                "This is how Murph will show up most of the time. You can change it anytime."
            InitialOnboardingStage.SupportingPersona ->
                "Optional. Murph will lead with $mainLabel and borrow a little from one other personality."
            InitialOnboardingStage.Tone -> "Which sounds more like you?"
            else -> null
        }
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                ),
                color = MurphColors.SlateMuted,
            )
        }
    }
}

private fun InitialOnboardingStage.onboardingTitle(): String = when (this) {
    InitialOnboardingStage.Contact -> "Add Murph to your contacts"
    InitialOnboardingStage.MainPersona -> "Choose Murph’s main personality"
    InitialOnboardingStage.SupportingPersona -> "Add a supporting personality"
    InitialOnboardingStage.Voice -> "Choose a voice"
    InitialOnboardingStage.Tone -> "Pick Murph’s tone"
    InitialOnboardingStage.Welcome -> "Welcome to Murph"
}

@Composable
private fun ContactChoices(
    avatars: List<InitialOnboardingContactAvatar>,
    selectedId: String?,
    avatarPainters: Map<String, Painter>,
    onSelect: (String) -> Unit,
) {
    val selected = avatars.firstOrNull { it.id == selectedId } ?: avatars.firstOrNull()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        selected?.let {
            ContactAvatar(it, 96, avatarPainters[it.id])
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "Murph",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 25.sp),
                    color = MurphColors.Slate,
                )
                Text(
                    "CONTACT CARD",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = MurphColors.SlateMuted,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            avatars.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { avatar ->
                        val isSelected = avatar.id == selectedId
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(role = Role.RadioButton) { onSelect(avatar.id) }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = avatar.label
                                    this.selected = isSelected
                                }
                                .padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) {
                                            MurphColors.SageDark
                                        } else {
                                            MurphColors.BorderWarm
                                        },
                                        shape = CircleShape,
                                    )
                                    .padding(3.dp),
                            ) {
                                ContactAvatar(avatar, 56, avatarPainters[avatar.id])
                            }
                            Text(
                                avatar.label.uppercase(),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    letterSpacing = 1.sp,
                                ),
                                color = if (isSelected) MurphColors.Slate else MurphColors.SlateMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ContactAvatar(
    avatar: InitialOnboardingContactAvatar,
    size: Int,
    painter: Painter?,
) {
    val targetPixels = with(LocalDensity.current) { size.dp.roundToPx() }
    val imageState by remoteImage(
        url = if (painter == null) avatar.imageUrl else null,
        targetPixels = targetPixels,
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MurphColors.Sand),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else when (val current = imageState) {
            RemoteImageState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size((size * 0.28f).dp),
                color = MurphColors.SageDark,
                strokeWidth = 2.dp,
            )
            is RemoteImageState.Ready -> Image(
                bitmap = current.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            RemoteImageState.Empty -> Text(
                text = "M",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = (size * 0.4f).sp),
                color = MurphColors.Slate,
            )
        }
    }
}

@Composable
private fun PersonaChoices(
    personas: List<InitialOnboardingPersona>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        personas.forEach { persona ->
            ChoiceCard(
                title = persona.label,
                description = persona.description,
                icon = personaIcon(persona.id),
                selected = selectedId == persona.id,
                onClick = { onSelect(persona.id) },
            )
        }
    }
}

@Composable
private fun SupportingPersonaChoices(
    personas: List<InitialOnboardingPersona>,
    draft: InitialOnboardingDraft,
    onSelect: (String?) -> Unit,
) {
    val main = personas.firstOrNull { it.id == draft.mainPersonaId }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MurphColors.MutedSurface),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "YOUR MURPH",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                    color = MurphColors.SlateMuted,
                )
                Text(
                    "${main?.label ?: "Murph"} leads",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 25.sp),
                    color = MurphColors.Slate,
                )
                Text(
                    draft.supportingPersonaId?.let { id ->
                        "Supported by ${personas.firstOrNull { it.id == id }?.label ?: "another personality"}."
                    } ?: "No supporting personality added.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MurphColors.SlateMuted,
                )
            }
        }
        ChoiceCard(
            title = "No supporting personality",
            description = "Keep ${main?.label ?: "Murph"} focused.",
            icon = MurphIconKind.Minus,
            selected = draft.supportingPersonaId == null,
            onClick = { onSelect(null) },
        )
        personas.filter { it.id != draft.mainPersonaId }.forEach { persona ->
            ChoiceCard(
                title = persona.label,
                description = persona.supportDescription,
                icon = personaIcon(persona.id),
                selected = draft.supportingPersonaId == persona.id,
                onClick = { onSelect(persona.id) },
            )
        }
    }
}

@Composable
private fun VoiceChoices(
    personas: List<InitialOnboardingPersona>,
    voices: List<InitialOnboardingVoice>,
    draft: InitialOnboardingDraft,
    loadingVoiceId: String?,
    previewingVoiceId: String?,
    onSelect: (String) -> Unit,
    onPreview: (InitialOnboardingVoice) -> Unit,
) {
    val main = personas.firstOrNull { it.id == draft.mainPersonaId }
    val recommendedIds = main?.recommendedVoiceIds.orEmpty()
    val recommended = recommendedIds.mapNotNull { id -> voices.firstOrNull { it.id == id } }
    val other = voices.filter { it.id !in recommendedIds }
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        VoiceSection(
            title = "RECOMMENDED FOR ${(main?.label ?: "MURPH").uppercase()}",
            voices = recommended,
            draft = draft,
            loadingVoiceId = loadingVoiceId,
            previewingVoiceId = previewingVoiceId,
            onSelect = onSelect,
            onPreview = onPreview,
        )
        HorizontalDivider(color = MurphColors.BorderWarm)
        VoiceSection(
            title = "OTHER VOICES",
            voices = other,
            draft = draft,
            loadingVoiceId = loadingVoiceId,
            previewingVoiceId = previewingVoiceId,
            onSelect = onSelect,
            onPreview = onPreview,
        )
    }
}

@Composable
private fun VoiceSection(
    title: String,
    voices: List<InitialOnboardingVoice>,
    draft: InitialOnboardingDraft,
    loadingVoiceId: String?,
    previewingVoiceId: String?,
    onSelect: (String) -> Unit,
    onPreview: (InitialOnboardingVoice) -> Unit,
) {
    if (voices.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
            color = MurphColors.SlateMuted,
        )
        voices.forEach { voice ->
            val isSelected = draft.voiceId == voice.id
            val isLoading = loadingVoiceId == voice.id
            val isPreviewing = previewingVoiceId == voice.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MurphColors.SelectedSurface
                    } else {
                        MurphColors.Card
                    },
                ),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MurphColors.SageDark else MurphColors.BorderWarm,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.RadioButton) { onSelect(voice.id) }
                            .semantics {
                                selected = isSelected
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ChoiceIcon(MurphIconKind.Waveform)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                voice.label,
                                style = choiceTitleStyle(),
                                color = MurphColors.Slate,
                            )
                            Text(
                                voice.description,
                                style = choiceDescriptionStyle(),
                                color = MurphColors.SlateMuted,
                            )
                        }
                        SelectionIndicator(isSelected)
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(role = Role.Button) { onPreview(voice) }
                            .semantics {
                                contentDescription = if (isPreviewing) {
                                    "Pause ${voice.label} preview"
                                } else if (isLoading) {
                                    "Cancel ${voice.label} preview"
                                } else {
                                    "Play ${voice.label} preview"
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MurphColors.Sage.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MurphColors.SageDark,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                MurphIcon(
                                    kind = if (isPreviewing) {
                                        MurphIconKind.Pause
                                    } else {
                                        MurphIconKind.Play
                                    },
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    icon: MurphIconKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MurphColors.SelectedSurface else MurphColors.Card,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MurphColors.SageDark else MurphColors.BorderWarm,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChoiceIcon(icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = choiceTitleStyle(), color = MurphColors.Slate)
                Text(
                    description,
                    style = choiceDescriptionStyle(),
                    color = MurphColors.SlateMuted,
                )
            }
            SelectionIndicator(selected)
        }
    }
}

@Composable
private fun ChoiceIcon(kind: MurphIconKind) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MurphColors.MutedSurfaceOpaque),
        contentAlignment = Alignment.Center,
    ) {
        MurphIcon(kind = kind, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
    MurphIcon(
        kind = if (selected) MurphIconKind.CheckCircle else MurphIconKind.RadioCircle,
        modifier = Modifier.size(20.dp),
        tint = if (selected) MurphColors.SageDark else MurphColors.BorderWarm,
    )
}

private fun personaIcon(id: String): MurphIconKind = when (id) {
    "navy-seal" -> MurphIconKind.PersonaScope
    "stoic-philosopher" -> MurphIconKind.PersonaMountains
    "scientist" -> MurphIconKind.PersonaAtom
    "hype-coach" -> MurphIconKind.PersonaBolt
    "straight-talking-friend" -> MurphIconKind.PersonaChat
    else -> MurphIconKind.PersonaClassic
}

@Composable
private fun onboardingTitleStyle(): TextStyle = MaterialTheme.typography.displayLarge.copy(
    fontSize = 34.sp,
    lineHeight = 39.sp,
)

@Composable
private fun choiceTitleStyle(): TextStyle = MaterialTheme.typography.headlineMedium.copy(
    fontSize = 19.sp,
    lineHeight = 24.sp,
)

@Composable
private fun choiceDescriptionStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

private fun InitialOnboardingStage.hasVisibleBackAction(): Boolean = when (this) {
    InitialOnboardingStage.SupportingPersona,
    InitialOnboardingStage.Voice,
    InitialOnboardingStage.Tone,
    -> true
    InitialOnboardingStage.Contact,
    InitialOnboardingStage.MainPersona,
    InitialOnboardingStage.Welcome,
    -> false
}

private fun InitialOnboardingStage.previousStage(): InitialOnboardingStage = when (this) {
    InitialOnboardingStage.SupportingPersona -> InitialOnboardingStage.MainPersona
    InitialOnboardingStage.Voice -> InitialOnboardingStage.SupportingPersona
    InitialOnboardingStage.Tone -> InitialOnboardingStage.Voice
    InitialOnboardingStage.Contact,
    InitialOnboardingStage.MainPersona,
    InitialOnboardingStage.Welcome,
    -> this
}

@Composable
private fun OnboardingFooter(
    stage: InitialOnboardingStage,
    saving: Boolean,
    actions: MurphActions,
) {
    val back: () -> Unit
    val backTitle: String
    val forward: () -> Unit
    val forwardTitle: String
    when (stage) {
        InitialOnboardingStage.Contact -> {
            backTitle = "Skip"
            back = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.MainPersona) }
            forwardTitle = if (saving) "Opening…" else "Add Murph to Contacts"
            forward = actions.onPrepareInitialOnboardingContactCard
        }
        InitialOnboardingStage.MainPersona -> {
            backTitle = "Skip"
            back = actions.onSkipInitialOnboarding
            forwardTitle = "Continue"
            forward = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.SupportingPersona) }
        }
        InitialOnboardingStage.SupportingPersona -> {
            backTitle = "Back"
            back = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.MainPersona) }
            forwardTitle = "Continue"
            forward = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.Voice) }
        }
        InitialOnboardingStage.Voice -> {
            backTitle = "Back"
            back = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.SupportingPersona) }
            forwardTitle = "Continue"
            forward = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.Tone) }
        }
        InitialOnboardingStage.Tone -> {
            backTitle = "Back"
            back = { actions.onSetInitialOnboardingStage(InitialOnboardingStage.Voice) }
            forwardTitle = if (saving) "Saving…" else "Continue"
            forward = actions.onSaveInitialOnboarding
        }
        InitialOnboardingStage.Welcome -> return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MurphPrimaryButton(
            text = forwardTitle,
            onClick = forward,
            enabled = !saving,
            leadingContent = {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MurphColors.Card,
                        strokeWidth = 2.dp,
                    )
                }
            },
        )
        MurphGhostButton(
            text = backTitle,
            onClick = back,
            enabled = !saving,
        )
    }
}

@Composable
private fun WelcomeScreen(
    state: AppUiState,
    actions: MurphActions,
    onOpenSettings: () -> Unit,
) {
    val action = state.initialOnboarding?.contactAction
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(28.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MurphColors.Sage.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MurphMark(Modifier.size(width = 64.dp, height = 64.dp))
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Welcome to Murph",
                        modifier = Modifier.semantics { heading() },
                        style = onboardingTitleStyle(),
                        color = MurphColors.Slate,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Your personal health assistant is here. Message Murph to get started.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        ),
                        color = MurphColors.SlateMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                action?.let {
                    MurphPrimaryButton(
                        text = it.label,
                        onClick = { actions.onOpenInitialOnboardingContact(it.href) },
                    )
                }
                MurphGhostButton(
                    text = "Start exploring",
                    onClick = actions.onDismissCompletedInitialOnboarding,
                )
            }
        }
        OnboardingSettingsBar(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun remoteImage(
    url: String?,
    targetPixels: Int,
) = produceState<RemoteImageState>(
    initialValue = if (url == null) RemoteImageState.Empty else RemoteImageState.Loading,
    key1 = url,
    key2 = targetPixels,
) {
    value = if (url == null) {
        RemoteImageState.Empty
    } else {
        value = RemoteImageState.Loading
        withContext(Dispatchers.IO) {
            runCatching { loadBoundedImage(url, targetPixels) }
                .getOrNull()
                ?.let(RemoteImageState::Ready)
                ?: RemoteImageState.Empty
        }
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data object Empty : RemoteImageState
    data class Ready(val image: ImageBitmap) : RemoteImageState
}

private fun loadBoundedImage(url: String, requestedTargetPixels: Int): ImageBitmap? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 12_000
    connection.instanceFollowRedirects = false
    connection.useCaches = true
    return try {
        if (connection.responseCode !in 200..299) return null
        val declaredLength = connection.contentLengthLong
        if (declaredLength > MAX_AVATAR_BYTES) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_AVATAR_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_AVATAR_DIMENSION ||
            bounds.outHeight !in 1..MAX_AVATAR_DIMENSION
        ) return null
        val targetPixels = requestedTargetPixels.coerceIn(1, MAX_AVATAR_TARGET_PIXELS)
        val options = BitmapFactory.Options().apply {
            inSampleSize = avatarSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetPixels = targetPixels,
            )
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        val decodedPixels = decoded.width.toLong() * decoded.height.toLong()
        if (
            decoded.width > MAX_AVATAR_TARGET_PIXELS ||
            decoded.height > MAX_AVATAR_TARGET_PIXELS ||
            decodedPixels > MAX_AVATAR_DECODED_PIXELS
        ) {
            decoded.recycle()
            return null
        }
        decoded.asImageBitmap()
    } finally {
        connection.disconnect()
    }
}

internal fun avatarSampleSize(
    width: Int,
    height: Int,
    targetPixels: Int,
): Int {
    require(width > 0 && height > 0 && targetPixels > 0)
    val renderedTarget = targetPixels.coerceAtMost(MAX_AVATAR_TARGET_PIXELS)
    var sampleSize = 1
    while (
        ceilDiv(width, sampleSize) > MAX_AVATAR_TARGET_PIXELS ||
        ceilDiv(height, sampleSize) > MAX_AVATAR_TARGET_PIXELS
    ) {
        sampleSize *= 2
    }
    while (
        width / (sampleSize * 2) >= renderedTarget &&
        height / (sampleSize * 2) >= renderedTarget
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
private const val MAX_AVATAR_DIMENSION = 4_096
private const val MAX_AVATAR_TARGET_PIXELS = 512
private const val MAX_AVATAR_DECODED_PIXELS = 512L * 512L
