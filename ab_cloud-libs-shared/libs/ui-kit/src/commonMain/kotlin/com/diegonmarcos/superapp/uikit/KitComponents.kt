package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * THE density of a kit page (cloud-account-ui spec 0.4 and section 2): three spacing steps and
 * one type ramp, lifted from the Store's StoreDensity (#869) so the Store can switch to it later.
 * Cards breathe ([large] gutters, [title]/[head]); list rows stay dense ([medium]/[small],
 * [body]/[caption]). A kit component holds no other dp or sp literal of its own.
 */
object KitDensity {
    /** StoreDensity.TEXT_SCALE: the Store's type ramp is its base sizes times this. */
    const val TEXT_SCALE: Float = 0.78f

    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    /** Hairline rules and the stepper rail. */
    val rule: Dp = 1.dp
    /** A state dot. */
    val dot: Dp = 8.dp
    /** The device glyph box and a list row's leading glyph. */
    val glyph: Dp = 28.dp
    /** The hero avatar. */
    val avatar: Dp = 56.dp
    /** Corner radius of a card, a tile and a chip. */
    val corner: Dp = 12.dp

    // Type ramp: StoreDensity's T_CAPTION / T_BODY / T_TITLE / T_HEAD, same base sizes and scale.
    val caption: TextUnit = (11f * TEXT_SCALE).sp
    val body: TextUnit = (13f * TEXT_SCALE).sp
    val title: TextUnit = (14f * TEXT_SCALE).sp
    val head: TextUnit = (18f * TEXT_SCALE).sp
    /** The stat tile's number and the hero's name. */
    val display: TextUnit = (26f * TEXT_SCALE).sp
    val eyebrowTracking: TextUnit = 0.08.em
}

/** A state, shown as a shape before it is a word (spec 0.5). */
enum class KitState { OK, WARN, BAD, IDLE, BUSY }

/** The palette token a [KitState] paints its dot with: ok / warn / bad, else the secondary ink. */
@Composable
fun kitStateColor(state: KitState): Color {
    val p = LocalKitPalette.current
    return when (state) {
        KitState.OK -> p.ok
        KitState.WARN -> p.warn
        KitState.BAD -> p.bad
        KitState.BUSY -> p.accent
        KitState.IDLE -> p.textSecondary
    }
}

/** One button of a [KitActionBar], a [KitStatusBanner] or a row. */
data class KitAction(val label: String, val tag: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** Test tags of the components added for the Account visual pass. */
object KitPartTags {
    fun pill(id: String) = "kit:pill:$id"
    fun hero(id: String) = "kit:hero:$id"
    fun device(id: String) = "kit:device:$id"
    fun deviceMenu(id: String) = "kit:device:menu:$id"
    fun stat(id: String) = "kit:stat:$id"
    fun banner(id: String) = "kit:banner:$id"
    fun fingerprint(id: String) = "kit:fingerprint:$id"
    fun segment(id: String, option: String) = "kit:segment:$id:$option"
    fun step(id: String) = "kit:step:$id"
    fun listRow(id: String) = "kit:listrow:$id"
    const val ACTION_BAR = "kit:actionbar"
    const val SAVE_BAR = "kit:savebar"
    const val SAVE = "kit:savebar:save"
    const val DISCARD = "kit:savebar:discard"
}

/** Dot + short word: the trailing slot of every row that has a state. */
@Composable
fun KitStatePill(word: String, state: KitState, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val c = kitStateColor(state)
    Row(
        modifier
            .testTag(KitPartTags.pill(word))
            .clip(RoundedCornerShape(KitDensity.corner))
            .background(p.surfaceSelected)
            .padding(horizontal = KitDensity.small, vertical = KitDensity.small / 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(KitDensity.dot).clip(CircleShape).background(c))
        Text(word, color = p.textPrimary, fontSize = KitDensity.caption, maxLines = 1,
            modifier = Modifier.padding(start = KitDensity.small / 2))
    }
}

/**
 * The person at the top of an account page: avatar (the [avatar] slot, else [initials] on the
 * accent), name on the display scale, then each of [lines] in the secondary ink.
 */
@Composable
fun KitHero(
    name: String,
    lines: List<String>,
    initials: String,
    modifier: Modifier = Modifier,
    tag: String = "account",
    onClick: (() -> Unit)? = null,
    avatar: (@Composable () -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().testTag(KitPartTags.hero(tag))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = KitDensity.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(KitDensity.avatar).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            if (avatar != null) avatar()
            else Text(initials.take(2).uppercase(), color = p.tileInk, fontSize = KitDensity.head, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = KitDensity.large)) {
            Text(name, color = p.textPrimary, fontSize = KitDensity.display, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            for (l in lines) if (l.isNotBlank()) Text(l, color = p.textSecondary, fontSize = KitDensity.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One device: glyph, id on the title scale, [model] beside it, one [state] line, an optional
 * [badge] ("this phone", "DEFAULT"), an optional [primary] button and an overflow (⋮) of [menu].
 * Tapping the card calls [onClick] (the profile page opens the picker).
 */
@Composable
fun KitDeviceCard(
    id: String,
    model: String,
    state: String,
    modifier: Modifier = Modifier,
    glyph: String = "▣",
    badge: String = "",
    pill: Pair<String, KitState>? = null,
    primary: KitAction? = null,
    secondary: List<KitAction> = emptyList(),
    menu: List<KitAction> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().testTag(KitPartTags.device(id))
            .clip(RoundedCornerShape(KitDensity.corner)).background(p.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(KitDensity.large),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KitDensity.glyph).clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected),
                contentAlignment = Alignment.Center) { Text(glyph, color = p.accent, fontSize = KitDensity.title) }
            Column(Modifier.weight(1f).padding(start = KitDensity.medium)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id, color = p.textPrimary, fontSize = KitDensity.head, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badge.isNotBlank()) Text(badge, color = p.accent, fontSize = KitDensity.caption,
                        modifier = Modifier.padding(start = KitDensity.small))
                }
                if (model.isNotBlank()) Text(model, color = p.textSecondary, fontSize = KitDensity.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (pill != null) KitStatePill(pill.first, pill.second)
            if (menu.isNotEmpty()) Box {
                TextButton(onClick = { open = true }, modifier = Modifier.testTag(KitPartTags.deviceMenu(id))) {
                    Text("⋮", color = p.textPrimary, fontSize = KitDensity.head)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (a in menu) DropdownMenuItem(
                        text = { Text(a.label) }, enabled = a.enabled, modifier = Modifier.testTag(a.tag),
                        onClick = { open = false; a.onClick() },
                    )
                }
            }
        }
        if (state.isNotBlank()) Text(state, color = p.textSecondary, fontSize = KitDensity.body)
        if (primary != null || secondary.isNotEmpty()) {
            KitActionBar(listOfNotNull(primary) + secondary, filledFirst = primary != null)
        }
    }
}

/** A big number over a caption, its [state] as a dot; one of the 2×2 grid on the profile page. */
@Composable
fun KitStatTile(
    value: String,
    caption: String,
    state: KitState,
    modifier: Modifier = Modifier,
    tag: String = caption,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Column(
        modifier.testTag(KitPartTags.stat(tag))
            .clip(RoundedCornerShape(KitDensity.corner)).background(p.surface)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(KitDensity.large),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = p.textPrimary, fontSize = KitDensity.display, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(KitDensity.dot).clip(CircleShape).background(kitStateColor(state)))
        }
        Text(caption, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of actions: the first filled (the primary), the rest outlined; wraps on a narrow pane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KitActionBar(actions: List<KitAction>, modifier: Modifier = Modifier, filledFirst: Boolean = true) {
    FlowRow(
        modifier.fillMaxWidth().testTag(KitPartTags.ACTION_BAR),
        horizontalArrangement = Arrangement.spacedBy(KitDensity.small),
        verticalArrangement = Arrangement.spacedBy(KitDensity.small),
    ) {
        actions.forEachIndexed { i, a ->
            if (i == 0 && filledFirst) Button(onClick = a.onClick, enabled = a.enabled, modifier = Modifier.testTag(a.tag)) {
                Text(a.label, fontSize = KitDensity.title)
            } else OutlinedButton(onClick = a.onClick, enabled = a.enabled, modifier = Modifier.testTag(a.tag)) {
                Text(a.label, fontSize = KitDensity.title)
            }
        }
    }
}

/** One sentence about the page's state, its dot coloured by [state], with the one [action] that fixes it. */
@Composable
fun KitStatusBanner(text: String, state: KitState, modifier: Modifier = Modifier, tag: String = "status", action: KitAction? = null) {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().testTag(KitPartTags.banner(tag))
            .clip(RoundedCornerShape(KitDensity.corner)).background(p.surface)
            .border(KitDensity.rule, kitStateColor(state), RoundedCornerShape(KitDensity.corner))
            .padding(horizontal = KitDensity.large, vertical = KitDensity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(KitDensity.dot).clip(CircleShape).background(kitStateColor(state)))
        Text(text, color = p.textPrimary, fontSize = KitDensity.body, modifier = Modifier.weight(1f).padding(start = KitDensity.medium))
        if (action != null) TextButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.testTag(action.tag)) {
            Text(action.label, color = p.accent, fontSize = KitDensity.title)
        }
    }
}

/**
 * A secret, as a chip: ◆ and a short fingerprint (or "hidden" and its length), never the value,
 * and nothing to copy. Every secret-class render goes through here (spec 0.6).
 */
@Composable
fun KitFingerprint(fingerprint: String?, modifier: Modifier = Modifier, length: Int? = null, tag: String = fingerprint ?: "hidden") {
    val p = LocalKitPalette.current
    val word = when {
        !fingerprint.isNullOrBlank() -> fingerprint.take(12)
        length != null -> "hidden · $length"
        else -> "hidden"
    }
    Row(
        modifier.testTag(KitPartTags.fingerprint(tag))
            .clip(RoundedCornerShape(KitDensity.corner)).background(p.surfaceSelected)
            .padding(horizontal = KitDensity.small, vertical = KitDensity.small / 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◆", color = p.accent, fontSize = KitDensity.caption)
        Text(word, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1,
            modifier = Modifier.padding(start = KitDensity.small / 2))
    }
}

/** A one-of-few pick drawn as joined segments; announced as radio buttons. */
@Composable
fun KitSegmented(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "segmented",
    enabled: (String) -> Boolean = { true },
) {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner))
            .border(KitDensity.rule, p.hairline, RoundedCornerShape(KitDensity.corner)),
    ) {
        for ((id, label) in options) {
            val on = id == selected
            Box(
                Modifier.weight(1f).testTag(KitPartTags.segment(tag, id))
                    .background(if (on) p.surfaceSelected else p.surface)
                    .selectable(selected = on, enabled = enabled(id), role = Role.RadioButton, onClick = { onSelect(id) })
                    .padding(vertical = KitDensity.medium),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) p.textPrimary else p.textSecondary, fontSize = KitDensity.title,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** A step of a [KitStepper]. */
enum class KitStepState { TODO, RUNNING, DONE, FAILED }

data class KitStep(val id: String, val title: String, val detail: String, val state: KitStepState, val action: KitAction? = null,
                   /** The row's test tag; [KitPartTags.step] of [id] when blank. */
                   val tag: String = "")

/**
 * Steps on a vertical rail: ○ ● ✓ ✗ joined by a line, the title, the detail under it and one
 * trailing button. A done step collapses to its title line.
 */
@Composable
fun KitStepper(steps: List<KitStep>, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner)).background(p.surface)
        .padding(horizontal = KitDensity.large, vertical = KitDensity.small)) {
        steps.forEachIndexed { i, s ->
            val (glyph, state) = when (s.state) {
                KitStepState.TODO -> "○" to KitState.IDLE
                KitStepState.RUNNING -> "●" to KitState.BUSY
                KitStepState.DONE -> "✓" to KitState.OK
                KitStepState.FAILED -> "✗" to KitState.BAD
            }
            Row(Modifier.fillMaxWidth().testTag(s.tag.ifBlank { KitPartTags.step(s.id) })) {
                Column(Modifier.width(KitDensity.glyph), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(glyph, color = kitStateColor(state), fontSize = KitDensity.head,
                        modifier = Modifier.padding(top = KitDensity.small))
                    if (i < steps.lastIndex) Box(Modifier.width(KitDensity.rule).height(KitDensity.large).background(p.hairline))
                }
                Column(Modifier.weight(1f).padding(start = KitDensity.small, top = KitDensity.small, bottom = KitDensity.small)) {
                    Text(s.title, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Medium)
                    if (s.state != KitStepState.DONE && s.detail.isNotBlank()) {
                        Text(s.detail, color = if (s.state == KitStepState.FAILED) p.bad else p.textSecondary, fontSize = KitDensity.body)
                    }
                }
                s.action?.let { a ->
                    TextButton(onClick = a.onClick, enabled = a.enabled, modifier = Modifier.testTag(a.tag).align(Alignment.CenterVertically)) {
                        Text(a.label, color = p.accent, fontSize = KitDensity.title)
                    }
                }
            }
        }
    }
}

/**
 * The dense list row (Store look): optional leading glyph, label, secondary line, optional state
 * pill, then a trailing slot (one button, a value, a fingerprint). [onLongClick] is the
 * "copy path" affordance; [onClick] opens the row.
 */
@Composable
fun KitListRow(
    label: String,
    modifier: Modifier = Modifier,
    secondary: String = "",
    leading: String? = null,
    pill: Pair<String, KitState>? = null,
    tag: String = label,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    val gestures = if (onClick != null || onLongClick != null) Modifier.pointerInput(onClick, onLongClick) {
        detectTapGestures(onTap = { onClick?.invoke() }, onLongPress = { onLongClick?.invoke() })
    } else Modifier
    Row(
        modifier.fillMaxWidth().testTag(KitPartTags.listRow(tag)).then(gestures)
            .padding(vertical = KitDensity.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Box(Modifier.size(KitDensity.glyph).clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected),
            contentAlignment = Alignment.Center) { Text(leading, color = p.textPrimary, fontSize = KitDensity.body) }
        Column(Modifier.weight(1f).padding(start = if (leading != null) KitDensity.medium else 0.dp)) {
            Text(label, color = p.textPrimary, fontSize = KitDensity.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (secondary.isNotBlank()) Text(secondary, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        if (pill != null) KitStatePill(pill.first, pill.second, Modifier.padding(start = KitDensity.small))
        if (trailing != null) Box(Modifier.padding(start = KitDensity.small)) { trailing() }
    }
}

/** A chip: [label] and, when [onRemove] is set, an × that calls it (a grant's key on the Secrets page); null = read-only. */
@Composable
fun KitChip(label: String, onRemove: (() -> Unit)?, modifier: Modifier = Modifier, tag: String = label, removeTag: String = "$tag:remove") {
    val p = LocalKitPalette.current
    Row(
        modifier.testTag(tag).clip(RoundedCornerShape(KitDensity.corner)).background(p.surfaceSelected)
            .padding(start = KitDensity.small, end = KitDensity.small / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.textPrimary, fontSize = KitDensity.caption, maxLines = 1)
        // A read-only chip (onRemove = null) carries no ×.
        if (onRemove != null) Text("×", color = p.textSecondary, fontSize = KitDensity.title,
            modifier = Modifier.testTag(removeTag).clickable(role = Role.Button, onClick = onRemove)
                .padding(horizontal = KitDensity.small / 2, vertical = KitDensity.small / 4))
    }
}

/** The sticky bar unsaved edits raise: "[summary] · Save · Discard". */
@Composable
fun KitSaveBar(summary: String, onSave: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier, saving: Boolean = false) {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().testTag(KitPartTags.SAVE_BAR).background(p.surfaceSelected)
            .padding(horizontal = KitDensity.large, vertical = KitDensity.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, color = p.textPrimary, fontSize = KitDensity.body, modifier = Modifier.weight(1f))
        TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.testTag(KitPartTags.DISCARD)) {
            Text("Discard", color = p.textSecondary, fontSize = KitDensity.title)
        }
        Button(onClick = onSave, enabled = !saving, modifier = Modifier.testTag(KitPartTags.SAVE)) {
            Text(if (saving) "Saving…" else "Save", fontSize = KitDensity.title)
        }
    }
}

/**
 * Sample data for every component, so a host's preview (or a compose test) draws each one
 * without a vault: `CloudKitTheme(palette) { KitSamples.All() }`.
 */
object KitSamples {
    val steps = listOf(
        KitStep("connected", "Connected", "GitHub · fetched 12:05", KitStepState.DONE),
        KitStep("profile", "Profile", "galaxy-s21 loaded", KitStepState.RUNNING),
        KitStep("shell", "Shell", "no channel yet", KitStepState.FAILED, KitAction("Retry", "sample:retry") {}),
        KitStep("store", "Store", "Cloud Store not installed", KitStepState.TODO, KitAction("Install", "sample:install") {}),
    )

    @Composable
    fun All() {
        Column(verticalArrangement = Arrangement.spacedBy(KitDensity.medium)) {
            KitHero("Ada Lovelace", listOf("ada@example.org", "London/UK · Analytical"), "AL")
            KitDeviceCard("phone-a", "Pixel 9 · Android 16", "profile loaded · backed up 10:48", badge = "this phone",
                menu = listOf(KitAction("Load", "sample:load") {}))
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                KitStatTile("247", "apps installed", KitState.OK, Modifier.weight(1f))
                KitStatTile("12", "keys drift", KitState.WARN, Modifier.weight(1f))
            }
            KitActionBar(listOf(KitAction("Backup now", "sample:backup") {}, KitAction("Restore", "sample:restore") {}))
            KitStatusBanner("Connected · GitHub · fetched 12:05", KitState.OK)
            KitListRow("Company", secondary = "about › profile › company", pill = "set" to KitState.OK) { KitFingerprint("3fa9c1d2e4b5") }
            KitSegmented(listOf("github" to "GitHub", "gitea" to "Gitea"), "github", {})
            KitStepper(steps)
            KitSaveBar("3 changes", {}, {})
        }
    }
}
