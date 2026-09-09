package app.sterna.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ForeignScript
import app.sterna.core.data.filter.ForeignScriptNotice
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.foreignScriptNotice
import app.sterna.core.data.filter.showsNoRulesNote
import app.sterna.ui.components.LoadingRing

/** Server-side filter rules (JMAP Sieve) for the current account: edited as a form and pushed on
 *  Save. Network-backed, so the screen carries loading / saving / error state. */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    viewModel: FiltersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    // Confirm-on-back (#34); both doors go through it, the scaffold's arrow and the system gesture.
    // Held above the rule-editor branch below so the early return never straddles a remember.
    var confirmExit by remember { mutableStateOf(false) }
    // Saving from the dialog is a network round-trip (compile → validate → activate), so the
    // screen leaves only once the server has taken it; a refusal keeps the error in view.
    var leaveAfterSave by remember { mutableStateOf(false) }
    // The overwrite confirmation, and which gesture waits behind it: the exit dialog's Save must
    // still leave once the write lands, the button must still stay.
    var confirmOverwrite by remember { mutableStateOf(false) }
    var overwriteThenLeave by remember { mutableStateOf(false) }
    // The takeover confirmation, and the same passenger: which gesture is waiting behind it.
    var confirmTakeover by remember { mutableStateOf(false) }
    var takeoverThenLeave by remember { mutableStateOf(false) }

    val editIndex = editing
    if (editIndex != null && editIndex < state.rules.size) {
        // Full-screen editor (a dialog is too cramped for this many fields).
        RuleEditScreen(
            initial = state.rules[editIndex],
            folders = state.folders,
            onCommit = { viewModel.updateRule(editIndex, it); editing = null },
            onDelete = { viewModel.removeRule(editIndex); editing = null },
        )
        return
    }

    LaunchedEffect(leaveAfterSave, state.saving, state.dirty, state.errorKind) {
        if (!leaveAfterSave) return@LaunchedEffect
        when (pendingExitStep(state.saving, state.dirty, failed = state.errorKind != null)) {
            PendingExit.LEAVE -> { leaveAfterSave = false; onBack() }
            PendingExit.STAY -> leaveAfterSave = false
            PendingExit.WAIT -> Unit
        }
    }
    // One door for both gestures that write: the confirmation belongs to the write, not to the
    // button. Hung on the button alone it is walked around by the back gesture, which reaches the
    // same write through the exit dialog below. Which asked travels in `thenLeave`.
    fun requestSave(thenLeave: Boolean) {
        when (filtersSaveStep(scriptUnreadable = state.scriptUnreadable, foreignActive = state.foreignActive)) {
            FiltersSaveStep.CONFIRM_OVERWRITE -> { overwriteThenLeave = thenLeave; confirmOverwrite = true }
            FiltersSaveStep.CONFIRM_TAKEOVER -> { takeoverThenLeave = thenLeave; confirmTakeover = true }
            FiltersSaveStep.WRITE -> { leaveAfterSave = thenLeave; viewModel.save() }
        }
    }

    BackHandler(enabled = state.dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message_generic),
            canSave = !state.saving,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; requestSave(thenLeave = true) },
        )
    }
    if (confirmOverwrite) {
        // Same shape as the block-sender dialog: server-side, permanent, no Undo behind it.
        // Cancelling leaves both the screen and the exit alone, so the edits stay unwritten.
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text(stringResource(R.string.settings_filters_overwrite_title)) },
            text = {
                // The body must scroll: Material's text slot is a height-bounded box, so a bare
                // Text in it is cut, not ellipsised, with nothing saying more exists.
                Text(
                    stringResource(R.string.settings_filters_overwrite_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // A verb, not a sentence: a sentence wraps and Cancel is then drawn inside this
                // button, one tap landing on both, on a permanent server-side write.
                TextButton(onClick = { confirmOverwrite = false; leaveAfterSave = overwriteThenLeave; viewModel.save() }) {
                    Text(stringResource(R.string.settings_filters_overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    if (confirmTakeover) {
        // Same shape as the overwrite dialog, different loss: that one replaces content, this one
        // STOPS a script that is filtering mail right now. The script is named in every sentence,
        // because "another script" is what the owner already read as a routine notice (#209).
        val foreignName = state.foreignScript?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmTakeover = false },
            title = { Text(stringResource(R.string.settings_filters_takeover_title, foreignName)) },
            text = {
                Text(
                    stringResource(R.string.settings_filters_takeover_body, foreignName),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmTakeover = false; leaveAfterSave = takeoverThenLeave; viewModel.save() }) {
                    Text(stringResource(R.string.settings_filters_takeover_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTakeover = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    DetailScaffold(
        title = stringResource(R.string.settings_filters_screen_title),
        onBack = { if (state.dirty) confirmExit = true else onBack() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingRing(Modifier.align(Alignment.Center))
                state.noAccount -> FiltersNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> FiltersNote(stringResource(R.string.settings_filters_unsupported))
                state.errorKind == FiltersError.LOAD -> FiltersNote(
                    stringResource(R.string.settings_vacation_load_error, state.errorDetail),
                    onRetry = viewModel::load,
                )
                else -> FiltersList(
                    state = state,
                    viewModel = viewModel,
                    onEdit = { editing = it },
                    onAdd = { viewModel.addRule(); editing = state.rules.size },
                    onSave = { requestSave(thenLeave = false) },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.FiltersNote(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            // Filled Button to match every other error-recovery "Retry" (inbox, message load).
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.settings_vacation_retry))
            }
        }
    }
}

/**
 * The active script this app did not write, shown as text because it cannot be shown as rules.
 * Read-only on purpose: an editor here would be a Sieve editor, and a parser that filled the rule
 * list from it would drop every construct the rule model has no field for.
 */
@Composable
private fun ForeignScriptBody(foreign: ForeignScript) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_filters_foreign_body_title, foreign.name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (foreign.body == null) {
            // The list named it and the blob would not come down. Saying so is the whole point:
            // silence here is indistinguishable from an empty script, and an empty script is the
            // one case where a save costs nothing.
            Text(
                stringResource(R.string.settings_filters_foreign_body_unavailable, foreign.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // Monospace and horizontally scrollable: Sieve is indented code, and re-wrapping it
            // silently changes what the owner is being asked to judge.
            Text(
                foreign.body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun FiltersList(
    state: FiltersUiState,
    viewModel: FiltersViewModel,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    // NOT viewModel::save: the write goes through the screen's one door, which asks first when the
    // save would replace a script nobody could read.
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.accountLabel.isNotBlank()) {
            Text(
                stringResource(R.string.settings_vacation_account, state.accountLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Before the "another script is active" line and independent of it: the rules can be
        // stopped with no foreign script active at all.
        if (state.rulesNotRunning) {
            Text(
                stringResource(R.string.settings_filters_not_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // One line, not two, in priority order: an unreadable script of our own first, then
        // `vacation` being active, which names what the save costs.
        foreignScriptNotice(
            scriptUnreadable = state.scriptUnreadable,
            foreignActive = state.foreignActive,
            vacationScriptActive = state.vacationScriptActive,
        )?.let { notice ->
            Text(
                when (notice) {
                    ForeignScriptNotice.UNREADABLE_SCRIPT -> stringResource(R.string.settings_filters_unreadable)
                    ForeignScriptNotice.STOPS_AUTO_REPLY -> stringResource(R.string.settings_filters_stops_auto_reply)
                    // Named, not "another": the unnamed sentence read as routine, and the owner
                    // could not tell which of their scripts a save was about to switch off (#209).
                    ForeignScriptNotice.ANOTHER_SCRIPT ->
                        stringResource(R.string.settings_filters_foreign_warning, state.foreignScript?.name.orEmpty())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // The script itself, verbatim. This app models a SUBSET of Sieve, so it cannot turn a
        // script it did not write into rules without dropping whatever it failed to understand —
        // the same data loss as before, arriving quietly. Showing the text unparsed states both
        // facts honestly: none of this is represented as rules, and none of it was invented.
        state.foreignScript?.let { foreign -> ForeignScriptBody(foreign) }
        when {
            state.rules.isNotEmpty() -> {
                state.rules.forEachIndexed { index, rule ->
                    HorizontalDivider()
                    RuleRow(
                        rule = rule,
                        onToggle = { viewModel.setRuleEnabled(index, it) },
                        onEdit = { onEdit(index) },
                    )
                }
                HorizontalDivider()
            }
            // An empty list is not always "no rules". Over an unreadable script, and over a
            // foreign one quoted just above, the line already there says what the emptiness
            // means — and "No rules yet. Add one…" contradicts it while inviting the save that
            // makes it true (#209).
            showsNoRulesNote(
                ruleCount = state.rules.size,
                scriptUnreadable = state.scriptUnreadable,
                foreignActive = state.foreignActive,
            ) ->
                Text(
                    stringResource(R.string.settings_filters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
        }

        OutlinedButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_filters_add))
        }

        if (state.errorKind == FiltersError.SAVE) {
            Text(
                stringResource(R.string.settings_vacation_save_error, state.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Button(
            onClick = onSave,
            // Nothing to push until a rule differs from what the server holds (#34) — unless the
            // server is not running the rules it holds, and Save is the way to put them back.
            enabled = filtersSaveEnabled(
                saving = state.saving,
                dirty = state.dirty,
                rulesNotRunning = state.rulesNotRunning,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.settings_vacation_save))
            }
        }
        if (state.savedTick > 0) {
            Text(
                stringResource(R.string.settings_filters_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** One rule in the list: summary + enable switch; tap to edit. */
@Composable
private fun RuleRow(rule: FilterRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.name.ifBlank { stringResource(R.string.settings_filters_untitled) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                ruleSummary(context, rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

/** Full-screen rule editor. Back commits the edited rule to the in-memory list. */
@Composable
private fun RuleEditScreen(
    initial: FilterRule,
    folders: List<String>,
    onCommit: (FilterRule) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var rule by remember { mutableStateOf(initial) }
    BackHandler { onCommit(rule) }
    DetailScaffold(
        title = stringResource(R.string.settings_filters_edit_title),
        onBack = { onCommit(rule) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Keeps the rule form scrollable above the keyboard while typing (#52).
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = rule.name,
                onValueChange = { rule = rule.copy(name = it) },
                label = { Text(stringResource(R.string.settings_filter_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsSection(stringResource(R.string.settings_filter_if)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_filter_field),
                    options = RuleField.entries,
                    selected = rule.field,
                    optionLabel = { fieldLabel(context, it) },
                    onSelect = { rule = rule.copy(field = it) },
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_filter_match),
                    options = RuleMatch.entries,
                    selected = rule.match,
                    optionLabel = { matchLabel(context, it) },
                    onSelect = { rule = rule.copy(match = it) },
                )
                OutlinedTextField(
                    value = rule.value,
                    onValueChange = { rule = rule.copy(value = it) },
                    label = { Text(stringResource(R.string.settings_filter_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SettingsSection(stringResource(R.string.settings_filter_then)) {
                val noMove = stringResource(R.string.settings_filter_move_none)
                // A rule written before targets became whole paths holds a bare folder name, no
                // longer an offered option. Kept and kept selected: opening a rule must not
                // quietly retarget it.
                val stored = rule.moveTo?.takeIf { it !in folders }
                SettingChoiceRow(
                    title = stringResource(R.string.settings_filter_move_to),
                    options = listOf<String?>(null) + folders + listOfNotNull(stored),
                    selected = rule.moveTo,
                    optionLabel = { it ?: noMove },
                    onSelect = { rule = rule.copy(moveTo = it) },
                )
                ActionSwitch(
                    label = stringResource(R.string.settings_filter_mark_read),
                    checked = rule.markRead,
                    onChange = { rule = rule.copy(markRead = it) },
                )
                ActionSwitch(
                    label = stringResource(R.string.settings_filter_flag),
                    checked = rule.flag,
                    onChange = { rule = rule.copy(flag = it) },
                )
            }
            TextButton(onClick = onDelete, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_filter_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun ActionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "Sender contains \"X\" → Move to Y · Mark read" — a one-line human summary. */
private fun ruleSummary(context: Context, rule: FilterRule): String {
    val cond = "${fieldLabel(context, rule.field)} ${matchLabel(context, rule.match)} \"${rule.value}\""
    val actions = buildList {
        rule.moveTo?.let { add(context.getString(R.string.settings_filter_summary_move, it)) }
        if (rule.markRead) add(context.getString(R.string.settings_filter_mark_read))
        if (rule.flag) add(context.getString(R.string.settings_filter_flag))
    }
    return if (actions.isEmpty()) cond else "$cond → ${actions.joinToString(" · ")}"
}

private fun fieldLabel(context: Context, field: RuleField): String = context.getString(
    when (field) {
        RuleField.FROM -> R.string.settings_filter_field_from
        RuleField.TO -> R.string.settings_filter_field_to
        RuleField.CC -> R.string.settings_filter_field_cc
        RuleField.SUBJECT -> R.string.settings_filter_field_subject
    },
)

private fun matchLabel(context: Context, match: RuleMatch): String = context.getString(
    when (match) {
        RuleMatch.CONTAINS -> R.string.settings_filter_match_contains
        RuleMatch.IS -> R.string.settings_filter_match_is
    },
)
