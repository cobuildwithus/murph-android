package ai.withmurph.companion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.withmurph.companion.ui.theme.MurphColors

@Composable
fun MurphPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MurphColors.SageDark),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun MurphOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MurphColors.BorderWarm),
    ) {
        Text(text = text, modifier = Modifier.padding(vertical = 5.dp))
    }
}

@Composable
fun MurphCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MurphColors.Card),
        border = BorderStroke(1.dp, MurphColors.BorderWarm),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onClick: () -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = MurphColors.Slate),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MurphColors.SlateMuted,
                    )
                }
            }
            if (actionLabel != null) {
                Text(
                    actionLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MurphColors.SageDark,
                )
            }
        }
    }
}
