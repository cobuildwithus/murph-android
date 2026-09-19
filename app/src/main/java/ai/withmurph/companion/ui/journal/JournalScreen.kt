package ai.withmurph.companion.ui.journal

import ai.withmurph.companion.core.*
import ai.withmurph.companion.ui.components.MurphPrimaryButton
import ai.withmurph.companion.ui.theme.MurphColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    state: JournalState,
    onRefresh: () -> Unit,
    onOpenMeals: () -> Unit,
    reserveStatusBarInset: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    var selectedDate by remember { mutableStateOf(today) }
    var visibleCount by remember { mutableIntStateOf(7) }
    var showCalendar by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<JournalEvent?>(null) }
    val response = (state as? JournalState.Ready)?.response
    val earliest = today.minusDays(((response?.journal?.windowDays ?: 120).coerceIn(1, 120) - 1).toLong())
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }
    LaunchedEffect(state is JournalState.Ready) {
        if (state !is JournalState.Ready) { selectedEvent = null; showCalendar = false }
    }
    LaunchedEffect(earliest) { selectedDate = selectedDate.coerceIn(earliest, today) }
    Column(Modifier.fillMaxSize().background(MurphColors.Cream)
        .then(if (reserveStatusBarInset) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showCalendar = true }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)) {
                JournalIcon("calendar", Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Text("⌄", fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { selectedDate = today; visibleCount = 7 }) { Text("Today") }
        }
        HorizontalDivider(color = MurphColors.BorderWarm)
        PullToRefreshBox(isRefreshing = state == JournalState.Loading, onRefresh = onRefresh) {
            key(selectedDate) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                    when (state) {
                        JournalState.Idle, JournalState.Loading -> item { JournalSkeleton() }
                        JournalState.Failed -> item {
                            JournalStatus("Journal couldn't load", "Try again when you're connected.", onRefresh)
                        }
                        is JournalState.Ready -> {
                            val journal = state.response.journal
                            when {
                                journal == null -> item {
                                    JournalStatus("Your journal isn't ready yet", "Check again after Murph has updated your records.", onRefresh)
                                }
                                journal.days.all { it.events.isEmpty() } -> item { JournalEmpty(onOpenMeals, onRefresh) }
                                else -> {
                                    val days = journal.days.associate { it.date to it.events }
                                    if (response?.freshness == "stale") item {
                                        Text("Showing saved records. Pull down to check for updates.",
                                            Modifier.padding(top = 20.dp), color = MurphColors.SlateMuted,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    item { WeekSummary(selectedDate, earliest, days) }
                                    val dates = journalDates(selectedDate, visibleCount, earliest)
                                    dates.forEach { date ->
                                        item("heading-$date") { DayHeading(date, today) }
                                        val events = days[date].orEmpty()
                                        if (events.isEmpty()) item("empty-$date") {
                                            Text("No entries this day", Modifier.padding(bottom = 24.dp),
                                                color = MurphColors.SlateMuted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        items(events) { event -> EventRow(event) { selectedEvent = event } }
                                        item("space-$date") { Spacer(Modifier.height(16.dp)) }
                                    }
                                    item {
                                        if (dates.lastOrNull()?.isAfter(earliest) == true) {
                                            TextButton(onClick = { visibleCount = (visibleCount + 7).coerceAtMost(120) },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Earlier days ↓") }
                                        } else {
                                            Text("You've reached the end of the available journal.",
                                                Modifier.padding(vertical = 24.dp), color = MurphColors.SlateMuted,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCalendar) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = earliest.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return date in earliest..today
                }
            },
        )
        DatePickerDialog(onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        selectedDate = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        visibleCount = 7
                    }
                    showCalendar = false
                }, enabled = dateState.selectedDateMillis != null) { Text("Show this day") }
            }, dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } }) {
            DatePicker(state = dateState, title = { Text("Choose a date", Modifier.padding(24.dp)) })
        }
    }
    selectedEvent?.takeIf { state is JournalState.Ready }?.let { event ->
        ModalBottomSheet(onDismissRequest = { selectedEvent = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MurphColors.Cream) {
            EventDetails(event)
        }
    }
}

@Composable
private fun JournalEmpty(onOpenMeals: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.padding(top = 36.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.size(84.dp).background(MurphColors.Sage.copy(alpha = .12f), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center) { JournalIcon("book", Modifier.size(40.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your journal\nstarts here.", style = MaterialTheme.typography.displayLarge.copy(fontSize = 34.sp, lineHeight = 40.sp),
                color = MurphColors.Slate, modifier = Modifier.semantics { heading() })
            Text("Your sleep, meals, movement, and notes will come together here, one day at a time.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp), color = MurphColors.SlateMuted)
        }
        Column(Modifier.fillMaxWidth().background(MurphColors.Card, RoundedCornerShape(22.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("START WITH SOMETHING SMALL", style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
            listOf("meal" to "Send Murph a meal photo", "chat" to "Tell Murph how you're feeling",
                "heart" to "Let connected health data fill in the rest").forEach { (kind, title) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JournalIcon(kind, Modifier.size(20.dp))
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = MurphColors.Slate)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MurphPrimaryButton("Open Meals", onClick = onOpenMeals)
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("Check for updates") }
        }
    }
}

@Composable
private fun JournalStatus(title: String, detail: String, onRefresh: () -> Unit) {
    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MurphColors.Slate)
        Text(detail, style = MaterialTheme.typography.bodyLarge, color = MurphColors.SlateMuted)
        TextButton(onClick = onRefresh) { Text("Try again") }
    }
}

@Composable
private fun WeekSummary(date: LocalDate, earliest: LocalDate, days: Map<LocalDate, List<JournalEvent>>) {
    val events = journalDates(date, 7, earliest).flatMap { days[it].orEmpty() }
    val sleep = events.filter { it.timing == "night" }.mapNotNull { it.metrics["sleepMinutes"] }
    val activity = events.sumOf { it.metrics["activityMinutes"] ?: 0.0 }
    val values = buildList {
        if (sleep.isNotEmpty()) add(JournalPresentation.duration(sleep.average()) to "Average sleep")
        if (activity > 0) add(JournalPresentation.duration(activity) to "Activity")
    }
    Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("7 DAYS THROUGH ${date.format(DateTimeFormatter.ofPattern("MMM d")).uppercase()}",
            style = MaterialTheme.typography.labelMedium, color = MurphColors.SlateMuted)
        if (LocalDensity.current.fontScale > 1.4f) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { values.forEach { (value, label) -> SummaryValue(value, label) } }
        } else Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            values.forEach { (value, label) -> Box(Modifier.weight(1f)) { SummaryValue(value, label) } }
        }
    }
}

@Composable
private fun SummaryValue(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineLarge, color = MurphColors.Slate)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MurphColors.SlateMuted)
    }
}

@Composable
private fun DayHeading(date: LocalDate, today: LocalDate) {
    HorizontalDivider(color = MurphColors.BorderWarm)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (date == today) "Today" else date.format(DateTimeFormatter.ofPattern("EEEE")),
            Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 23.sp), color = MurphColors.Slate)
        Text(date.format(DateTimeFormatter.ofPattern("MMM d")), style = MaterialTheme.typography.bodySmall, color = MurphColors.SlateMuted)
    }
}

@Composable
private fun EventRow(event: JournalEvent, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open entry details", onClick = onOpen)
        .padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        JournalIcon(event.kind, Modifier.padding(top = 2.dp).size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(JournalPresentation.text(event.title), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), color = MurphColors.Slate)
            if (event.timeLabel.isNotEmpty()) Text(event.timeLabel, style = MaterialTheme.typography.bodySmall, color = MurphColors.SlateMuted)
            JournalPresentation.summary(event.summary)?.takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MurphColors.SlateMuted)
            }
        }
        Text("›", color = MurphColors.SlateMuted, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun EventDetails(event: JournalEvent) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JournalIcon(event.kind, Modifier.size(26.dp))
            Text(JournalPresentation.text(event.title), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp), color = MurphColors.Slate,
                modifier = Modifier.semantics { heading() })
            Text(listOfNotNull(event.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                event.timeLabel.takeIf(String::isNotEmpty), event.timeZone).joinToString(" · "), color = MurphColors.SlateMuted,
                style = MaterialTheme.typography.bodySmall)
            JournalPresentation.summary(event.summary)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MurphColors.Slate) }
        }
        Column {
            JournalPresentation.metrics(event.metrics).forEach { (label, value) ->
                if (LocalDensity.current.fontScale > 1.4f) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp).semantics(mergeDescendants = true) {}) {
                        Text(label, color = MurphColors.Slate, style = MaterialTheme.typography.bodyLarge)
                        Text(value, color = MurphColors.Slate, style = MaterialTheme.typography.headlineMedium)
                    }
                } else Row(Modifier.fillMaxWidth().padding(vertical = 13.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, Modifier.weight(1f), color = MurphColors.Slate, style = MaterialTheme.typography.bodyLarge)
                    Text(value, color = MurphColors.Slate, style = MaterialTheme.typography.headlineMedium)
                }
                HorizontalDivider(color = MurphColors.BorderWarm)
            }
        }
        event.details.forEach { Text(JournalPresentation.text(it), color = MurphColors.Slate, style = MaterialTheme.typography.bodyLarge) }
        if (event.records.isNotEmpty()) {
            Text("RECORDS", color = MurphColors.SlateMuted, style = MaterialTheme.typography.labelMedium)
            event.records.forEach { record ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(JournalPresentation.text(record.label), color = MurphColors.Slate, style = MaterialTheme.typography.bodyLarge)
                    JournalPresentation.summary(record.summary)?.takeIf { it != JournalPresentation.summary(event.summary) }?.let {
                        Text(it, color = MurphColors.SlateMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(listOfNotNull(record.source?.let(JournalPresentation::text),
                        if (event.timing == "timed") JournalPresentation.time(record.occurredAt, record.timeZone) else null).joinToString(" · "),
                        color = MurphColors.SlateMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun JournalSkeleton() {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp).clearAndSetSemantics { contentDescription = "Loading your journal" },
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(2) {
            Box(Modifier.fillMaxWidth(.6f).height(22.dp).background(MurphColors.MutedSurface, RoundedCornerShape(5.dp)))
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(24.dp).background(MurphColors.MutedSurface, RoundedCornerShape(12.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.fillMaxWidth(.55f).height(20.dp).background(MurphColors.MutedSurface, RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxWidth(.8f).height(14.dp).background(MurphColors.MutedSurface, RoundedCornerShape(5.dp)))
                    }
                }
            }
        }
    }
}

@Composable
fun JournalIcon(kind: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MurphColors.SageDark) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val u = size.minDimension
        val stroke = Stroke(u * .065f, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
        when (kind) {
            "sleep" -> {
                val moon = Path().apply {
                    moveTo(u * .45f, u * .08f)
                    cubicTo(-u * .08f, u * .35f, u * .12f, u, u * .65f, u * .86f)
                    quadraticTo(u * .82f, u * .8f, u * .9f, u * .64f)
                    cubicTo(u * .4f, u * .78f, u * .27f, u * .35f, u * .45f, u * .08f)
                }
                drawPath(moon, color, style = stroke)
            }
            "meal" -> {
                line(.25f,.1f,.25f,.9f); line(.12f,.1f,.12f,.36f); line(.38f,.1f,.38f,.36f)
                line(.12f,.36f,.38f,.36f); line(.76f,.1f,.76f,.9f)
                line(.62f,.1f,.62f,.53f); line(.62f,.53f,.76f,.53f)
            }
            "activity", "workout" -> {
                drawCircle(color, u * .085f, Offset(.57f*u,.12f*u))
                line(.52f,.3f,.42f,.58f); line(.48f,.35f,.25f,.45f); line(.52f,.32f,.75f,.5f)
                line(.42f,.58f,.23f,.9f); line(.42f,.58f,.66f,.71f); line(.66f,.71f,.73f,.91f)
            }
            "heart" -> {
                val heart = Path().apply {
                    moveTo(.5f*u,.87f*u)
                    cubicTo(-.2f*u,.4f*u,.25f*u,-.1f*u,.5f*u,.25f*u)
                    cubicTo(.75f*u,-.1f*u,1.2f*u,.4f*u,.5f*u,.87f*u)
                }
                drawPath(heart,color,style=stroke)
            }
            "observation", "symptom" -> {
                line(.08f,.5f,.28f,.5f); line(.28f,.5f,.42f,.15f); line(.42f,.15f,.58f,.85f)
                line(.58f,.85f,.73f,.5f); line(.73f,.5f,.93f,.5f)
            }
            "chat" -> {
                val bubble = Path().apply {
                    moveTo(.15f*u,.15f*u); lineTo(.85f*u,.15f*u); lineTo(.85f*u,.7f*u)
                    lineTo(.42f*u,.7f*u); lineTo(.2f*u,.9f*u); lineTo(.2f*u,.7f*u); close()
                }
                drawPath(bubble,color,style=stroke)
                line(.3f,.32f,.7f,.32f); line(.3f,.5f,.6f,.5f)
            }
            "calendar" -> {
                drawRoundRect(color, Offset(.12f*u,.18f*u), Size(.76f*u,.7f*u), CornerRadius(.08f*u), style=stroke)
                line(.12f,.38f,.88f,.38f); line(.3f,.1f,.3f,.25f); line(.7f,.1f,.7f,.25f)
                for (x in 0..2) for (y in 0..1) drawCircle(color,.025f*u,Offset((.32f+.18f*x)*u,(.55f+.18f*y)*u))
            }
            else -> {
                drawRoundRect(color, Offset(.18f*u,.1f*u), Size(.65f*u,.78f*u), CornerRadius(.08f*u), style=stroke)
                line(.3f,.31f,.7f,.31f); line(.3f,.49f,.7f,.49f); line(.3f,.67f,.55f,.67f)
            }
        }
    }
}
