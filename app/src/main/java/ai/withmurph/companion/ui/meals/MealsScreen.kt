package ai.withmurph.companion.ui.meals

import android.graphics.BitmapFactory
import ai.withmurph.companion.app.AppUiState
import ai.withmurph.companion.core.MealPhotoCaptureState
import ai.withmurph.companion.core.MealPhotoReviewItem
import ai.withmurph.companion.core.MealPhotoReviewStatus
import ai.withmurph.companion.ui.components.MurphCard
import ai.withmurph.companion.ui.components.MurphLinkButton
import ai.withmurph.companion.ui.components.MurphOutlineButton
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MealsScreen(
    state: AppUiState,
    onEnable: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTurnOff: () -> Unit,
    onApprove: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Meals",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                ),
                color = MurphColors.Slate,
            )
            Text(
                text = "Take meal photos as usual. Murph can privately check new photos on this phone and suggest likely meals for you to review.",
                style = MaterialTheme.typography.bodyLarge,
                color = MurphColors.SlateMuted,
            )
        }

        MurphCard {
            Text(
                text = mealCaptureTitle(state.mealPhotoCapture),
                style = MaterialTheme.typography.titleMedium,
                color = MurphColors.Slate,
            )
            Text(
                text = mealCaptureDetail(state.mealPhotoCapture),
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SlateMuted,
            )
            state.mealPhotoMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MurphColors.SlateMuted,
                )
            }
            when (state.mealPhotoCapture) {
                MealPhotoCaptureState.Unavailable -> Unit
                MealPhotoCaptureState.Off -> MurphPrimaryButton(
                    text = "Turn on meal suggestions",
                    onClick = onEnable,
                    enabled = !state.isMealPhotoBusy,
                )
                MealPhotoCaptureState.NeedsPhotosAccess,
                MealPhotoCaptureState.NeedsFullAccess,
                -> {
                    MurphPrimaryButton(
                        text = "Open Photos settings",
                        onClick = onOpenSettings,
                        enabled = !state.isMealPhotoBusy,
                    )
                    MurphLinkButton(
                        text = "Turn off meal suggestions",
                        onClick = onTurnOff,
                        enabled = !state.isMealPhotoBusy,
                    )
                }
                MealPhotoCaptureState.Enabling -> MurphPrimaryButton(
                    text = "Turning on…",
                    onClick = {},
                    enabled = false,
                )
                MealPhotoCaptureState.NeedsAttention -> {
                    MurphPrimaryButton(
                        text = "Try again",
                        onClick = onRefresh,
                        enabled = !state.isMealPhotoBusy,
                    )
                    MurphLinkButton(
                        text = "Turn off meal suggestions",
                        onClick = onTurnOff,
                        enabled = !state.isMealPhotoBusy,
                    )
                }
                MealPhotoCaptureState.On -> {
                    MurphPrimaryButton(
                        text = if (state.isMealPhotoBusy) "Checking…" else "Check now",
                        onClick = onRefresh,
                        enabled = !state.isMealPhotoBusy,
                    )
                    MurphLinkButton(
                        text = "Turn off meal suggestions",
                        onClick = onTurnOff,
                        enabled = !state.isMealPhotoBusy,
                    )
                }
            }
        }

        MurphCard {
            Text(
                text = "Private by default",
                style = MaterialTheme.typography.titleMedium,
                color = MurphColors.Slate,
            )
            PrivacyLine("Only photos added after you turn this on are considered.")
            PrivacyLine("If Photos access is turned off while suggestions are on, photos added during that interval may be checked after full access returns.")
            PrivacyLine("Meal detection runs on this phone. Classification labels never leave it.")
            PrivacyLine("Every likely meal stays on this phone until you see its thumbnail and tap Yes, send.")
            PrivacyLine("After you tap Yes, send, Murph creates a fresh JPEG and strips metadata before upload.")
            PrivacyLine("Murph does not sell these photos or use them to train general-purpose AI models.")
        }

        Text(
            text = "Prefer not to share Photos access? Text meal photos in your existing Murph conversation instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MurphColors.SlateMuted,
        )

        if (state.mealPhotoReviewItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Recent photos",
                    style = MaterialTheme.typography.titleLarge,
                    color = MurphColors.Slate,
                )
                state.mealPhotoReviewItems.forEach { item ->
                    MealReviewCard(
                        item = item,
                        isBusy = state.mealPhotoActionId == item.id,
                        onApprove = { onApprove(item.id) },
                        onDismiss = { onDismiss(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MurphColors.SlateMuted,
    )
}

@Composable
private fun MealReviewCard(
    item: MealPhotoReviewItem,
    isBusy: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    MurphCard {
        item.thumbnailJpeg?.let { bytes ->
            val image = remember(item.id, bytes.contentHashCode()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = if (item.status == MealPhotoReviewStatus.NeedsReview) {
                        "Meal photo suggestion awaiting review"
                    } else {
                        "Meal photo sent to Murph"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = REVIEW_TIME.format(item.capturedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MurphColors.SlateMuted,
        )
        if (item.status == MealPhotoReviewStatus.NeedsReview) {
            Text(
                text = "Is this a meal?",
                style = MaterialTheme.typography.titleMedium,
                color = MurphColors.Slate,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MurphOutlineButton(
                    text = "No",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                )
                MurphOutlineButton(
                    text = "Yes, send",
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy && item.thumbnailJpeg != null,
                )
            }
        } else {
            Text(
                text = "Sent to Murph",
                style = MaterialTheme.typography.bodyMedium,
                color = MurphColors.SageDark,
            )
        }
    }
}

private fun mealCaptureTitle(state: MealPhotoCaptureState): String = when (state) {
    MealPhotoCaptureState.Unavailable -> "Not available on this phone"
    MealPhotoCaptureState.Off -> "Meal suggestions are off"
    MealPhotoCaptureState.Enabling -> "Turning on meal suggestions"
    MealPhotoCaptureState.On -> "Meal suggestions are on"
    MealPhotoCaptureState.NeedsPhotosAccess,
    MealPhotoCaptureState.NeedsFullAccess,
    -> "Full Photos access is needed"
    MealPhotoCaptureState.NeedsAttention -> "Meal suggestions need attention"
}

private fun mealCaptureDetail(state: MealPhotoCaptureState): String = when (state) {
    MealPhotoCaptureState.Unavailable ->
        "You can still text meal photos in your existing Murph conversation."
    MealPhotoCaptureState.Off ->
        "Nothing is read or uploaded until you explicitly turn this on. Suggested photos still require Yes, send."
    MealPhotoCaptureState.Enabling ->
        "Murph is setting a future-only boundary before it can suggest new meal photos."
    MealPhotoCaptureState.On ->
        "Android may delay background work. Opening Murph gives it another chance to check new photos."
    MealPhotoCaptureState.NeedsPhotosAccess,
    MealPhotoCaptureState.NeedsFullAccess,
    -> "Selected-photo access cannot observe future camera photos, so background suggestions require full library access."
    MealPhotoCaptureState.NeedsAttention ->
        "Open Murph while online to repair upload access without reconsidering older photos."
}

private val REVIEW_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault())
