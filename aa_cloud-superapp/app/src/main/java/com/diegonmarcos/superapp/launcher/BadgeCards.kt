package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.Haptics

/**
 * #1068 — the top of the Notify page: every declared badge as a One UI live / ongoing card, in the order build.json
 * ranks them (`card.rank` in `ui.notification_center.producers`; the shade's own group order is untouched). One list, one source: [BadgeDeclaration.badges] over [BadgeServices.declared]; a card exists for
 * every entry, drawn from [BadgeCardData] (the badge providers' real data), so a badge added to build.json is a card
 * here with no edit. No control lives on a card: the switches, Launch and customisation are in the page's Settings
 * section at the very bottom.
 */
internal object BadgeCardTags {
    const val LIST = "badge-cards"
    fun card(id: String) = "badge-card:$id"
    fun progress(id: String) = "badge-card-progress:$id"
}

/** The declared badges in display order: `card.rank` from build.json, ties in declaration order (the sort is stable). */
internal fun orderedBadges(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context): List<BadgeDeclaration.Badge> =
    BadgeDeclaration.badges(BadgeServices.declared).sortedBy { it.card.rank }

@Composable
internal fun BadgeCards() {
    val ctx = LocalContext.current
    val cards = remember { orderedBadges(ctx).map { BadgeCardData.read(ctx, it) } }
    Column(Modifier.fillMaxWidth().testTag(BadgeCardTags.LIST), verticalArrangement = Arrangement.spacedBy(NotifCard.gap)) {
        for (d in cards) androidx.compose.runtime.key(d.badge.id) { BadgeCard(d) }
        Box(Modifier.height(NotifCard.gap))
    }
}

private fun accentOf(d: BadgeCardData.Data): Color =
    runCatching { Color(android.graphics.Color.parseColor(d.badge.card.accent)) }.getOrDefault(Color(0xFF8AB4F8))

@Composable
internal fun BadgeCard(d: BadgeCardData.Data) {
    val ctx = LocalContext.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val accent = accentOf(d)
    val icon = remember(d.badge.icon) { Sections.iconResFor(ctx, d.badge.icon) }
    val tap = d.open
    Column(
        Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)
            .then(if (tap != null) Modifier.clickable {
                Haptics.tap(hostView); runCatching { tap.send() }
            } else Modifier)
            .padding(start = NotifCard.pad, end = NotifCard.pad, top = 12.dp, bottom = 14.dp)
            .testTag(BadgeCardTags.card(d.badge.id)),
    ) {
        // The app-name row of a One UI notification: small tinted icon, name, state, no controls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(NotifCard.headerIcon))
            Text(d.appName, color = NotifCard.title, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
                fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
            Text(" • " + (if (d.live) "live" else "not posted"), color = NotifCard.meta, fontSize = NotifCard.metaSp,
                fontFamily = NotifCard.font, maxLines = 1)
            Box(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.live) accent else NotifCard.pill))
        }
        // The live value, large; the supporting line under it.
        Text(d.headline, color = NotifCard.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        val sub = d.sub.ifBlank { d.note }
        if (sub.isNotBlank()) Text(sub, color = NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        d.progress?.let { p ->
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(NotifCard.pill)
                .testTag(BadgeCardTags.progress(d.badge.id))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(accent))
            }
        }
        if (d.badge.card.kind == "quotes") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in d.lines) Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(NotifCard.pill)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(l.label, color = NotifCard.meta, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 1)
                    Text(l.value, color = NotifCard.title, fontSize = NotifCard.bodySp, fontWeight = FontWeight.SemiBold,
                        fontFamily = NotifCard.font, maxLines = 1)
                }
            }
        } else for (l in d.lines) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (l.label.isNotBlank()) Text(l.label, color = NotifCard.meta, fontSize = NotifCard.bodySp,
                fontFamily = NotifCard.font, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
            Text(l.value, color = NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
