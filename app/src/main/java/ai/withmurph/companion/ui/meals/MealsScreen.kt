package ai.withmurph.companion.ui.meals

import ai.withmurph.companion.core.ManualMealsState
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.journal.JournalIcon
import ai.withmurph.companion.ui.theme.MurphColors
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun MealsScreen(
    state: ManualMealsState,
    onPreparePhotos: (String, List<Uri>, File?) -> Unit,
    canAcquirePhotos: Boolean = true,
    onRecover: () -> Unit = {},
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit = {},
    reserveStatusBarInset: Boolean = true,
) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { onRefresh() }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var pickerGeneration by rememberSaveable { mutableStateOf(state.selectionGeneration) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(state.selectionGeneration) {
        onDispose {
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) {
                cameraPath?.let(::File)?.let { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.meal-camera", file)
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    file.delete()
                }
            }
        }
    }
    fun prepare(uris: List<Uri>, cameraFile: File? = null) {
        val generation = pickerGeneration
        // Transfer camera ownership before the retained operation starts.
        if (cameraFile != null) cameraPath = null
        onPreparePhotos(generation, uris, cameraFile)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) prepare(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let(::File)
        if (success && file != null && file.isFile) {
            prepare(listOf(FileProvider.getUriForFile(context, "${context.packageName}.meal-camera", file)), file)
        } else { file?.delete(); cameraPath = null }
    }
    LaunchedEffect(canAcquirePhotos) { if (!canAcquirePhotos) menu = false }
    LaunchedEffect(state.selectionGeneration) { localMessage = null; menu = false }
    Column(Modifier.fillMaxSize().background(MurphColors.Cream)
        .then(if (reserveStatusBarInset) Modifier.statusBarsPadding() else Modifier)
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Meals", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge, color = MurphColors.Slate)
            Box {
                IconButton(onClick = { menu = true }, enabled = canAcquirePhotos && !state.preparing && !state.sending && !state.partialFailure && state.selected.size < 10,
                    modifier = Modifier.size(44.dp).background(MurphColors.MutedSurface, CircleShape).semantics { contentDescription = "Add meal photos" }) {
                    Text("+", color = MurphColors.Slate, fontSize = 26.sp)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Camera") }, onClick = {
                        menu = false
                        pickerGeneration = state.selectionGeneration
                        try {
                            val directory = File(context.cacheDir, "meal-camera").apply { mkdirs() }
                            val file = File.createTempFile("capture-", ".jpg", directory)
                            cameraPath = file.absolutePath
                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.meal-camera", file))
                        } catch (_: Exception) {
                            cameraPath?.let(::File)?.delete(); cameraPath = null
                            localMessage = "Camera isn't available on this device."
                        }
                    })
                    DropdownMenuItem(text = { Text("Photos") }, onClick = {
                        menu = false
                        pickerGeneration = state.selectionGeneration
                        try { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        catch (_: Exception) { localMessage = "Photos couldn't open. Try again." }
                    })
                }
            }
        }
        if (!canAcquirePhotos) {
            Text("Reconnect to add or send meal photos.", color = MurphColors.SlateMuted)
            MurphPrimaryButton("Try again", onClick = onRecover)
        }
        if (state.preparing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preparing photos…", color = MurphColors.SlateMuted)
            }
        }
        if (state.selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(MurphColors.Card, RoundedCornerShape(22.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.selected, key = { it.id }) { photo ->
                        Box {
                            MealThumbnail(photo.thumbnail, "Selected meal photo", Modifier.size(112.dp))
                            IconButton(onClick = { onRemove(photo.id) }, enabled = !state.sending,
                                modifier = Modifier.align(Alignment.TopEnd).size(44.dp).semantics { contentDescription = "Remove meal photo" }) {
                                Text("×", Modifier.background(MurphColors.Card, CircleShape).padding(horizontal = 8.dp), color = MurphColors.Slate, fontSize = 24.sp)
                            }
                        }
                    }
                }
                Text(if (state.sending) "Sending ${state.current} of ${state.total}"
                    else if (state.selected.size == 1) "1 photo ready to send" else "${state.selected.size} photos ready to send",
                    color = MurphColors.SlateMuted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.sending) LinearProgressIndicator(progress = { state.current.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                MurphPrimaryButton("Send to Murph", onClick = onSend, enabled = canAcquirePhotos && !state.sending && !state.preparing)
            }
        }
        (localMessage ?: state.message)?.let { message ->
            Text(message, color = if (state.partialFailure) MurphColors.Sienna else MurphColors.SlateMuted,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.selected.isEmpty() && state.sent.isEmpty() && !state.preparing) {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                JournalIcon("meal", Modifier.size(34.dp))
                Text("No meal photos yet", style = MaterialTheme.typography.headlineMedium, color = MurphColors.Slate)
                Text("Use Add above whenever you want to send a meal photo.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MurphColors.SlateMuted)
            }
        }
        if (state.sent.isNotEmpty()) {
            Text("SENT TO MURPH", style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.sent.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { photo -> MealThumbnail(photo.thumbnail, "Sent meal photo", Modifier.weight(1f).aspectRatio(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealThumbnail(bytes: ByteArray, description: String, modifier: Modifier) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, contentDescription = description, modifier = modifier.clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
}
