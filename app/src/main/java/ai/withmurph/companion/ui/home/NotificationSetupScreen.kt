package ai.withmurph.companion.ui.home

import ai.withmurph.companion.ui.components.MurphIcon
import ai.withmurph.companion.ui.components.MurphIconKind
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NotificationSetupScreen(busy: Boolean, onAllow: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MurphColors.Cream).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("NOTIFICATIONS · 3 OF 3", style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
        MurphIcon(MurphIconKind.Bell, Modifier.size(42.dp))
        Text("One sync reminder", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp),
            color = MurphColors.Slate, modifier = Modifier.semantics { heading() })
        Text("If Murph hasn't received new data, it can remind you to reopen the app. No meal alerts, health details, or marketing.",
            style = MaterialTheme.typography.bodyLarge, color = MurphColors.SlateMuted)
        MurphPrimaryButton(if (busy) "Setting up…" else "Allow reminder", onClick = onAllow, enabled = !busy)
        TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Keep notifications off") }
    }
}
