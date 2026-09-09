package app.sterna.ui.search

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.rememberMotionEnabled
import kotlinx.coroutines.launch
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.components.EmailListItem
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.accountColorOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenEmail: (id: String, accountId: String?) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val query = form.query
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun runSearch() {
        // Fold the keyboard away with the panel: it used to stay up over the results.
        focusManager.clearFocus()
        keyboard?.hide()
        viewModel.search()
    }

    // The advanced filters slide over the results like the main navigation drawer: a slim handle
    // under the search box drives a top overlay, and the results never reflow. form.expanded stays
    // the single source of truth, so a drag only sets it and the snap animation follows.
    //
    // All of this lives ABOVE the Scaffold rather than inside its content, because the title bar is
    // one of the drag points and Scaffold's topBar cannot see the content's locals.
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberMotionEnabled()
    val handleDesc = stringResource(R.string.search_advanced_toggle)
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var firstSync by remember { mutableStateOf(true) }
    // The handle's height: reserved at the top of the results so a closed handle sits clear of the
    // first row, and reused as the handle's own size so it reads as a solid edge.
    val handleHeight = 18.dp

    // How far the panel is pulled out, 0 (shut) to panelHeightPx. A PLAIN float state, written
    // synchronously, and the only reader of the panel's position on this screen.
    //
    // Not an Animatable, which can only be moved from a coroutine: a nested scroll must answer
    // "how much did you take?" the instant it is asked, and two touch events drained in one pass of
    var offsetPx by remember { mutableFloatStateOf(0f) }
    // Kept only to DRIVE the settle animation, not to hold the position. Its mutex is the point:
    // two settles racing cancel each other instead of both writing.
    val animation = remember { Animatable(0f) }

    suspend fun glideTo(target: Float) {
        animation.snapTo(offsetPx) // start from wherever the finger actually left it
        animation.animateTo(target) { offsetPx = value }
    }

    // Follow form.expanded. The first pass snaps, so entering the screen already-open plays no
    // intro slide.
    LaunchedEffect(form.expanded, panelHeightPx) {
        if (panelHeightPx == 0) return@LaunchedEffect
        val target = if (form.expanded) panelHeightPx.toFloat() else 0f
        when {
            firstSync -> { offsetPx = target; firstSync = false }
            offsetPx == target -> Unit
            motionEnabled -> glideTo(target)
            else -> { offsetPx = target }
        }
    }

    // The drag's end-state: animate the snap here, so it plays even when the panel was already on
    // that side, then record it in the ViewModel.
    fun settle(open: Boolean) {
        scope.launch {
            val target = if (open) panelHeightPx.toFloat() else 0f
            if (motionEnabled) glideTo(target) else { offsetPx = target }
        }
        viewModel.setExpanded(open)
    }

    // Move the panel by [delta] px and report how much was actually used — a nested scroll has to
    // hand back exactly what it took. Read and write are in the same synchronous call, so the panel
    // moves by precisely the number returned.
    fun nudge(delta: Float): Float {
        if (panelHeightPx == 0) return 0f
        val used = searchPanelTakes(offsetPx, panelHeightPx.toFloat(), delta)
        offsetPx += used
        return used
    }

    // One drag behaviour at every grab point: the handle, the title bar and the free-text field.
    // Same state, and above all the same release decision — see [searchPanelSettlesOpen].
    val dragState = rememberDraggableState { delta -> nudge(delta) }
    val panelDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = dragState,
        onDragStopped = { velocity ->
            settle(searchPanelSettlesOpen(offsetPx, panelHeightPx.toFloat(), velocity))
        },
    )

    // Folding by dragging up from inside the panel is NOT a `draggable`: the panel scrolls, and a
    // drag modifier over it would swallow that scroll and put the Search button out of reach in
    // exactly the cramped cases the scroll exists for. Nested scroll instead, so the panel's own
    // scroll is served first and the fold gets only what it could not consume.
    //
    // Unkeyed remember: everything it reads is either a State or fixed for the composition's life.
    val panelNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Coming back DOWN after a partial fold: pull the panel out again BEFORE its
                // content scrolls, or a reversed gesture leaves it stuck half-way.
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (offsetPx >= panelHeightPx) return Offset.Zero
                return Offset(0f, nudge(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Going UP: this only runs on what the panel's scroll could not use.
                if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                return Offset(0f, nudge(available.y))
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Release. Settle only if this gesture actually moved the panel: when it merely
                // scrolled the panel's content, the fling belongs to that content and taking it
                val committed = if (form.expanded) panelHeightPx.toFloat() else 0f
                if (panelHeightPx == 0 || offsetPx == committed) return Velocity.Zero
                settle(searchPanelSettlesOpen(offsetPx, panelHeightPx.toFloat(), available.y))
                return available
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Dragging down from the title bar opens the panel: an 18 dp handle was not a
                // usable target. The icon buttons keep their taps, a tap being decided before the
                // drag slop is crossed.
                modifier = panelDrag,
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.message_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::togglePanel) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.search_advanced_toggle),
                            tint = if (form.expanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // imePadding: the screen gives the keyboard the room it takes instead of being drawn under it.
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Wrapped rather than modified directly so the strip AROUND the field drags too: the
            // whole band between the title bar and the handle is one grab area.
            Box(Modifier.fillMaxWidth().then(panelDrag)) {
                OutlinedTextField(
                    value = query.text,
                    onValueChange = { viewModel.updateQuery(query.copy(text = it)) },
                    label = { Text(stringResource(R.string.search_field_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // clipToBounds hides the panel where it rests above the handle; weight(1f) is the
            // results' space AND the overlay's stage, so opening the panel never resizes the list.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                // The results, at full size UNDER the overlay, with a top inset the height of the
                // handle.
                Column(Modifier.fillMaxSize().padding(top = handleHeight)) {
                    if (!form.expanded) {
                        // Folded: the criteria the RESULTS came from, never the half-edited form.
                        val applied = (state as? SearchState.Results)?.query ?: query
                        CriteriaSummary(applied, onClick = viewModel::togglePanel)
                    }
                    (state as? SearchState.Results)?.takeIf { it.emails.isNotEmpty() }?.let { results ->
                        Text(
                            // "At least N" whenever the search stopped short of the whole answer: a
                            // truncated scan counted as a total would be a number the user cannot
                            when (searchCount(results.complete, loading = false)) {
                                SearchCount.EXACT ->
                                    pluralStringResource(R.plurals.search_result_count, results.emails.size, results.emails.size)
                                SearchCount.AT_LEAST ->
                                    pluralStringResource(R.plurals.search_result_count_capped, results.emails.size, results.emails.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (val s = state) {
                            is SearchState.Idle -> Text(
                                stringResource(R.string.search_idle_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            is SearchState.Searching -> LoadingRing(Modifier.align(Alignment.Center))
                            is SearchState.Error -> Text(
                                stringResource(R.string.search_failed, s.message),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                            // A truncated scan that returned nothing has NOT proven there is
                            // nothing: say it stopped short rather than reporting "no results" as a
                            // fact. Routed through the shared decision so the inbox's bar cannot
                            // word the same situation differently.
                            is SearchState.Results -> when (
                                searchDisplay(s.emails.size, loading = false, complete = s.complete)
                            ) {
                                SearchDisplay.INCOMPLETE_EMPTY -> EmptyState(
                                    art = EmptyArt.SEARCH,
                                    title = stringResource(R.string.search_incomplete),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                                SearchDisplay.EMPTY -> {
                                    val term = s.query.text.trim()
                                    EmptyState(
                                        art = EmptyArt.SEARCH,
                                        // This screen knows the words searched for, so it can name
                                        // them; the inbox's bar shows a generic line.
                                        title = if (term.isBlank()) stringResource(R.string.search_no_results_generic)
                                        else stringResource(R.string.search_no_results, term),
                                        body = stringResource(R.string.empty_search_body),
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                                // Unreachable here — spelled out rather than left to an else, so
                                // adding a case to the enum has to be answered here too.
                                SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))
                                SearchDisplay.RESULTS -> LazyColumn(Modifier.fillMaxSize()) {
                                    items(s.emails, key = ::searchResultKey) { email ->
                                    // Which account a hit belongs to matters here most: the search
                                    // spans them all. Same pill as the unified list.
                                        val owner = if (accounts.size > 1) {
                                            accounts.firstOrNull { it.id == email.accountId }
                                        } else {
                                            null
                                        }
                                        EmailListItem(
                                            email = email,
                                            onClick = { onOpenEmail(email.id, email.accountId) },
                                            originLabel = owner?.label(),
                                            originColor = accountColorOf(owner?.color),
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }

                // Scrim: like the drawer's, dims the results and catches a tap to close. Absent,
                // and non-blocking, while fully closed.
                val progress = if (panelHeightPx > 0) offsetPx / panelHeightPx else 0f
                if (progress > 0f) {
                    val scrimSource = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
                            .clickable(interactionSource = scrimSource, indication = null) { settle(false) },
                    )
                }

                // The panel itself, floating over the results: translated up by its own measured
                // height when closed and clipped away by the parent Box.
                Surface(
                    // No tonalElevation. Material 3 implements it by tinting the surface with
                    // the primary colour, and in this theme `background` and `surface` are the same
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .onSizeChanged { panelHeightPx = it.height }
                        .graphicsLayer {
                            translationY = if (panelHeightPx == 0) -100_000f else offsetPx - panelHeightPx
                        },
                ) {
                    // Scrollable, because the panel is a fixed stack of rows in a space that is
                    // not: landscape, a large font scale, or the keyboard opening from the Subject
                    // field each cut the height it gets, and without this the flagged switch and
                    // the Search button are clipped away with no gesture to bring them back.
                    //
                    // nestedScroll sits OUTSIDE verticalScroll on purpose: that order makes the
                    // scroll the first served and the fold the second.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .nestedScroll(panelNestedScroll)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        OutlinedTextField(
                            value = query.from,
                            onValueChange = { viewModel.updateQuery(query.copy(from = it)) },
                            label = { Text(stringResource(R.string.search_from)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        // Matches To OR Cc: a message that only carries the address in copy was
                        // still received at it, so one field and no To/Cc switch.
                        OutlinedTextField(
                            value = query.recipient,
                            onValueChange = { viewModel.updateQuery(query.copy(recipient = it)) },
                            label = { Text(stringResource(R.string.search_recipient)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        OutlinedTextField(
                            value = query.subject,
                            onValueChange = { viewModel.updateQuery(query.copy(subject = it)) },
                            label = { Text(stringResource(R.string.search_subject)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.search_has_attachment),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = query.hasAttachment,
                                onCheckedChange = { viewModel.updateQuery(query.copy(hasAttachment = it)) },
                            )
                        }
                        // Gathering flagged mail lives here rather than in the drawer: a drawer
                        // entry could only list what the cache holds, so it would promise "your
                        // flagged mail" while hiding everything in a never-synced folder.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.search_flagged),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = query.flagged,
                                onCheckedChange = { viewModel.updateQuery(query.copy(flagged = it)) },
                            )
                        }
                        SearchDateRow(stringResource(R.string.search_after), query.afterMillis) { picked ->
                            viewModel.updateQuery(query.copy(afterMillis = picked?.let(::searchAfterBound)))
                        }
                        SearchDateRow(stringResource(R.string.search_before), query.beforeMillis) { picked ->
                            viewModel.updateQuery(query.copy(beforeMillis = picked?.let(::searchBeforeBound)))
                        }
                        Button(
                            onClick = { runSearch() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.search_button))
                        }
                    }
                }

                // The separator / drag handle: the drawer's moving edge, riding at the panel's
                // bottom — the line under the search box when closed, then travelling DOWN with the
                // panel. Drawn last so it stays on top of the panel's bottom edge, and a tap toggle
                // as well as a drag, for reach.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .graphicsLayer { translationY = offsetPx }
                        .height(handleHeight)
                        .background(MaterialTheme.colorScheme.surface)
                        .then(panelDrag)
                        // The tap stays, and so does the contentDescription: a gesture is no handle
                        // for TalkBack, which has only this and the Tune button.
                        .clickable { viewModel.togglePanel() }
                        .semantics { contentDescription = handleDesc },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}

/**
 * The folded panel's one line: "From: alex · Has attachment · After: 3 Jun 2026". Text rather than
 */
@Composable
private fun CriteriaSummary(query: SearchQuery, onClick: () -> Unit) {
    val filters = searchSummary(query)
    if (filters.isEmpty()) return
    // A plain loop, not joinToString: the pieces are string resources, and a composable call
    // cannot go inside a non-inline lambda.
    val parts = ArrayList<String>(filters.size)
    for (filter in filters) {
        parts += when (filter.criterion) {
            SearchCriterion.FROM -> criterionPair(R.string.search_from, filter.text)
            SearchCriterion.RECIPIENT -> criterionPair(R.string.search_recipient, filter.text)
            SearchCriterion.SUBJECT -> criterionPair(R.string.search_subject, filter.text)
            SearchCriterion.ATTACHMENT -> stringResource(R.string.search_has_attachment)
            SearchCriterion.FLAGGED -> stringResource(R.string.search_flagged)
            // The date labels are already worded as prepositions, so they take the value without a
            // colon — hence a second join pattern.
            SearchCriterion.AFTER -> stringResource(
                R.string.search_criteria_pair_date,
                stringResource(R.string.search_after),
                formatSearchDate(searchBoundDay(filter.millis!!)),
            )
            SearchCriterion.BEFORE -> stringResource(
                R.string.search_criteria_pair_date,
                stringResource(R.string.search_before),
                formatSearchDate(searchBoundDay(filter.millis!!)),
            )
        }
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun criterionPair(@StringRes label: Int, value: String): String =
    stringResource(R.string.search_criteria_pair, stringResource(label), value)

/** A tappable row that shows the chosen date (or "Any") and opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDateRow(label: String, boundMillis: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                boundMillis?.let { formatSearchDate(searchBoundDay(it)) }
                    ?: stringResource(R.string.search_date_any),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundMillis != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_vacation_clear_date))
            }
        }
    }
    if (showPicker) {
        // The picker speaks UTC midnight, the query holds a local-day bound: hand it back the day.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = boundMillis?.let(::searchPickerMillis),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun formatSearchDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
