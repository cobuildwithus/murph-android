package ai.withmurph.companion.ui.login

import ai.withmurph.companion.ui.components.MurphLogo
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val welcomeTitles = listOf(
    "Health is hard. Don’t do it alone.", "Health is better with your people.",
    "Answers that know the backstory.", "Ask once. Murph follows through.",
    "The meal tracker you never open.", "Health chores, handled.",
)
private val welcomeDescriptions = listOf(
    "Murph is your personal health AI. Whatever healthier looks like for you, Murph helps you get there.",
    "Start a challenge in the group chat. Murph keeps score and calls the winner.",
    "Murph keeps the useful context, so you never start from scratch.",
    "Research, reminders, and check-ins keep moving in the same thread.",
    "Snap the plate and move on. Murph logs the meal and texts your tally at night.",
    "Appointments, refills, and nearby care without another to-do list.",
)

@Composable
fun AuthWelcomeScreen(onContinue: () -> Unit, onOpenPrivacy: () -> Unit, onOpenTerms: () -> Unit) {
    val pager = rememberPagerState { 6 }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(MurphColors.Cream).systemBarsPadding()) {
        val fontScale = LocalDensity.current.fontScale
        val cardHeight = (maxHeight * .52f).coerceIn(390.dp, 470.dp) *
            (if (fontScale > 1.3f) fontScale else 1f)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(bottom = 6.dp)) {
            MurphLogo(Modifier.padding(start = 24.dp, top = 12.dp))
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth().height(cardHeight)) {
                Box(Modifier.matchParentSize().padding(horizontal = 24.dp).graphicsLayer {
                    translationX = 10.dp.toPx(); translationY = 7.dp.toPx(); rotationZ = 3f; scaleX = .96f; scaleY = .96f
                }.background(MurphColors.Sand, RoundedCornerShape(30.dp)))
                Box(Modifier.matchParentSize().padding(horizontal = 24.dp).graphicsLayer {
                    translationX = 6.dp.toPx(); translationY = 4.dp.toPx(); rotationZ = 1.5f; scaleX = .98f; scaleY = .98f
                }.background(Color(0xFF5A6E32), RoundedCornerShape(30.dp)))
                HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 24.dp,
                    modifier = Modifier.fillMaxSize()) { page -> WelcomeCard(page) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.Center) {
                repeat(6) { index ->
                    Box(Modifier.size(width = if (index == pager.currentPage) 32.dp else 20.dp, height = 44.dp)
                        .clickable(role = Role.Button) { scope.launch { pager.animateScrollToPage(index) } }
                        .semantics { contentDescription = "Welcome card ${index + 1} of 6"; selected = index == pager.currentPage },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = if (index == pager.currentPage) 22.dp else 7.dp, height = 7.dp)
                            .background(if (index == pager.currentPage) MurphColors.SageDark else MurphColors.Sand, RoundedCornerShape(4.dp)))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MurphPrimaryButton("Join for free", onClick = onContinue)
                TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Log in", color = MurphColors.Slate, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onOpenPrivacy) { Text("Privacy") }
                TextButton(onClick = onOpenTerms) { Text("Terms") }
            }
        }
    }
}

@Composable
private fun WelcomeCard(page: Int) {
    val ink = Color(0xFF2D3436)
    val cream = Color(0xFFF5F0E8)
    val sand = Color(0xFFD4C4A8)
    val sage = Color(0xFF5A6E32)
    val lightCard = page == 2 || page == 4
    val background = when (page) { 1, 3 -> sage; 2, 4 -> sand; else -> ink }
    val foreground = if (lightCard) ink else cream
    Column(Modifier.fillMaxSize().background(background, RoundedCornerShape(30.dp))
        .padding(horizontal = 28.dp, vertical = 24.dp).verticalScroll(rememberScrollState())) {
        Text(welcomeTitles[page], color = foreground,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 39.sp),
            modifier = Modifier.semantics { heading() })
        Text(welcomeDescriptions[page], color = foreground.copy(alpha = .8f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOf("MESSAGES", "WALK CHALLENGE", "TWO WEEKS LATER", "FRIDAY CHECK-IN", "TODAY · 3 LOGGED MEALS", "HEALTH CHORES")[page],
                color = foreground, style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp))
            when (page) {
                0 -> {
                    MessageBubble("Why am I dragging today?", sand, ink, true)
                    MessageBubble("Sleep was 54 minutes shorter, and yesterday’s workout ran late. Want a lighter plan tonight?", cream, ink)
                }
                1 -> {
                    listOf("You" to "5/5 days   +31%", "Maya" to "4/5 days   +22 min", "Theo" to "3/5 days   +4%").forEach { (name, score) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(name, Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodySmall)
                            Text(score, color = foreground, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    MessageBubble("Theo, bold words for someone who logged 11 minutes yesterday.", cream, ink)
                }
                2 -> {
                    MessageBubble("That afternoon crash is back.", sage, cream, true)
                    MessageBubble("Same timing as last time. Earlier lunches softened the dip.", cream, ink)
                }
                3 -> {
                    MessageBubble("Two of three workouts done. Move the last one to tomorrow?", cream, ink)
                    MessageBubble("Yes, 9 AM.", sand, ink, true)
                    Text("✓  Moved to tomorrow at 9:00 AM", color = foreground, style = MaterialTheme.typography.bodySmall)
                }
                4 -> Column(Modifier.fillMaxWidth().background(cream, RoundedCornerShape(20.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2,140 cal", color = ink, style = MaterialTheme.typography.headlineLarge)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        listOf("PROTEIN" to "138g", "CARBS" to "214g", "FAT" to "81g", "FIBER" to "28g").forEach { (label, value) ->
                            Column { Text(label, color = ink, fontSize = 7.sp); Text(value, color = ink, fontSize = 12.sp) }
                        }
                    }
                }
                else -> listOf("Booked" to "Dentist · Thu 10:15 AM", "Found nearby" to "DEXA scan · Thu 2:00 PM",
                    "In your cart" to "Omega-3 refill").forEach { (status, title) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(status, color = sand, style = MaterialTheme.typography.bodySmall)
                        Text(title, color = cream, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(text: String, background: Color, foreground: Color, trailing: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (trailing) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, color = foreground, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.fillMaxWidth(if (trailing) .9f else .94f).background(background, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
