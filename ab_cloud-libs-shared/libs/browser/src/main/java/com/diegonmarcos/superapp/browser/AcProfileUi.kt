package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #900 Autocomplete > Profiles: the list of personal profiles ("Diego - ES", "Diego - BR"), the default one starred,
 * and the editor behind a tap. [profiles] is the caller's current list (hoisted); every change goes out as a callback.
 */
@Composable
fun AcProfilesSection(
    profiles: List<AcProfile>,
    onSave: (AcProfile) -> Unit,
    onDelete: (String) -> Unit,
    onDefault: (String) -> Unit,
    onImportAccount: (() -> Unit)? = null,
    note: String? = null,
) {
    val p = LocalKitPalette.current
    var editing by remember { mutableStateOf<AcProfile?>(null) }
    Column(Modifier.fillMaxWidth().testTag("browser:ac:profiles")) {
        val e = editing
        if (e != null) {
            AcProfileEditor(e, onSave = { onSave(it); editing = null }, onCancel = { editing = null },
                onDelete = if (profiles.any { it.id == e.id }) ({ onDelete(e.id); editing = null }) else null)
            return@Column
        }
        KitSectionHeader("Profiles", "Many names, addresses and documents per profile; a chooser asks which at fill time.")
        note?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodySmall) }
        if (profiles.isEmpty()) Text("No profile yet.", color = p.textSecondary, modifier = Modifier.padding(vertical = 6.dp).testTag("browser:ac:profiles:empty"))
        profiles.forEach { pr ->
            KitListRow(
                label = (if (pr.isDefault) "★ " else "") + pr.title(),
                secondary = listOf("${pr.names.size} name(s)", "${pr.addresses.size} address(es)", "${pr.phones.size} phone(s)", "${pr.emails.size} email(s)", "${pr.documents.size} document(s)")
                    .joinToString(" · "),
                tag = "ac-profile-${pr.id}",
                onClick = { editing = pr },
                trailing = if (pr.isDefault) null else ({ TextButton({ onDefault(pr.id) }, modifier = Modifier.testTag("browser:ac:default:${pr.id}")) { Text("Make default") } }),
            )
        }
        Row {
            TextButton({ editing = AcProfile(label = "Profile ${profiles.size + 1}", isDefault = profiles.isEmpty(), names = listOf(AcName(isDefault = true))) },
                modifier = Modifier.testTag("browser:ac:profiles:add")) { Text("＋ Add profile") }
            if (onImportAccount != null) TextButton(onImportAccount, modifier = Modifier.testTag("browser:ac:profiles:import")) { Text("Import from Cloud Account") }
        }
    }
}

@Composable
private fun AcText(label: String, value: String, tag: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().testTag(tag), singleLine = true, label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun AcRecordHead(title: String, isDefault: Boolean, tag: String, onDefault: () -> Unit, onRemove: () -> Unit) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Checkbox(isDefault, onCheckedChange = { onDefault() }, modifier = Modifier.testTag("$tag:default"))
        Text("default", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
        TextButton(onRemove, modifier = Modifier.testTag("$tag:remove")) { Text("✕") }
    }
}

/**
 * The profile editor. Every kind of detail is a LIST: two names, three labelled addresses, several phones and emails,
 * documents (type, number, issuing country, citizenship), birth date, organisations. Exactly one record of each list is
 * the default (what a fill uses unless the chooser says otherwise).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcProfileEditor(profile: AcProfile, onSave: (AcProfile) -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    var pr by remember(profile.id) { mutableStateOf(profile) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun <T> only(list: List<T>, at: Int, isDef: (T, Boolean) -> T) = list.mapIndexed { i, t -> isDef(t, i == at) }

    Column(Modifier.fillMaxWidth().testTag("browser:ac:editor"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AcText("Profile label (e.g. Diego - ES)", pr.label, "browser:ac:label") { pr = pr.copy(label = it) }

        KitSectionHeader("Names", "")
        pr.names.forEachIndexed { i, n ->
            val tag = "browser:ac:names:$i"
            AcRecordHead("Name ${i + 1}", n.isDefault, tag, { pr = pr.copy(names = only(pr.names, i) { x, d -> x.copy(isDefault = d) }) }, { pr = pr.copy(names = pr.names - n) })
            fun set(f: (AcName) -> AcName) { pr = pr.copy(names = pr.names.map { if (it.id == n.id) f(it) else it }) }
            AcText("Given name", n.given, "$tag:given") { v -> set { it.copy(given = v) } }
            AcText("Middle name", n.middle, "$tag:middle") { v -> set { it.copy(middle = v) } }
            AcText("Family name", n.family, "$tag:family") { v -> set { it.copy(family = v) } }
            AcText("Full name (blank = joined from the parts)", n.full, "$tag:full") { v -> set { it.copy(full = v) } }
            AcText("Alias / nickname", n.alias, "$tag:alias") { v -> set { it.copy(alias = v) } }
        }
        TextButton({ pr = pr.copy(names = pr.names + AcName(isDefault = pr.names.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:names")) { Text("＋ Add name") }

        KitSectionHeader("Addresses", "")
        pr.addresses.forEachIndexed { i, a ->
            val tag = "browser:ac:addresses:$i"
            AcRecordHead("Address ${i + 1}", a.isDefault, tag, { pr = pr.copy(addresses = only(pr.addresses, i) { x, d -> x.copy(isDefault = d) }) }, { pr = pr.copy(addresses = pr.addresses - a) })
            fun set(f: (AcAddress) -> AcAddress) { pr = pr.copy(addresses = pr.addresses.map { if (it.id == a.id) f(it) else it }) }
            AcText("Label (home, work, ...)", a.label, "$tag:label") { v -> set { it.copy(label = v) } }
            AcText("Street", a.street, "$tag:street") { v -> set { it.copy(street = v) } }
            AcText("Number", a.number, "$tag:number") { v -> set { it.copy(number = v) } }
            AcText("Apartment / floor / c/o", a.line2, "$tag:line2") { v -> set { it.copy(line2 = v) } }
            AcText("City", a.city, "$tag:city") { v -> set { it.copy(city = v) } }
            AcText("Postal code", a.postal, "$tag:postal") { v -> set { it.copy(postal = v) } }
            AcText("Region / state", a.region, "$tag:region") { v -> set { it.copy(region = v) } }
            AcText("Country (ES, DE, Brasil, ...)", a.country, "$tag:country") { v -> set { it.copy(country = v) } }
        }
        TextButton({ pr = pr.copy(addresses = pr.addresses + AcAddress(isDefault = pr.addresses.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:addresses")) { Text("＋ Add address") }

        KitSectionHeader("Phones", "")
        pr.phones.forEachIndexed { i, x ->
            val tag = "browser:ac:phones:$i"
            AcRecordHead("Phone ${i + 1}", x.isDefault, tag, { pr = pr.copy(phones = only(pr.phones, i) { y, d -> y.copy(isDefault = d) }) }, { pr = pr.copy(phones = pr.phones - x) })
            AcText("Label", x.label, "$tag:label") { v -> pr = pr.copy(phones = pr.phones.map { if (it.id == x.id) it.copy(label = v) else it }) }
            AcText("Number", x.value, "$tag:value") { v -> pr = pr.copy(phones = pr.phones.map { if (it.id == x.id) it.copy(value = v) else it }) }
        }
        TextButton({ pr = pr.copy(phones = pr.phones + AcPhone(isDefault = pr.phones.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:phones")) { Text("＋ Add phone") }

        KitSectionHeader("Emails", "")
        pr.emails.forEachIndexed { i, x ->
            val tag = "browser:ac:emails:$i"
            AcRecordHead("Email ${i + 1}", x.isDefault, tag, { pr = pr.copy(emails = only(pr.emails, i) { y, d -> y.copy(isDefault = d) }) }, { pr = pr.copy(emails = pr.emails - x) })
            AcText("Label", x.label, "$tag:label") { v -> pr = pr.copy(emails = pr.emails.map { if (it.id == x.id) it.copy(label = v) else it }) }
            AcText("Address", x.value, "$tag:value") { v -> pr = pr.copy(emails = pr.emails.map { if (it.id == x.id) it.copy(value = v) else it }) }
        }
        TextButton({ pr = pr.copy(emails = pr.emails + AcEmail(isDefault = pr.emails.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:emails")) { Text("＋ Add email") }

        KitSectionHeader("ID documents", "National ID, passport, tax ID, residence permit; one per citizenship.")
        pr.documents.forEachIndexed { i, d ->
            val tag = "browser:ac:documents:$i"
            AcRecordHead("Document ${i + 1}", d.isDefault, tag, { pr = pr.copy(documents = only(pr.documents, i) { y, dd -> y.copy(isDefault = dd) }) }, { pr = pr.copy(documents = pr.documents - d) })
            fun set(f: (AcDocument) -> AcDocument) { pr = pr.copy(documents = pr.documents.map { if (it.id == d.id) f(it) else it }) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AcDocType.ALL.forEach { (id, label) ->
                    FilterChip(selected = d.type == id, onClick = { set { it.copy(type = id) } }, label = { Text(label) }, modifier = Modifier.testTag("$tag:type:$id"))
                }
            }
            AcText("Number", d.number, "$tag:number") { v -> set { it.copy(number = v) } }
            AcText("Issuing country", d.issuingCountry, "$tag:issuing") { v -> set { it.copy(issuingCountry = v) } }
            AcText("Citizenship", d.citizenship, "$tag:citizenship") { v -> set { it.copy(citizenship = v) } }
            AcText("Expires (yyyy-mm-dd)", d.expires, "$tag:expires") { v -> set { it.copy(expires = v) } }
        }
        TextButton({ pr = pr.copy(documents = pr.documents + AcDocument(isDefault = pr.documents.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:documents")) { Text("＋ Add document") }

        KitSectionHeader("Dates and organisations", "")
        pr.dates.forEachIndexed { i, d ->
            val tag = "browser:ac:dates:$i"
            AcRecordHead("Birth date", d.isDefault, tag, { pr = pr.copy(dates = only(pr.dates, i) { y, dd -> y.copy(isDefault = dd) }) }, { pr = pr.copy(dates = pr.dates - d) })
            AcText("yyyy-mm-dd", d.value, "$tag:value") { v -> pr = pr.copy(dates = pr.dates.map { if (it.id == d.id) it.copy(value = v) else it }) }
        }
        TextButton({ pr = pr.copy(dates = pr.dates + AcDate(isDefault = pr.dates.isEmpty())) }, modifier = Modifier.testTag("browser:ac:add:dates")) { Text("＋ Add birth date") }
        pr.organisations.forEachIndexed { i, o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AcText("Organisation ${i + 1}", o, "browser:ac:organisations:$i", { v -> pr = pr.copy(organisations = pr.organisations.mapIndexed { j, x -> if (j == i) v else x }) }, Modifier.weight(1f))
                TextButton({ pr = pr.copy(organisations = pr.organisations.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.testTag("browser:ac:organisations:$i:remove")) { Text("✕") }
            }
        }
        TextButton({ pr = pr.copy(organisations = pr.organisations + "") }, modifier = Modifier.testTag("browser:ac:add:organisations")) { Text("＋ Add organisation") }

        HorizontalDivider(color = p.hairline, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onDelete != null) TextButton({ confirmDelete = true }, modifier = Modifier.testTag("browser:ac:editor:delete")) { Text("Delete profile") }
            TextButton(onCancel, modifier = Modifier.testTag("browser:ac:editor:cancel")) { Text("Cancel") }
            TextButton({ onSave(pr.copy(
                names = pr.names.filterNot { it.isEmpty }, addresses = pr.addresses.filterNot { it.isEmpty }, phones = pr.phones.filter { it.value.isNotBlank() },
                emails = pr.emails.filter { it.value.isNotBlank() }, documents = pr.documents.filter { it.number.isNotBlank() }, dates = pr.dates.filter { it.value.isNotBlank() },
                organisations = pr.organisations.filter { it.isNotBlank() }).normalized()) },
                modifier = Modifier.testTag("browser:ac:editor:save")) { Text("Save") }
        }
    }
    if (confirmDelete && onDelete != null) KitConfirmDialog("Delete this profile?", "Its names, addresses and documents are removed from this phone.", "Delete", "Keep",
        onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
}
