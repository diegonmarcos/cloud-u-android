package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.settings.LauncherConfigFragment
import com.diegonmarcos.superapp.cloud.DriveConnectionsFragment
import com.diegonmarcos.superapp.cloud.C3MeshFragment
import com.diegonmarcos.superapp.cloud.C3HealthFragment
import com.diegonmarcos.superapp.cloud.CalendarMonthFragment
import com.diegonmarcos.superapp.cloud.CalendarAgendaFragment
import com.diegonmarcos.superapp.network.WireGuardFragment
import com.diegonmarcos.superapp.profile.ProfileFragment
import com.diegonmarcos.superapp.apps.RecentAppsFragment

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ai.AiNotBuiltFragment
import com.diegonmarcos.superapp.ai.AiTextEnhanceFragment
import com.diegonmarcos.superapp.ai.AiTokensFleetFragment
import com.diegonmarcos.superapp.chat.ChatPages
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.ops.OpsPages
// libs:wallet MOVED to ac_cloud-wallet (constellation APK). No WalletFragment here.

/**
 * Per-section page registry — now driven by [Sections] (which mirrors
 * `build.json::ui.sections[].pages[]`). The Kotlin side only owns the
 * **factory mapping** from a section's `pageId` to the actual Fragment
 * subclass; the *list* of pages and their order come from build.json.
 *
 * Adding a new section's pages:
 *   1. `pages: [{id, label, upstream}]` in build.json::ui.sections[X].
 *   2. Implement page Fragments in libs:<x>/.
 *   3. Add a `when` branch in [factoryFor] below for the new section id.
 */
object SectionPages {

    data class Page(val id: String, val label: String, val iconName: String = "", val action: String = "", val isAction: Boolean = false, val factory: () -> Fragment)

    /** The section's pages as listed to the user. Pass [includeHidden] when
     *  RESOLVING a target rather than listing children: a hidden page (Labs'
     *  c3, quant, …) still has to answer to `page:cloud/quant`. */
    fun pagesFor(sectionId: String, includeHidden: Boolean = false): List<Page> {
        val section = Sections.byId(sectionId) ?: return emptyList()
        return (if (includeHidden) section.allPages else section.pages).map { p ->
            Page(p.id, p.label, p.iconName ?: "", p.action, p.isAction) {
                factoryFor(sectionId, p.id, p.label, p.url, p.tabs)
            }
        }
    }

    private fun factoryFor(
        sectionId: String,
        pageId: String,
        label: String,
        url: String = "",
        tabs: List<String> = emptyList(),
    ): Fragment = when {
        // A page that declares `tabs` IS a strip over them — the same
        // SectionTabsFragment a tabbed SECTION wears, so this app has one tab
        // mechanism rather than two to keep in step. Checked before `url` and
        // before every id below, so grouping two pages behind one is a
        // build.json edit here too, not a branch.
        tabs.isNotEmpty() -> SectionTabsFragment.forPage(sectionId, pageId)
        // A page that declares a `url` IS that page. Checked first and by
        // data, so embedding the next one is a build.json edit, not a branch.
        url.isNotBlank() -> WebPageFragment.newInstance(url)
        sectionId == "mail"  -> MailPages.fragmentFor(pageId)
        // Phone ▸ Apps: installed Android apps, not build.json tile data — so
        // it is an ordinary page here, unlike its `facet: true` sibling Configs.
        sectionId == "phone" && pageId == "apps" ->
            com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.newInstance()
        sectionId == "chat"  -> ChatPages.fragmentFor(pageId)
        sectionId == "c3"    && pageId == "health"      -> C3HealthFragment.newInstance()
        sectionId == "c3"    && pageId == "dagu"        -> OpsPages.fragmentForDagu()
        sectionId == "wg"    && pageId == "status"      -> C3MeshFragment.newInstance()
        sectionId == "feed"  && pageId == "all"         -> RssFeedFragment.newInstance()
        sectionId == "drive" && pageId == "connections" -> DriveConnectionsFragment.newInstance()
        sectionId == "config" && pageId == "profile"   -> ProfileFragment.newInstance()
        // Configs ▸ AI's FIVE TABS. The `ai` page id itself is not here: it declares `tabs`, so
        // the branch at the top of this `when` already answered it with the shared strip, and a
        // second answer below would be unreachable.
        //
        // THREE OF THESE HAVE NO AGENT BEHIND THEM YET and say so. They share one fragment because
        // nothing distinguishes them but the sentence, and the sentence is a string resource — the
        // title and the detail line are passed in, so a placeholder cannot be shipped with a
        // hardcoded, untranslatable string in it.
        sectionId == "config" && pageId == "websearch" -> AiNotBuiltFragment.newInstance(
            R.string.ai_websearch_title, R.string.ai_websearch_detail)
        sectionId == "config" && pageId == "localsearch" -> AiNotBuiltFragment.newInstance(
            R.string.ai_localsearch_title, R.string.ai_localsearch_detail)
        sectionId == "config" && pageId == "library" -> AiNotBuiltFragment.newInstance(
            R.string.ai_library_title, R.string.ai_library_detail)
        sectionId == "config" && pageId == "textenhance" -> AiTextEnhanceFragment.newInstance()
        sectionId == "config" && pageId == "tokens"      -> AiTokensFleetFragment.newInstance()
        // Launcher's THEME tab. The `launcher` id names the two-tab strip now
        // (build.json::ui.sections[config].pages[launcher].tabs), so the theme
        // screen needed an id of its own — nothing it stores moved with it,
        // LauncherThemePrefs and friends key off fixed store names.
        sectionId == "config" && pageId == "theme" -> LauncherConfigFragment.newInstance()
        sectionId == "config" && pageId == "kde"            -> com.diegonmarcos.superapp.kdeconnect.KdeConnectFragment.newInstance()
        sectionId == "config" && pageId == "constellation"  -> com.diegonmarcos.superapp.appstore.ConstellationFragment()
        sectionId == "config" && pageId == "wg"             -> WireGuardFragment.newInstance()
        // "myfin" section is GONE — the dashboard moved to Cloud-Me (Buro > Fin)
        // and libs:fin left with it. The tile that deep-linked to it is gone too
        // (the owner dropped Projects Me ▸ MyFin), so nothing here targets
        // Buro > Fin; it is reached from inside Cloud-Me.
        sectionId == "cal"     && pageId == "month"         -> CalendarMonthFragment.newInstance()
        sectionId == "cal"     && pageId == "agenda"        -> CalendarAgendaFragment.newInstance()
        // "wallet" section is dead — tile target extapp:cloud-wallet bypasses openSectionPage.
        // "health" has no pages any more — the MyHealth surface moved to Cloud-Me
        // (Projects > Health); tile target extapp:cloud-me#page:projects/health. The
        // section survives in build.json for its `metrics` taxonomy alone, which
        // Configs > Permissions reads to count the Health Connect grants.
        sectionId == "wg"     && pageId == "config"         -> WireGuardFragment.newInstance()
        sectionId == "config" && pageId == "onehand" ->
            com.diegonmarcos.superapp.configs.OneHandFragment.newInstance()
        // Panel's Control tab. Its sibling tab `notify` is NOT here: it is a
        // facet declaring `mirror_page`, so it is answered before this map is
        // ever consulted — see LauncherNavController.pageFragment.
        sectionId == "config" && pageId == "control" ->
            com.diegonmarcos.superapp.configs.ControlFragment.newInstance()
        sectionId == "config" && pageId == "perms" ->
            com.diegonmarcos.superapp.configs.PermissionsFragment.newInstance()
        sectionId == "config" && (pageId == "about" || pageId == "dev") ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance()
        // "browser" section is dead — tile target extapp:cloud-browser bypasses openSectionPage.
        sectionId == "apptabs"                              ->
            com.diegonmarcos.superapp.apptabs.AppTabsFragment.newInstance()
        sectionId == "recentapps"                           ->
            RecentAppsFragment.newInstance()
        else -> PageContentFragment.newInstance(sectionId, pageId, label)
    }
}
