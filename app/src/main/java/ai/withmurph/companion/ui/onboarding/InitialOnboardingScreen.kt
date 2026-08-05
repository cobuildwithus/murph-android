package ai.withmurph.companion.ui.onboarding

import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.app.InitialOnboardingDraft
import ai.withmurph.companion.app.InitialOnboardingStage
import ai.withmurph.companion.core.InitialOnboardingContactAvatar
import ai.withmurph.companion.core.InitialOnboardingPersona
import ai.withmurph.companion.core.InitialOnboardingVoice
import ai.withmurph.companion.ui.MurphActions
import ai.withmurph.companion.ui.components.MurphGhostButton
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphMark
import ai.withmurph.companion.ui.components.MurphOutlineButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun InitialOnboardingScreen(
    state: AppUiState,
    actions: MurphActions,
) {
    val onboarding = state.initialOnboarding ?: return
    val catalog = onboarding.catalog ?: return
    val draft = state.initialOnboardingDraft ?: return
    val stage = state.initialOnboardingStage ?: return
    var previewingVoiceId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        player?.release()
        player = null
        previewingVoiceId = null
    }

    fun togglePreview(voice: InitialOnboardingVoice) {
        if (previewingVoiceId == voice.id) {
            stopPreview()
            return
        }
        stopPreview()
        try {
            val next = MediaPlayer().apply {
                setDataSource(voice.previewUrl)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    previewingVoiceId = null
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (player === failed) player = null
                    previewingVoiceId = null
                    true
                }
                prepareAsync()
            }
            player = next
            previewingVoiceId = voice.id
        } catch (_: Exception) {
            stopPreview()
        }
    }

    DisposableEffect(stage) {
        if (stage != InitialOnboardingStage.Voice) stopPreview()
        onDispose { stopPreview() }
    }

    if (stage == InitialOnboardingStage.Welcome) {
        WelcomeScreen(state, actions)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            OnboardingHeader(stage, draft, catalog.personas)
            when (stage) {
                InitialOnboardingStage.Contact -> ContactChoices(
                    avatars = onboarding.contactCard?.avatars.orEmpty(),
                    selectedId = draft.avatarId,
                    onSelect = actions.onSelectInitialOnboardingAvatar,
                )
                InitialOnboardingStage.MainPersona -> PersonaChoices(
                    personas = catalog.personas,
                    selectedId = draft.mainPersonaId,
                    supporting = false,
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
                    previewingVoiceId = previewingVoiceId,
                    onSelect = actions.onSelectInitialOnboardingVoice,
                    onPreview = ::togglePreview,
                )
                InitialOnboardingStage.Tone -> catalog.tones.forEach { tone ->
                    ChoiceCard(
                        title = tone.label,
                        description = tone.sample,
                        selected = draft.toneId == tone.id,
                        onClick = { actions.onSelectInitialOnboardingTone(tone.id) },
                    )
                }
                InitialOnboardingStage.Welcome -> Unit
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, MurphColors.BorderWarm))
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

@Composable
private fun OnboardingHeader(
    stage: InitialOnboardingStage,
    draft: InitialOnboardingDraft,
    personas: List<InitialOnboardingPersona>,
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
                modifier = Modifier.semantics { stateDescription = "Step $it of 4" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STEP $it OF 4",
                    style = MaterialTheme.typography.labelMedium,
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
                                .height(2.dp)
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
            text = when (stage) {
                InitialOnboardingStage.Contact -> "Add Murph to your contacts"
                InitialOnboardingStage.MainPersona -> "Choose Murph’s main personality"
                InitialOnboardingStage.SupportingPersona -> "Add a supporting personality"
                InitialOnboardingStage.Voice -> "Choose a voice"
                InitialOnboardingStage.Tone -> "Pick Murph’s tone"
                InitialOnboardingStage.Welcome -> "Welcome to Murph"
            },
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displayLarge,
            color = MurphColors.Slate,
        )
        val description = when (stage) {
            InitialOnboardingStage.Contact ->
                "Pick the photo Murph shows up with in your contacts. Same Murph either way."
            InitialOnboardingStage.MainPersona ->
                "This is how Murph will show up most of the time. You can change it anytime."
            InitialOnboardingStage.SupportingPersona ->
                "Optional. $mainLabel will lead and borrow a little from one other personality."
            InitialOnboardingStage.Tone -> "Which sounds more like you?"
            else -> null
        }
        description?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MurphColors.SlateMuted)
        }
    }
}

@Composable
private fun ContactChoices(
    avatars: List<InitialOnboardingContactAvatar>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = avatars.firstOrNull { it.id == selectedId } ?: avatars.firstOrNull()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        selected?.let {
            ContactAvatar(it, 96)
            Text("Murph", style = MaterialTheme.typography.headlineLarge, color = MurphColors.Slate)
            Text(
                "CONTACT CARD",
                style = MaterialTheme.typography.labelMedium,
                color = MurphColors.SlateMuted,
            )
        }
        avatars.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { avatar ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.RadioButton) { onSelect(avatar.id) }
                            .semantics { this.selected = avatar.id == selectedId }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.border(
                                width = if (avatar.id == selectedId) 2.dp else 1.dp,
                                color = if (avatar.id == selectedId) {
                                    MurphColors.SageDark
                                } else {
                                    MurphColors.BorderWarm
                                },
                                shape = CircleShape,
                            ).padding(3.dp),
                        ) { ContactAvatar(avatar, 54) }
                        Text(
                            avatar.label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MurphColors.SlateMuted,
                            maxLines = 1,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ContactAvatar(avatar: InitialOnboardingContactAvatar, size: Int) {
    val image by remoteImage(avatar.imageUrl)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MurphColors.MutedSurfaceOpaque),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = avatar.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = avatar.label.take(1).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = MurphColors.SageDark,
            )
        }
    }
}

@Composable
private fun PersonaChoices(
    personas: List<InitialOnboardingPersona>,
    selectedId: String,
    supporting: Boolean,
    onSelect: (String) -> Unit,
) {
    personas.forEach { persona ->
        ChoiceCard(
            title = persona.label,
            description = if (supporting) persona.supportDescription else persona.description,
            selected = selectedId == persona.id,
            onClick = { onSelect(persona.id) },
        )
    }
}

@Composable
private fun SupportingPersonaChoices(
    personas: List<InitialOnboardingPersona>,
    draft: InitialOnboardingDraft,
    onSelect: (String?) -> Unit,
) {
    val main = personas.firstOrNull { it.id == draft.mainPersonaId }
    Card(
        colors = CardDefaults.cardColors(containerColor = MurphColors.MutedSurfaceOpaque),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("YOUR MURPH", style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
            Text(
                "${main?.label ?: "Murph"} leads",
                style = MaterialTheme.typography.headlineLarge,
                color = MurphColors.Slate,
            )
            Text(
                draft.supportingPersonaId?.let { id ->
                    "Supported by ${personas.firstOrNull { it.id == id }?.label ?: "another personality"}."
                } ?: "No supporting personality added.",
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SlateMuted,
            )
        }
    }
    ChoiceCard(
        title = "No supporting personality",
        description = "Keep ${main?.label ?: "Murph"} focused.",
        selected = draft.supportingPersonaId == null,
        onClick = { onSelect(null) },
    )
    personas.filter { it.id != draft.mainPersonaId }.forEach { persona ->
        ChoiceCard(
            title = persona.label,
            description = persona.supportDescription,
            selected = draft.supportingPersonaId == persona.id,
            onClick = { onSelect(persona.id) },
        )
    }
}

@Composable
private fun VoiceChoices(
    personas: List<InitialOnboardingPersona>,
    voices: List<InitialOnboardingVoice>,
    draft: InitialOnboardingDraft,
    previewingVoiceId: String?,
    onSelect: (String) -> Unit,
    onPreview: (InitialOnboardingVoice) -> Unit,
) {
    val main = personas.firstOrNull { it.id == draft.mainPersonaId }
    val recommendedIds = main?.recommendedVoiceIds.orEmpty()
    val recommended = recommendedIds.mapNotNull { id -> voices.firstOrNull { it.id == id } }
    val other = voices.filter { it.id !in recommendedIds }
    VoiceSection(
        title = "RECOMMENDED FOR ${(main?.label ?: "MURPH").uppercase()}",
        voices = recommended,
        draft = draft,
        previewingVoiceId = previewingVoiceId,
        onSelect = onSelect,
        onPreview = onPreview,
    )
    VoiceSection(
        title = "OTHER VOICES",
        voices = other,
        draft = draft,
        previewingVoiceId = previewingVoiceId,
        onSelect = onSelect,
        onPreview = onPreview,
    )
}

@Composable
private fun VoiceSection(
    title: String,
    voices: List<InitialOnboardingVoice>,
    draft: InitialOnboardingDraft,
    previewingVoiceId: String?,
    onSelect: (String) -> Unit,
    onPreview: (InitialOnboardingVoice) -> Unit,
) {
    if (voices.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
    voices.forEach { voice ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.RadioButton) { onSelect(voice.id) }
                .semantics { selected = draft.voiceId == voice.id },
            colors = CardDefaults.cardColors(
                containerColor = if (draft.voiceId == voice.id) {
                    MurphColors.MutedSurfaceOpaque
                } else {
                    MurphColors.Card
                },
            ),
            border = BorderStroke(
                if (draft.voiceId == voice.id) 2.dp else 1.dp,
                if (draft.voiceId == voice.id) MurphColors.SageDark else MurphColors.BorderWarm,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(voice.label, style = MaterialTheme.typography.headlineMedium, color = MurphColors.Slate)
                    Text(voice.description, style = MaterialTheme.typography.bodyMedium, color = MurphColors.SlateMuted)
                }
                MurphOutlineButton(
                    text = if (previewingVoiceId == voice.id) "Stop" else "Play",
                    onClick = { onPreview(voice) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MurphColors.MutedSurfaceOpaque else MurphColors.Card,
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MurphColors.SageDark else MurphColors.BorderWarm,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MurphColors.Slate)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MurphColors.SlateMuted)
        }
    }
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
        MurphGhostButton(
            text = backTitle,
            onClick = back,
            enabled = !saving,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MurphPrimaryButton(forwardTitle, forward, enabled = !saving)
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MurphColors.Card,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(state: AppUiState, actions: MurphActions) {
    val action = state.initialOnboarding?.contactAction
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurphColors.Cream)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.weight(1f))
        MurphMark(Modifier.size(width = 96.dp, height = 64.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "Welcome to Murph",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displayLarge,
            color = MurphColors.Slate,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your personal health assistant is here. Message Murph to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MurphColors.SlateMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        action?.let {
            MurphPrimaryButton(
                text = it.label,
                onClick = { actions.onOpenInitialOnboardingContact(it.href) },
            )
            Spacer(Modifier.height(8.dp))
        }
        MurphGhostButton("Start exploring", actions.onDismissCompletedInitialOnboarding)
    }
}

@Composable
private fun remoteImage(url: String?) = produceState<ImageBitmap?>(initialValue = null, url) {
    value = if (url == null) null else withContext(Dispatchers.IO) {
        runCatching { loadBoundedImage(url) }.getOrNull()
    }
}

private fun loadBoundedImage(url: String): ImageBitmap? {
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
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } finally {
        connection.disconnect()
    }
}

private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
private const val MAX_AVATAR_DIMENSION = 4_096
