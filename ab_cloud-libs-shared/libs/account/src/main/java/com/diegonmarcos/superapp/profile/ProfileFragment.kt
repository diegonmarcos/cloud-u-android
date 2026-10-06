package com.diegonmarcos.superapp.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.auth.ConfigArtifact
import com.diegonmarcos.cloudlib.auth.ProfileJourney
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.cloudlib.auth.UserRegistry
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.uikit.kitComposeView
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs → ACCOUNT (#778) — exactly FOUR tabs, declared {id, label} in
 * build.json::ui.profile.tabs; this file maps an id to its column and names no
 * label, order or membership of its own.
 *
 *  • CONNECT — the #573 journey, whose step 1 is the declared sign-in LINES
 *    (ui.profile.connect: Authelia → Gitea = WebAuth | Bearer, GitHub = WebAuth |
 *    SSH / PAT, Import File), every way one pill of the same design, dispatched on
 *    its `kind` alone; the Authelia ways host the SHARED libs:auth SignInWays, and
 *    GitHub WebAuth is gh's own sign-in in the gh engine ([GhEngine], #713). Every
 *    line fetches the vault configs (the Authelia line through its mailed-code leg,
 *    [showVaultFetchDialog]); then who and which device — and nothing after (#766).
 *  • PROFILES — the declared config held in app storage (the local copy L, else
 *    the server file S), per topic, every declared field filled or `empty`, with
 *    Populate from runtime / from the server file, Save and Export ([ProfilesTab]).
 *  • RUNTIME — what each fleet app is using right now, read live per app, with its
 *    status and declared/reported/missing counts ([RuntimeTab]); nothing else.
 *  • DRIFT — S, R and L side by side with their metadata, the S↔R / L↔S / L↔R
 *    diffs per app and per field, and the sync actions ([DriftTab]).
 *  Profiles, Runtime and Drift are Compose on libs:ui-kit over ONE [AccountModel],
 *  which the debug API (`/api/account/…`, [AccountDebugApi]) drives too.
 *
 * RESTRUCTURED, NOT REBUILT (#778): Infos became Profiles (same schema, same mask),
 * Cloud Constellation Setup became Runtime (its index, wizard, cockpit cards and
 * repos deleted), and per-app apply moved to Drift's server → runtime.
 *
 * THE CONTACT CARD FORM IS GONE (#781). Its fields live in [ProfilePrefs], filled
 * from Profiles' `about` topic through Drift's server → runtime; this page still
 * hands them to [ProfileSync] when it is left ([onPause]), and the GDPR erase
 * is `/api/account/erase` ([AccountDebugApi]).
 */
class ProfileFragment : Fragment() {

    private lateinit var prefs: ProfilePrefs


    /** Which tab is showing. Held on the fragment so the many detach/attach
     *  redraws below do not bounce the user off the tab they were on. Negative
     *  until the strip is first built. */
    private var selectedTab = -1

    /** Position of the Connect tab — the sign-in, the fetch and the device pick. */
    private var connectTab = 0

    /** #778 Positions of Profiles (the declared copy), Runtime (per app) and Drift (S/R/L, sync), read off the strip. */
    private var profilesTab = 0
    private var runtimeTab = 0
    private var driftTab = 0

    /** The declared tab labels, in strip order — so a pointer to a tab names it in the declaration's words. */
    private var tabLabels: List<String> = emptyList()

    private fun tabLabel(index: Int): String = tabLabels.getOrNull(index).orEmpty()

    /** The strip itself, so the cockpit can send the owner to Connect. */
    private var strip: PageTabsView? = null

    /** Shows tab [index] and tells the host: the one door every selection goes through. */
    private var pickTab: ((Int) -> Unit)? = null

    /** The declared tab ids, in strip order (the ids [selectTab] and [onTabShown] speak). */
    private var tabIds: List<String> = emptyList()

    /**
     * #868 A host that draws the tabs itself (Cloud Account's bottom island) starts this fragment with
     * [ARG_EXTERNAL_STRIP], which hides the fragment's own strip, drives it through [selectTab], and is
     * told the tab on screen through [onTabShown] - on every change AND once when the host attaches, so
     * a host's selection never starts out of step with the page.
     */
    var onTabShown: ((String) -> Unit)? = null
        set(value) {
            field = value
            tabIds.getOrNull(selectedTab)?.let { value?.invoke(sectionOf(it)) }
        }

    /** Shows the declared tab [id]; an id with no column is ignored. */
    fun selectTab(id: String) {
        val i = tabIds.indexOf(id)
        if (i >= 0) pickTab?.invoke(i)
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = ProfilePrefs(ctx)

        // TWO TABS, ONE FRAGMENT, NO CHILD FRAGMENTS.
        //
        // The launcher's tabbed sections ([SectionTabsFragment]) swap CHILD
        // FRAGMENTS into a fixed pool of pane host ids. That is right for a
        // section — it is driven by build.json::ui.sections[].pages[], and a
        // page with a blank `action` is what [LauncherNavController.isTabbed]
        // counts — but it is the wrong machinery here twice over: Profile is a
        // page, not a section, so wiring it that way would mean inventing
        // build.json pages purely to get a strip; and this screen REBUILDS
        // ITSELF (detach/attach) after every image pick, token link, credential
        // clear, erase and import, which is exactly the sequence that leaves a
        // fixed-id pane blank when the re-commit lands on a host that is still
        // occupied.
        //
        // So the pages are plain columns built inline and toggled by
        // visibility — the same answer the RSS pages reached — and only the
        // pill CHROME is shared, through libs:bottomnav's PageTabsView. Nothing here touches
        // build.json, the host-id pool, or MAX_PANES.
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        scroll.addView(page)

        // FOUR content columns (#778), one per declared tab id.
        val connect = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val profiles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val runtime = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val drift = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // #873 Cloud Account's own pages (build.json ui.sections[].pages[]): the host app declares them, this app draws them.
        val fleetsetup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        listOf(connect, profiles, runtime, drift, fleetsetup).forEach(page::addView)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // THE STRIP IS DATA (#614; four tabs since #778): build.json::ui.profile.tabs
        // (UI_PROFILE_TABS_B64, read by AccountModel.tabs) lists {id, label} per tab in
        // render order, and this map is only the id → column lookup. The LABEL comes off
        // the declaration; an id with no column here draws no tab rather than an invented one.
        val columns = mapOf("connect" to connect, "profiles" to profiles, "runtime" to runtime, "drift" to drift)
        val own = ctx.packageName == AccountData.PKG
        val tabs = AccountModel.tabs().mapNotNull { t -> columns[t.id]?.let { Tab(t.label, it) } } +
            (if (own) listOf(Tab(getString(R.string.fleetsetup_title), fleetsetup)) else emptyList())
        tabIds = AccountModel.tabs().filter { columns.containsKey(it.id) }.map { it.id } + (if (own) listOf(PAGE_FLEETSETUP) else emptyList())
        connectTab = tabs.indexOfFirst { it.column === connect }
        profilesTab = tabs.indexOfFirst { it.column === profiles }
        runtimeTab = tabs.indexOfFirst { it.column === runtime }
        driftTab = tabs.indexOfFirst { it.column === drift }
        tabLabels = tabs.map { it.title }
        val model = AccountModel.get(ctx)
        // The page opens on CONNECT until something is declared (#573): Profiles,
        // Runtime and Drift compare against a declaration. Once there is one, Profiles.
        if (selectedTab < 0) {
            selectedTab = if (model.shown() == null && !ProfileJourney.allDone(journeyState(ctx))) connectTab else profilesTab
        }
        root.addView(tabStrip(ctx, tabs))
        root.addView(scroll)

        // ── CONNECT: sign in, fetch, say which machine this is ────────────
        // #766 NOTHING renders below the journey. The old "Vault export" block
        // (its own browser login, mailed code and fetch) is gone: the vault leg
        // is the Authelia line's own continuation ([showVaultFetchDialog]), and
        // every line's fetch also answers the journey's who / which-device steps.
        renderJourney(ctx, connect)

        // ── PROFILES · RUNTIME · DRIFT (#778, Compose on libs:ui-kit) ─────
        val palette = AccountHost.palette(ctx)
        profiles.addView(ctx.kitComposeView(palette) { ProfilesTab(model, tabLabel(connectTab), { n, t -> export(n, t) }, { openRoute(it) }) })
        renderRuntime(ctx, runtime)
        drift.addView(ctx.kitComposeView(palette) { DriftTab(model, VaultCockpit.selectedDevice(ctx)) { n, t -> export(n, t) } })
        if (own) fleetsetup.addView(ctx.kitComposeView(palette) { FleetSetupTab(model) })

        return root
    }

    // ── Infos · THE FETCHED VAULT CONFIGS (#695) ──────────────────────────

    // ── Connect · THE JOURNEY (#573) ──────────────────────────────────────

    /** The journey's views, so a tap inside a card can repaint the lights without a full redraw. */
    private var journey: ProfileJourneyView.Journey? = null

    /** Steps whose last attempt reported an error — the card's red light. Memory only. */
    private val failedSteps = mutableSetOf<ProfileJourney.Step>()

    /** Everything the journey knows, gathered once per draw — see [ProfileJourney.State]. */
    private fun journeyState(ctx: android.content.Context): ProfileJourney.State {
        val session = SignIn.Current.session
        val provider = session?.let { SignIn.provider(it.provider) }
        return ProfileJourney.State(
            session = session,
            storedBearerEmail = ConfigsPrefs(ctx).autheliaEmail,
            identityOnly = provider != null &&
                !provider.grants(SignIn.GRANT_CONFIG_ARTIFACT) && !provider.grants(SignIn.GRANT_REPO_ARTIFACT),
            registry = UserRegistry.current(ctx),
            artifactInMemory = UserRegistry.Current.artifact != null,
            identity = UserRegistry.selectedIdentity(ctx),
            peer = UserRegistry.selectedPeer(ctx),
            appliedAt = UserRegistry.appliedAt(ctx),
            vaultFetched = VaultConnect.Imported.bundle != null,
            failed = failedSteps.toSet(),
        )
    }

    private fun ask(step: ProfileJourney.Step): String = getString(when (step) {
        ProfileJourney.Step.SIGN_IN -> R.string.journey_ask_sign_in
        ProfileJourney.Step.WHO -> R.string.journey_ask_who
        ProfileJourney.Step.DEVICE -> R.string.journey_ask_device
        ProfileJourney.Step.GET -> R.string.journey_ask_get
    })

    private fun stepLabel(step: ProfileJourney.Step): String = getString(when (step) {
        ProfileJourney.Step.SIGN_IN -> R.string.journey_step_sign_in
        ProfileJourney.Step.WHO -> R.string.journey_step_who
        ProfileJourney.Step.DEVICE -> R.string.journey_step_device
        ProfileJourney.Step.GET -> R.string.journey_step_get
    })

    /** Why a step is locked, in words; null when it is not locked. */
    private fun lockText(s: ProfileJourney.State, step: ProfileJourney.Step): String? {
        if (ProfileJourney.phase(s, step) != ProfileJourney.Phase.LOCKED) return null
        val provider = s.session?.let { SignIn.provider(it.provider) }
        return when (ProfileJourney.lock(s, step)) {
            ProfileJourney.Lock.SIGN_IN_FIRST -> getString(R.string.journey_lock_sign_in_first)
            ProfileJourney.Lock.IDENTITY_ONLY -> getString(R.string.journey_lock_identity_only,
                provider?.label ?: s.session?.provider.orEmpty(), s.session?.identity?.ifBlank { "—" } ?: "—")
            ProfileJourney.Lock.NO_REGISTRY -> getString(R.string.journey_lock_no_registry)
            ProfileJourney.Lock.PICK_IDENTITY -> getString(R.string.journey_lock_pick_identity)
            ProfileJourney.Lock.PICK_PEER -> getString(R.string.journey_lock_pick_peer)
            ProfileJourney.Lock.REFETCH -> getString(R.string.journey_lock_refetch)
            null -> null
        }
    }

    private fun profilesText(peer: UserRegistry.Peer): String =
        if (peer.profiles.isEmpty()) getString(R.string.journey_profiles_pending)
        else getString(R.string.journey_profiles_n, peer.profiles.size)

    private fun identityTag(id: UserRegistry.Identity): String =
        listOfNotNull(id.label.takeIf { it.isNotBlank() }, getString(R.string.journey_primary_tag).takeIf { id.primary })
            .joinToString(" · ").ifBlank { "—" }

    /** One line per step, worded from the state. */
    private fun summaries(s: ProfileJourney.State): Map<ProfileJourney.Step, String> {
        // A local: State lives in libs:auth now, and Kotlin will not smart-cast a
        // property declared in another module.
        val session = s.session
        val provider = session?.let { SignIn.provider(it.provider) }
        val signIn = when {
            session != null && s.identityOnly ->
                getString(R.string.journey_identity_only_done, provider?.label ?: session.provider, session.identity.ifBlank { "—" })
            session != null ->
                getString(R.string.journey_signed_in_as, session.identity.ifBlank { s.storedBearerEmail.ifBlank { "—" } }, provider?.label ?: session.provider)
            s.storedBearerEmail.isNotBlank() -> getString(R.string.journey_bearer_stored, s.storedBearerEmail)
            s.vaultFetched -> getString(R.string.journey_signed_in_vault, VaultConnect.Imported.via.ifBlank { "—" })
            else -> getString(R.string.journey_not_signed_in)
        }
        val who = lockText(s, ProfileJourney.Step.WHO) ?: s.chosenIdentity?.let {
            getString(R.string.journey_who_summary, it.email, identityTag(it))
        } ?: ask(ProfileJourney.Step.WHO)
        val device = lockText(s, ProfileJourney.Step.DEVICE) ?: s.chosenPeer?.let {
            getString(R.string.journey_device_summary, it.label, it.wgIp.ifBlank { "—" }, profilesText(it))
        } ?: ask(ProfileJourney.Step.DEVICE)
        val get = lockText(s, ProfileJourney.Step.GET) ?: if (s.appliedAt.isNotBlank())
            getString(R.string.journey_applied, s.appliedAt,
                getString(if (s.vaultFetched) R.string.journey_vault_fetched else R.string.journey_vault_not_fetched))
        else ask(ProfileJourney.Step.GET)
        return mapOf(
            ProfileJourney.Step.SIGN_IN to signIn, ProfileJourney.Step.WHO to who,
            ProfileJourney.Step.DEVICE to device, ProfileJourney.Step.GET to get,
        )
    }

    private fun heroSummary(s: ProfileJourney.State): String =
        if (ProfileJourney.allDone(s)) getString(R.string.journey_hero_done)
        else getString(R.string.journey_hero_step, ProfileJourney.stepNumber(s), ask(ProfileJourney.next(s)))

    /** Repaint lights and summaries from a fresh state, without rebuilding the bodies. */
    private fun paintJourney() {
        val j = journey ?: return
        val s = journeyState(requireContext())
        ProfileJourneyView.paint(j, s, summaries(s), heroSummary(s))
    }

    /**
     * THE JOURNEY. Built from one [ProfileJourney.State]; every body that has
     * something to show is built (so a done card re-opens on its header) and
     * [ProfileJourneyView.paint] decides which are open. Nothing here names a
     * provider, a user, an address or a device: providers come from
     * build.json, the rest from the artifact's registry, the step badges from
     * the cockpit declaration's `journey_icons`.
     */
    private fun renderJourney(ctx: android.content.Context, into: LinearLayout) {
        val s = journeyState(ctx)
        val reg = s.registry
        val layout = VaultCockpit.layout
        val icons = ProfileJourney.Step.values().associateWith { step ->
            AccountHost.iconFor(ctx, layout.journeyIcons[step.name.lowercase()].orEmpty())
        }
        val chosen = s.chosenIdentity
        val peer = s.chosenPeer
        val j = ProfileJourneyView.build(
            ctx,
            heroTitle = reg?.name?.ifBlank { null } ?: getString(R.string.journey_hero_title_empty),
            heroSubtitle = if (chosen != null && peer != null) getString(R.string.journey_hero_subtitle, chosen.email, peer.label)
                           else getString(R.string.journey_hero_subtitle_empty),
            heroIcon = icons.getValue(ProfileJourney.Step.WHO),
            labels = ProfileJourney.Step.values().associateWith { stepLabel(it) },
            icons = icons,
            toggleAction = getString(R.string.journey_card_toggle),
        )
        journey = j
        into.addView(j.root)

        buildSignInStep(ctx, s, j.cards.getValue(ProfileJourney.Step.SIGN_IN).body)
        if (reg != null) {
            buildWhoStep(ctx, s, reg, j.cards.getValue(ProfileJourney.Step.WHO).body)
            buildDeviceStep(ctx, s, reg, j.cards.getValue(ProfileJourney.Step.DEVICE).body)
        }
        buildGetStep(ctx, s, j.cards.getValue(ProfileJourney.Step.GET).body)
        paintJourney()
    }

    // ── Connect · the declared sign-in LINES (#695) ───────────────────────

    /** One way into a line: [kind] is the only thing dispatched on; [note] is
     *  why it cannot start here when this app wires no handler for it. */
    private data class Way(val id: String, val label: String, val kind: String, val note: String, val rung: String = "", val line: String = "") {
        /** "<line> · <way>" — how the journey says which way fetched the vault. */
        val via: String get() = if (line.isBlank()) label else "$line · $label"
    }

    /** One line of ways, side by side; [note] is what the line cannot do yet. */
    private data class Line(val id: String, val label: String, val note: String, val ways: List<Way>)

    /** build.json::ui.profile.connect (UI_PROFILE_CONNECT_B64); a broken bake is no JSON. */
    private fun connectDecl(): org.json.JSONObject = runCatching {
        org.json.JSONObject(String(android.util.Base64.decode(
            com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_CONNECT_B64, android.util.Base64.NO_WRAP)))
    }.getOrDefault(org.json.JSONObject())

    /** The declared lines, in order; an unparseable blob yields none rather than invented ones. */
    private fun connectLines(): List<Line> = runCatching {
        val arr = connectDecl().optJSONArray("lines") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            val w = l.optJSONArray("ways") ?: org.json.JSONArray()
            Line(l.getString("id"), l.optString("label", l.getString("id")), l.optString("note"),
                (0 until w.length()).map { j ->
                    val x = w.getJSONObject(j)
                    Way(x.getString("id"), x.optString("label", x.getString("id")), x.optString("kind"), x.optString("note"), x.optString("rung"),
                        l.optString("label", l.getString("id")))
                })
        }
    }.getOrDefault(emptyList())

    /**
     * Step 1: the declared LINES, one row of ways each, side by side — every way
     * drawn by [buildWay] from its kind, never from its label. Below each row,
     * what the line holds now and what it cannot do yet (its declared note).
     */
    private fun buildSignInStep(ctx: android.content.Context, s: ProfileJourney.State, body: LinearLayout) {
        body.addView(caption(ctx, getString(R.string.connect_caption, tabLabel(profilesTab), tabLabel(driftTab))))
        val policy = s.registry?.authProviders.orEmpty()
        val status = statusView(ctx)
        for (line in connectLines()) {
            body.addView(label(ctx, line.label))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "connect:${line.id}"
            }
            val extras = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            line.ways.forEachIndexed { i, way ->
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = "connect:${line.id}:${way.id}"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) marginStart = dp(ctx, 8) }
                }
                buildWay(ctx, s, way, policy, cell, extras, status)
                row.addView(cell)
            }
            // #766 The Authelia line's vault leg: the mailed code and the fetch, in
            // the line's pill, once a credential for it is held on this device.
            if (line.ways.any { it.kind in AUTHELIA_KINDS } && vaultCredentialHeld(ctx))
                extras.addView(FleetCockpitView.pill(ctx, getString(R.string.connect_vault_pill)) { showVaultFetchDialog(line.label) }
                    .apply { tag = "way:vault_route" })
            body.addView(row)
            body.addView(extras)
            body.addView(caption(ctx, lineHeld(ctx, line)))
            if (line.note.isNotBlank()) body.addView(caption(ctx, line.note))
        }
        body.addView(status)
    }

    /**
     * One way, by KIND. The two Authelia kinds are libs:auth's own vocabulary
     * (a provider's `kind`, lower-cased) and host the SHARED [SignInWays]
     * narrowed to exactly the providers of that kind the user's policy offers —
     * the dialog, the cookie and the bearer store are cloud-drive's too. The
     * GitHub read is this page's ([KIND_GITHUB_SSH_PAT]). Any other kind has no
     * handler here and says why (its declared note) instead of drawing a button.
     */
    private fun buildWay(
        ctx: android.content.Context, s: ProfileJourney.State, way: Way, policy: List<String>,
        cell: LinearLayout, extras: LinearLayout, status: TextView,
    ) {
        // #713 ONE BUTTON DESIGN on every line: the page's own kinds draw the same
        // pill the libs:auth ways draw (FleetCockpitView.pill, the way's declared
        // label), so Authelia → Gitea, GitHub and Import File read alike.
        if (way.kind == KIND_GITHUB_SSH_PAT) {
            cell.addView(wayPill(ctx, way) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(way.label)
                    .setItems(arrayOf<CharSequence>(getString(R.string.connect_way_ssh), getString(R.string.connect_way_pat))) { _, which ->
                        if (which == 0) showGithubSshDialog(way.via) else showGithubPatDialog(way.via)
                    }
                    .show()
            })
            return
        }
        if (way.kind == KIND_GH_AUTH_LOGIN) {
            cell.addView(wayPill(ctx, way) { ghSignIn(way, status) })
            extras.addView(caption(ctx, getString(R.string.connect_gh_caption)))
            return
        }
        if (way.kind == KIND_VAULT_FILE) {
            cell.addView(wayPill(ctx, way) {
                fileStatus = status
                fileVia = way.via
                vaultFilePicker.launch(arrayOf("application/json", "text/*", "*/*"))
            })
            extras.addView(caption(ctx, getString(R.string.journey_import_file_caption)))
            return
        }
        val declared = SignIn.providers.filter { it.kind.name.lowercase() == way.kind }
        val offered = SignIn.offered(policy).filter { it.kind.name.lowercase() == way.kind }
        when {
            offered.isNotEmpty() -> cell.addView(signInWay(ctx, way, offered.map { it.id }))
            declared.isNotEmpty() -> cell.addView(caption(ctx, getString(R.string.connect_way_not_offered, way.label)))
            else -> cell.addView(caption(ctx, getString(R.string.connect_way_unwired, way.label, way.note)))
        }
        // The bearer way's own controls (stored token, orphan link, mailed code)
        // stay with it, so a policy that does not offer it does not show them.
        if (offered.any { it.kind == SignIn.Kind.AUTHELIA_BEARER }) buildStoredBearer(ctx, s, extras, status)
    }

    /** THE shared sign-in surface (libs:auth, #587) for one way, drawn in the cockpit's pill with the way's declared label. */
    private fun signInWay(ctx: android.content.Context, way: Way, providerIds: List<String>): View =
        ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SignInWays(host = signInHost, policy = providerIds, pill = { _, tag, onClick ->
                    AndroidView(factory = { c -> FleetCockpitView.pill(c, way.label, onClick).apply { this.tag = tag } })
                })
            }
        }

    /** A page-kind way's button: the same pill the shared sign-in draws, tagged by kind. */
    private fun wayPill(ctx: android.content.Context, way: Way, onClick: () -> Unit): View =
        FleetCockpitView.pill(ctx, way.label, onClick).apply { tag = "way:${way.kind}" }

    /**
     * #713 GitHub ▸ WebAuth: gh's OWN `auth login` in the gh engine ([GhEngine]) —
     * GitHub CLI's public client inside gh, no OAuth app of the fleet's. gh prints
     * a one-time code and the page to enter it at; the code is copied and the page
     * opens in cloud-browser (the fleet's browser, only ever on the declared host).
     * Once gh holds a token it reads the vault export ONCE through the PAT's own
     * read ([fetchVaultFileWithToken]) and lands like every sign-in ([landVault]);
     * the token is never stored, logged or shown. Already signed in: no new login.
     * Every failure is one red line naming the next step.
     */
    private fun ghSignIn(way: Way, status: TextView) {
        val ctx = requireContext()
        val engine = GhEngine(ctx)
        val host = AuthDeclaration.gitChain.firstOrNull { it.id == way.rung }?.config?.optString("host").orEmpty()
        val why = when (val c = engine.check()) {
            is GhEngine.Check.NotInstalled -> getString(R.string.connect_gh_missing, c.pkg)
            is GhEngine.Check.TooOld -> getString(R.string.connect_gh_old, c.pkg, c.found, c.needed)
            GhEngine.Check.Ready -> if (host.isBlank()) getString(R.string.connect_gh_no_host, way.rung) else ""
        }
        if (why.isNotBlank()) { show(status, RED, "✗ $why"); view?.snack(why); return }
        show(status, NEUTRAL, getString(R.string.connect_gh_starting))
        val appCtx = ctx.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            var failure = ""
            val token = withContext(Dispatchers.IO) {
                engine.token(host) ?: run {
                    val r = engine.login(host) { code, page ->
                        view?.post { if (isAdded) ghPrompt(appCtx, status, code, GhEngine.pageOnHost(page, host)) }
                    }
                    when {
                        !r.ok -> { failure = appCtx.getString(R.string.connect_gh_failed, r.output.trim().lines().lastOrNull().orEmpty()); null }
                        else -> engine.token(host) ?: run { failure = appCtx.getString(R.string.connect_gh_no_token, host); null }
                    }
                }
            }
            if (token == null) { show(status, RED, "✗ $failure"); return@launch }
            show(status, NEUTRAL, getString(R.string.connect_fetching, vaultFile()))
            val hint = getString(R.string.connect_pat_auth_hint, AuthDeclaration.configSource.gitRepo)
            when (val o = withContext(Dispatchers.IO) { fetchVaultFileWithToken(token, hint) }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed -> show(status, RED, "✗ ${o.kind}\n${o.message}")
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> landVault(status, o.body, via = way.via)
            }
        }
    }

    /** gh's prompt: the code shown and copied (flagged sensitive), the page opened in cloud-browser. */
    private fun ghPrompt(ctx: android.content.Context, status: TextView, code: String, page: String?) {
        if (code.isNotBlank()) {
            val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.connect_gh_code_clip), code))
        }
        show(status, NEUTRAL, getString(R.string.connect_gh_prompt, code.ifBlank { "…" }, page ?: "…"))
        page ?: return
        val browser = AuthDeclaration.browserMission?.pkg.orEmpty()
        runCatching {
            require(browser.isNotBlank())
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page)).setPackage(browser))
        }.onFailure { show(status, RED, "✗ " + getString(R.string.connect_gh_no_browser, browser.ifBlank { "—" }, page)) }
    }

    /** What [line] holds on this device right now — presence, never a value. */
    private fun lineHeld(ctx: android.content.Context, line: Line): String {
        val configs = ConfigsPrefs(ctx)
        val held = line.ways.flatMap { way ->
            when (way.kind) {
                SignIn.Kind.AUTHELIA_WEB.name.lowercase() ->
                    listOfNotNull(getString(R.string.connect_held_session).takeIf { vaultSession != null })
                SignIn.Kind.AUTHELIA_BEARER.name.lowercase() ->
                    listOfNotNull(configs.autheliaEmail.takeIf { configs.autheliaToken.isNotBlank() }
                        ?.let { getString(R.string.connect_held_bearer, it) })
                KIND_GITHUB_SSH_PAT -> listOfNotNull(
                    getString(R.string.connect_held_github)
                        .takeIf { configs.secret(VaultCockpit.SECTION_GIT, VaultCockpit.K_GITHUB_TOKEN).isNotBlank() },
                    getString(R.string.connect_held_ssh)
                        .takeIf { configs.secret(VaultCockpit.SECTION_SSH, VaultCockpit.K_VAULT_REPO_KEY).isNotBlank() },
                )
                else -> emptyList<String>()
            }
        }
        return if (held.isEmpty()) getString(R.string.connect_line_idle)
               else getString(R.string.connect_line_held, held.joinToString(" · "))
    }

    /** The bearer way's own controls: the stored token (one tap, clearable), an
     *  orphan one to link, and the mailed second factor. They live with the
     *  bearer provider, so a policy that does not offer it does not show them. */
    private fun buildStoredBearer(ctx: android.content.Context, s: ProfileJourney.State, body: LinearLayout, status: TextView) {
        val configs = ConfigsPrefs(ctx)
        if (s.storedBearerEmail.isNotBlank()) {
            body.addView(pickButton(ctx, getString(R.string.journey_use_stored_bearer, s.storedBearerEmail)) {
                show(status, NEUTRAL, "…")
                runFetch(status, { afterLanding() },
                    via = SignIn.byKind(SignIn.Kind.AUTHELIA_BEARER), identity = s.storedBearerEmail) { ConfigArtifact.fetchWithBearer(configs.autheliaToken) }
            })
            body.addView(clearSecretButton(ctx, "Authelia bearer token") { configs.clearAutheliaCredential() })
        } else if (configs.hasOrphanToken()) {
            body.addView(caption(ctx, ORPHAN_TOKEN_TEXT))
            body.addView(pickButton(ctx, "Link the stored token to ${prefs.email.trim()}") {
                val error = configs.adoptOrphanToken(prefs.email.trim())
                if (error != null) view?.snack(error) else { view?.snack("Stored token linked"); redraw() }
            })
        }
        body.addView(pickButton(ctx, getString(R.string.journey_mail_code)) { showMailCodeDialog() })
    }

    /** Step 2: one selectable row per identity; the pick is stored as the address alone. */
    private fun buildWhoStep(
        ctx: android.content.Context, s: ProfileJourney.State, reg: UserRegistry.Registry, body: LinearLayout,
    ) {
        body.addView(caption(ctx, getString(R.string.journey_who_user_line, reg.name.ifBlank { reg.user }, reg.identities.size, reg.peers.size)))
        body.addView(caption(ctx, getString(R.string.journey_who_caption)))
        val highlight = s.chosenIdentity ?: ProfileJourney.defaultIdentity(s)
        for (id in reg.identities) {
            body.addView(ProfileJourneyView.choice(ctx, getString(R.string.journey_identity_row, id.email, identityTag(id)), id.email == highlight?.email) {
                UserRegistry.selectIdentity(ctx, id.email)
                redraw()
            })
        }
    }

    /** Step 3: one selectable row per peer; the pick also points the Fleet cockpit at the peer's vault device. */
    private fun buildDeviceStep(
        ctx: android.content.Context, s: ProfileJourney.State, reg: UserRegistry.Registry, body: LinearLayout,
    ) {
        body.addView(caption(ctx, getString(R.string.journey_device_caption)))
        val highlight = s.chosenPeer ?: ProfileJourney.defaultPeer(s)
        for (p in reg.peers) {
            val text = getString(R.string.journey_device_row, p.label, p.kind.ifBlank { "—" }, p.wgIp.ifBlank { "—" }, profilesText(p))
            body.addView(ProfileJourneyView.choice(ctx, text, p.id == highlight?.id) {
                UserRegistry.selectPeer(ctx, p.id)
                if (p.vaultDevice.isNotBlank()) VaultCockpit.selectDevice(ctx, p.vaultDevice)
                redraw()
            })
        }
    }

    /** Step 4 on Connect: getting is the lines', APPLYING is Setup's (#695) — the step says so and goes there. */
    private fun buildGetStep(ctx: android.content.Context, s: ProfileJourney.State, body: LinearLayout) {
        body.addView(caption(ctx, getString(R.string.journey_get_on_setup, tabLabel(driftTab))))
        body.addView(pickButton(ctx, tabLabel(driftTab)) { pickTab?.invoke(driftTab) })
        // #766 no second Import File here: it is Connect's third line.
    }

    // ── Runtime (#778, replaces Cloud Constellation Setup) ───────────────

    /**
     * #778 RUNTIME, per app: what each fleet app is using right now ([RuntimeTab], Compose) — and
     * nothing below it (#781: the per-peer "Your config" apply and the contact card are deleted;
     * applying is Drift's server → runtime, the card's fields are Profiles' `about` topic).
     */
    private fun renderRuntime(ctx: android.content.Context, into: LinearLayout) {
        into.addView(ctx.kitComposeView(AccountHost.palette(ctx)) { RuntimeTab(AccountModel.get(ctx)) })
    }

    /** CreateDocument for Profiles' and Drift's exports; [pendingExport] is the text the picked file receives. */
    private var pendingExport: String? = null
    private val exportPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = pendingExport; pendingExport = null
            if (uri == null || text == null) return@registerForActivityResult
            val app = requireContext().applicationContext
            kotlin.concurrent.thread(name = "account-export") {
                val ok = runCatching { app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.isSuccess
                view?.post { view?.snack(if (ok) getString(R.string.account_exported, uri.lastPathSegment.orEmpty()) else getString(R.string.account_export_failed)) }
            }
        }

    private fun export(name: String, text: String) { pendingExport = text; exportPicker.launch(name) }

    /** A declared launcher route (the apps topic's Store link) handed to the host. */
    private fun openRoute(route: String) {
        val a = activity ?: return
        if (!AccountHost.route(a, route)) view?.snack(getString(R.string.account_route_unavailable))
    }

    private fun statusView(ctx: android.content.Context): TextView = TextView(ctx).apply {
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setTextIsSelectable(true)
        visibility = View.GONE
        setPadding(0, dp(ctx, 8), 0, 0)
    }

    /** The Authelia mailed identity-validation code: one dialog, the same mechanics as before. */
    private fun showMailCodeDialog() {
        val ctx = requireContext()
        importDialog(
            title = getString(R.string.journey_mail_code_title),
            positive = getString(R.string.journey_mail_code_go),
            buildBody = { body, _ ->
                body.addView(caption(ctx, MAIL_2FA_TEXT))
                body.addView(mailConfirmationField(ctx))
            },
            onGo = { _, _ -> confirmMailCode() },
            onDismiss = { mailCodeField = null },
        ).show()
    }

    // ── tabs ─────────────────────────────────────────────────────────────

    /**
     * The Connect | Infos | Cloud Constellation Setup strip (#695; labels are the declaration's).
     *
     * Plain columns swapped by visibility — no child fragments, no pane
     * host ids, no build.json pages (see the note in [onCreateView]). It reuses
     * libs:bottomnav's [PageTabsView] so the pills read exactly like the launcher's
     * section strips, which is the whole of what that idiom is worth here.
     *
     * EVERY TAB HAS A COLUMN. #614's strip also carried "launch tabs" — a tab
     * with no column that navigated away and handed the selection straight back
     * (Store, WireGuard). #626 removed both: a deep-link is an Infos SECTION
     * now, which is a link that looks like a link instead of a tab that refuses
     * to stay selected.
     *
     * The selection is held on the FRAGMENT, not the view, because this screen
     * redraws itself with detach/attach — which destroys the view and keeps the
     * instance. Without that the user would be thrown back to Setup every time
     * they picked a photo from the Infos tab.
     */
    private data class Tab(val title: String, val column: View)

    private fun tabStrip(
        ctx: android.content.Context,
        tabs: List<Tab>,
    ): PageTabsView {
        fun show(index: Int) {
            tabs.forEachIndexed { i, tab ->
                tab.column.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }
        show(selectedTab)
        return PageTabsView(ctx).apply {
            strip = this
            // The shared pill strip: it owns the chrome AND the sizing (one slot per tab, the font
            // stepping down before any label clips), so there is no host styling hook any more.
            pages = tabs.mapIndexed { i, t -> NavPage(tabIds.getOrNull(i) ?: "tab$i", t.title) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val view = this
            pickTab = pick@{ index ->
                if (tabs.getOrNull(index) == null) return@pick
                selectedTab = index
                view.selectedId = view.pages.getOrNull(index)?.id
                show(index)
                tabIds.getOrNull(index)?.let { onTabShown?.invoke(sectionOf(it)) }
            }
            onSelect = { page -> pickTab?.invoke(view.pages.indexOfFirst { it.id == page.id }) }
            selectedId = pages.getOrNull(selectedTab)?.id
            if (arguments?.getBoolean(ARG_EXTERNAL_STRIP) == true) visibility = View.GONE
            tabIds.getOrNull(selectedTab)?.let { onTabShown?.invoke(sectionOf(it)) }
        }
    }

    // ── mail 2FA confirmation ────────────────────────────────────────────

    /**
     * The one-time code Authelia MAILS, and the one field on this screen that
     * is deliberately not stored anywhere at all.
     *
     * WHAT THIS IS, because guessing wrong here leaks a seed. This fleet's
     * Authelia enables exactly two second factors — `webauthn` (the default)
     * and `totp` — and no `duo_api`. Authelia has no email second factor, so
     * "mail 2FA" cannot be a login factor. What it does have is
     * `notifier.smtp`, pointed at maddy on the mesh, and that transport exists
     * for ONE purpose: the identity-validation message Authelia sends to the
     * account address when a new TOTP or WebAuthn device is enrolled, or a
     * password is reset. So this box takes THAT code.
     *
     * Consequences, both of which are the point:
     *  • it is NOT a TOTP seed, so it must never reach [ConfigsPrefs] — a seed
     *    saved here would be a permanent second factor sitting on the device
     *    next to the bearer it is supposed to be independent of;
     *  • it is a code with minutes of life and one use, so persisting it would
     *    store something already expired. It therefore has NO TextWatcher and
     *    no save lambda: it lives in the view, is cleared the moment it is
     *    used, and is dropped with the view.
     */
    private fun mailConfirmationField(ctx: android.content.Context): EditText =
        EditText(ctx).apply {
            hint = "code from the Authelia email"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            mailCodeField = this
        }

    /** Live handle to the code box. Never read into prefs — only into the
     *  clipboard, and only on an explicit tap. */
    private var mailCodeField: EditText? = null

    /**
     * Hand the code to Authelia's own page, which is the only thing that can
     * check it.
     *
     * The portal is mesh-only and this fleet routes no `/api/secondfactor`
     * anywhere the app could reach, so there is nothing to POST to and no
     * pretending otherwise: the code goes to the clipboard (flagged sensitive
     * where the platform supports it) and the existing browser-login WebView
     * opens on the confirmation page. The box is emptied straight away, so a
     * used code is not left sitting on screen.
     */
    private fun confirmMailCode() {
        val field = mailCodeField ?: return
        val code = field.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            field.error = "Paste the code Authelia emailed you."
            return
        }
        val ctx = requireContext()
        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val item = android.content.ClipData.newPlainText("Authelia confirmation code", code)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            item.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clip.setPrimaryClip(item)
        field.setText("")
        field.error = null
        // The portal login lives in the shared surface now (#587); the vault
        // route's own browser dialog is the same Authelia portal, so the
        // confirmation page opens there.
        showVaultBrowserDialog()
        view?.snack("Code copied — paste it into the Authelia page")
    }

    // ── vault configs ────────────────────────────────────────────────────

    private fun vaultEndpoints() = AuthDeclaration.vault

    /** True when the Authelia line holds a credential the vault route accepts. */
    private fun vaultCredentialHeld(ctx: android.content.Context): Boolean {
        if (vaultSession != null) return true
        return ConfigsPrefs(ctx).autheliaToken.isNotBlank()
    }

    /**
     * #766 THE AUTHELIA LINE'S VAULT LEG — what used to be the "Vault export" block
     * below the lines, now the line's own continuation: opened by itself the moment an
     * Authelia way lands (so connecting fetches the vault configs, as the GitHub and
     * file lines do) and by the line's pill afterwards. Opening mails the one-time code
     * to the signed-in address ([vaultStart]); the code box is never stored (a one-use
     * code with minutes of life) and is emptied once sent; a fetch lands through
     * [landVault] like every line, and the dialog closes on it.
     */
    private fun showVaultFetchDialog(via: String) {
        val ctx = context ?: return
        var codeBox: EditText? = null
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        dialog = importDialog(
            title = getString(R.string.connect_vault_title),
            positive = getString(R.string.connect_vault_go),
            buildBody = { body, status ->
                body.addView(caption(ctx, getString(R.string.connect_vault_caption, tabLabel(profilesTab), tabLabel(driftTab), vaultAuthText(ctx))))
                val box = EditText(ctx).apply {
                    hint = getString(R.string.vault_connect_code_hint)
                    setSingleLine()
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                }
                codeBox = box
                body.addView(box)
                body.addView(pickButton(ctx, getString(R.string.connect_vault_resend)) { vaultStart(status) })
                vaultStart(status)
            },
            onGo = { _, status -> codeBox?.let { vaultFetch(status, it, via) { dialog.dismiss() } } },
            onDismiss = { codeBox = null },
        )
        dialog.show()
    }

    /**
     * A browser session for the vault route, memory only (#570). Set by
     * [showVaultBrowserDialog], dropped with the process; never written to
     * prefs, never shown. When present it wins over the stored bearer, because
     * it is the fresher of the two — "refresh" is signing in again.
     */
    private var vaultSession: String? = null

    /** The credential the vault calls will carry, or null with the reason on screen. */
    private fun vaultAuth(status: TextView): VaultConnect.Auth? {
        vaultSession?.let { return VaultConnect.Auth.Cookie(it) }
        val token = ConfigsPrefs(requireContext()).autheliaToken
        if (token.isNotBlank()) return VaultConnect.Auth.Bearer(token)
        show(status, RED, "✗ " + getString(R.string.vault_connect_no_bearer))
        return null
    }

    private fun vaultAuthText(ctx: android.content.Context): String = when {
        vaultSession != null -> getString(R.string.vault_connect_auth_cookie)
        ConfigsPrefs(ctx).autheliaToken.isNotBlank() ->
            getString(R.string.vault_connect_auth_bearer, ConfigsPrefs(ctx).autheliaEmail)
        else -> getString(R.string.vault_connect_auth_none)
    }

    /**
     * OWebAuth for the vault route: the same WebView login the config import
     * uses, pointed at the vault base URL, and the cookie it earns is kept in
     * [vaultSession] for this process only.
     */
    private fun showVaultBrowserDialog() {
        val ctx = requireContext()
        val e = vaultEndpoints()
        val landing = e.baseUrl.trimEnd('/') + "/" + e.startPath.trimStart('/')
        var web: android.webkit.WebView? = null
        importDialog(
            title = getString(R.string.vault_connect_browser),
            positive = getString(R.string.vault_connect_use_session),
            buildBody = { body, _ ->
                body.addView(caption(ctx, getString(R.string.vault_connect_browser_caption, e.baseUrl)))
                val view = android.webkit.WebView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380),
                    ).apply { topMargin = dp(ctx, 10) }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = android.webkit.WebViewClient()
                }
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                view.loadUrl(landing)
                web = view
                body.addView(view)
            },
            onGo = { _, status ->
                val cookie = android.webkit.CookieManager.getInstance().getCookie(e.baseUrl).orEmpty()
                if (cookie.isBlank()) {
                    show(status, RED, "✗ No cookie for ${e.baseUrl} yet — finish the login above first.")
                } else {
                    vaultSession = cookie
                    importedThisSession = true   // redraw: the auth line changes
                    show(status, GREEN, "✓ " + getString(R.string.vault_connect_auth_cookie))
                }
            },
            onDismiss = { web?.destroy(); web = null },
        ).show()
    }

    private fun vaultStart(status: TextView) {
        val auth = vaultAuth(status) ?: return
        val e = vaultEndpoints()
        show(status, NEUTRAL, "…")
        lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { VaultConnect.start(e, auth) }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    showVaultFailure(status, o)
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok ->
                    show(status, GREEN, getString(R.string.vault_connect_sent))
            }
        }
    }

    private fun vaultFetch(status: TextView, box: EditText, via: String, onLanded: () -> Unit) {
        val auth = vaultAuth(status) ?: return
        val code = box.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            box.error = getString(R.string.vault_connect_no_code)
            return
        }
        box.setText("")
        val e = vaultEndpoints()
        lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { VaultConnect.fetch(e, auth, code) }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    showVaultFailure(status, o)
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok ->
                    if (landVault(status, o.body, redrawNow = false, via = via)) onLanded()
            }
        }
    }

    /**
     * EVERY route that fetched the vault export lands here (#695): the mailed-code
     * fetch, the GitHub token read and the SSH clone. The schema gate first (an
     * unknown version is refused whole), then the ONE in-memory import Infos and
     * Setup read, then Infos. A dialog route passes [redrawNow] false: the page
     * is redrawn when the dialog closes (importDialog's dismiss), never under it.
     */
    private fun landVault(status: TextView, body: org.json.JSONObject, redrawNow: Boolean = true, via: String = ""): Boolean {
        VaultConnect.unknownSchemaVersion(body, VaultConnect.knownSchemaVersions)?.let { v ->
            show(status, RED, "✗ " + getString(R.string.vault_connect_schema_unknown, v,
                VaultConnect.knownSchemaVersions.sorted().joinToString(", ")))
            return false
        }
        val sections = VaultConnect.sections(body)
        VaultConnect.Imported.last = sections
        VaultConnect.Imported.bundle = body.optJSONObject("bundle") ?: body
        VaultConnect.Imported.via = via
        // #766 the vault names the owner's peers too: who / which device answer on every line.
        context?.let { c -> VaultConnect.Imported.bundle?.let(UserRegistry::fromVault)?.let { UserRegistry.adopt(c, it) } }
        // #778 the fetch IS the server file S, stored with its source; L starts as S when there is none.
        context?.let { c -> VaultConnect.Imported.bundle?.let { AccountModel.get(c).landServer(it, via) } }
        failedSteps -= ProfileJourney.Step.SIGN_IN
        show(status, GREEN, getString(
            R.string.vault_connect_fetched, sections.sumOf { it.rows.size }, sections.size, tabLabel(profilesTab)))
        // #766 the next question (which device this is) is Connect's: stay there until it is answered.
        selectedTab = if (context?.let { journeyState(it).chosenPeer } != null) profilesTab else connectTab
        importedThisSession = true
        if (redrawNow && isAdded && !isStateSaved) { importedThisSession = false; redraw() }
        return true
    }

    private fun showVaultFailure(
        status: TextView,
        o: com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed,
    ) {
        val hint = when (VaultConnect.hint(o.kind)) {
            VaultConnect.Hint.CODE_REJECTED -> getString(R.string.vault_connect_code_rejected) + "\n"
            VaultConnect.Hint.SERVER_NOT_READY -> getString(R.string.vault_connect_server_not_ready) + "\n"
            VaultConnect.Hint.NONE -> ""
        }
        show(status, RED, "✗ ${o.kind}\n$hint${o.message}")
    }

    private fun redraw() {
        if (!isAdded) return
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    /** Retry a queued upload whenever this screen comes back — a plausible
     *  moment for connectivity to have returned since the last failure. */
    override fun onResume() {
        super.onResume()
        ProfileSync.flush(requireContext())
    }

    /**
     * Leaving the screen is the send point: fields auto-save as they are typed,
     * so by now prefs hold the finished card and one upload carries all of it.
     * [ProfileSync.push] itself no-ops when the profile is incomplete or no
     * endpoint is configured, and never blocks — it writes the queue and hands
     * off to a background thread.
     */
    override fun onPause() {
        super.onPause()
        ProfileSync.push(requireContext(), prefs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        strip = null
        pickTab = null
        journey = null
        // The mailed code is never stored; it dies with the view that held it.
        mailCodeField = null
    }

    // ── credentials ──────────────────────────────────────────────────────

    /** The only way to remove a stored credential, since a blank box means
     *  "unchanged". Confirmed, because losing the WireGuard private key means
     *  the tunnel cannot be brought up again without re-importing it. */
    private fun clearSecretButton(
        ctx: android.content.Context,
        what: String,
        clear: () -> Unit,
    ): View = pickButton(ctx, "Clear stored $what") {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear $what?")
            .setMessage("It is removed from this device. It is not stored anywhere else, so you will have to paste or re-import it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                clear()
                view?.snack("$what cleared")
                parentFragmentManager.beginTransaction().detach(this).commitNow()
                parentFragmentManager.beginTransaction().attach(this).commitNow()
            }
            .show()
    }

    // ── THE sign-in (libs:auth, #587) ────────────────────────────────────

    /** Set when a sign-in or an import wrote something, so the form above is
     *  redrawn with the new values once the dialog is dismissed. */
    private var importedThisSession = false

    /**
     * What the shared surface hands back. Every way in — the bearer paste, the
     * portal login, a device grant — ends in [landed]; the browser session is
     * also kept for the vault route (one login, both fetches), memory only.
     */
    private val signInHost = object : SignInHost {
        override fun onSignedIn(result: SignInResult) {
            landed(result.provider, result.identity, result.artifact, result.bytes, result.bearer)
            // The lib's dialog has already closed; the journey redraws with the session.
            view?.post { afterLanding() }
        }
        override fun onWebSession(cookie: String) { vaultSession = cookie }
    }

    /**
     * A sign-in landed (#573): this is STEP 1 of the journey. It remembers the
     * artifact (and caches its User → Identity → Peer registry) and records who
     * signed in; it does NOT apply. Applying is step 4, for the peer picked in
     * step 3 — an apply here would write the wrong phone's profiles before the
     * owner had said which phone this is. A bearer that just proved itself is
     * stored WITH the address it proved — the durable sign-in the vault route
     * needs. ONE place, whichever way was taken (the lib's three, the stored
     * bearer, the SSH clone).
     */
    private fun landed(via: SignIn.Provider?, identity: String, artifact: org.json.JSONObject?, bytes: Int, storeBearer: String) {
        val appCtx = requireContext().applicationContext
        if (artifact != null) UserRegistry.remember(appCtx, artifact)
        val who = identity.ifBlank { UserRegistry.Current.registry?.primaryIdentity?.email.orEmpty() }
        via?.let { SignIn.Current.session = SignIn.Session(it.id, who) }
        if (storeBearer.isNotBlank()) {
            ConfigsPrefs(appCtx).setAutheliaCredential(who, storeBearer)?.let { view?.snack(it) }
        }
        failedSteps -= ProfileJourney.Step.SIGN_IN
        if (artifact != null) view?.snack(getString(R.string.journey_fetched_snack))
        importedThisSession = true   // the journey redraws on dialog dismiss
        // #766 an Authelia way also fetches the vault configs: its leg opens next.
        if (via != null && via.kind.name.lowercase() in AUTHELIA_KINDS)
            vaultLegVia = connectLines().firstOrNull { l -> l.ways.any { it.kind == via.kind.name.lowercase() } }?.label ?: via.label
    }

    /** #766 Set by an Authelia landing: the line label the vault leg opens for, once the page is redrawn. */
    private var vaultLegVia: String? = null

    /** After a sign-in landed: redraw with it, then (an Authelia way) continue to the vault leg. */
    private fun afterLanding() {
        if (importedThisSession) { importedThisSession = false; redraw() }
        vaultLegVia?.let { vaultLegVia = null; if (isAdded) showVaultFetchDialog(it) }
    }

    /**
     * Fetch on IO, land on the main thread, report either way — the two ways
     * this fragment still drives itself (the stored bearer's one tap and the
     * SSH clone); the lib's ways land through [signInHost].
     */
    private fun runFetch(
        status: TextView,
        done: () -> Unit,
        via: SignIn.Provider? = null,
        identity: String = "",
        storeBearer: String = "",
        fetch: () -> com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { fetch() }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed -> {
                    failedSteps += ProfileJourney.Step.SIGN_IN
                    show(status, RED, "✗ ${outcome.kind}\n${outcome.message}")
                }
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> {
                    landed(via, identity, outcome.body, outcome.bytes, storeBearer)
                    show(status, GREEN, getString(R.string.journey_fetched, outcome.bytes))
                }
            }
            done()
        }
    }

    // ── shared dialog shell ──────────────────────────────────────────────

    /**
     * The three authenticated dialogs are the same object with a different
     * middle: a scrollable body, a status line, and a positive button that
     * deliberately does NOT dismiss on click — a failed import has to report
     * itself in place, and a dialog that vanishes on tap is how "nothing
     * happened" became the most common bug report on the bearer flow.
     */
    private fun importDialog(
        title: String,
        positive: String,
        buildBody: (LinearLayout, TextView) -> Unit,
        onGo: (go: android.widget.Button, status: TextView) -> Unit,
        onDismiss: () -> Unit = {},
    ): androidx.appcompat.app.AlertDialog {
        val ctx = requireContext()
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 20); setPadding(pad, dp(ctx, 12), pad, 0)
        }
        val status = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 12), 0, 0)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        buildBody(body, status)
        body.addView(status)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton(positive, null)   // wired in setOnShowListener
            .setNegativeButton("Close", null)
            .create()

        // Redraw the form AFTER the dialog closes, never during: detach/attach
        // destroys the fragment view, which would cancel an in-flight import.
        dialog.setOnDismissListener {
            onDismiss()
            if (importedThisSession) {
                importedThisSession = false
                parentFragmentManager.beginTransaction().detach(this).commitNow()
                parentFragmentManager.beginTransaction().attach(this).commitNow()
            }
        }
        dialog.setOnShowListener {
            val go = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener { onGo(go, status) }
        }
        return dialog
    }

    // ── Import File (#711) ───────────────────────────────────────────────

    /** The Connect status line the Import File pick reports into. */
    private var fileStatus: TextView? = null

    /** The Import File way's "<line> · <way>", for the journey's sign-in line. */
    private var fileVia: String = ""

    private val vaultFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            // A dismissed picker is the owner's choice, not a failure.
            uri?.let { importVaultFile(it) }
        }

    /**
     * The Import File line: no second importer. The bytes go through the ONE
     * classifier Configs ▸ Import uses ([ImportConfigsFragment.classify]); the
     * decrypted export lands through [landVault], exactly as a sign-in's fetch
     * does (Infos fills, Setup unlocks, the page moves to Infos). Every other
     * file is refused in red with the reason, and nothing is stored.
     */
    private fun importVaultFile(uri: android.net.Uri) {
        val ctx = context ?: return   // the page is gone; there is nowhere left to land or to say
        // The line's status, or (the page was rebuilt under the picker) a fresh
        // one — a refusal still reaches the screen through the snack.
        val status = fileStatus ?: statusView(ctx)
        fun refuse(text: String) { show(status, RED, text); view?.snack(text) }
        val name = uri.lastPathSegment ?: uri.toString()
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Throwable) {
            refuse(getString(R.string.import_file_error, "${t.javaClass.simpleName}: ${t.message}")); return
        }
        when {
            text == null -> refuse(getString(R.string.import_file_no_stream, name))
            text.isEmpty() -> refuse(getString(R.string.import_file_empty, name))
            else -> when (val v = AccountHost.classify(text)) {
                is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> landVault(status, v.bundle, via = fileVia)
                else -> refuse(AccountHost.refusal(ctx, v).orEmpty())
            }
        }
    }

    // ── GitHub · SSH key ─────────────────────────────────────────────────

    /** Live handle to the SSH key field, so the file picker can fill it. */
    private var sshKeyField: EditText? = null

    private val sshKeyFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val field = sshKeyField ?: return@registerForActivityResult
            runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                field.setText(extractSshKey(text))
            }.onFailure { field.error = "Could not read that file: ${it.message}" }
        }

    /** A raw PEM, or `ssh.vault_repo_key` out of an artifact JSON — the shape
     *  build.json::ui.import_schema already declares, so a previously exported
     *  config can bootstrap the next import. */
    private fun extractSshKey(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val o = org.json.JSONObject(trimmed)
            o.optJSONObject("ssh")?.optString("vault_repo_key").orEmpty()
                .ifBlank { o.optString("vault_repo_key") }
                .ifBlank { trimmed }
                // A JSON string carries \n as an escape; JSch needs real newlines.
                .replace("\\n", "\n")
        }.getOrDefault(trimmed)
    }

    /** #695 the vault export's path inside the ONE declared vault repo (ui.profile.connect.vault_file). */
    private fun vaultFile(): String = connectDecl().optString("vault_file")

    private fun showGithubSshDialog(via: String) {
        val ctx = requireContext()
        val repo = AuthDeclaration.configSource.gitRepo
        val path = vaultFile()
        var passField: EditText? = null

        val dialog = importDialog(
            title = "GitHub · SSH key",
            positive = "Clone & Import",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Paste the private key that has read access to $repo, or import it from a file (a previously exported config works — the key is read from ssh.vault_repo_key).\n\nThis route CLONES the repository: GitHub offers no file-read over SSH, so the whole repo is fetched shallow and bare into the cache and deleted immediately after $path is read. Nothing is checked out. A token (the PAT way) reads only that one file instead."))

                val key = EditText(ctx).apply {
                    hint = "-----BEGIN OPENSSH PRIVATE KEY-----"
                    setSingleLine(false); maxLines = 6
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                sshKeyField = key
                body.addView(key)

                body.addView(pickButton(ctx, "Import key from file…") {
                    sshKeyFilePicker.launch("*/*")
                })

                body.addView(label(ctx, "Passphrase  (leave empty if the key has none)"))
                val pass = EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                passField = pass
                body.addView(pass)
            },
            onGo = { go, status ->
                val key = sshKeyField?.text?.toString()?.trim().orEmpty()
                val pass = passField?.text?.toString().orEmpty()
                if (key.isEmpty()) {
                    show(status, RED, "✗ Paste or import a private key first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… cloning $repo over SSH (shallow, bare)")
                    val cacheDir = requireContext().cacheDir
                    runVaultRead(status, via, { go.isEnabled = true }) {
                        GitSshVault.fetchArtifact(cacheDir, key, pass, path)
                    }
                }
            },
            onDismiss = { sshKeyField = null },
        )
        dialog.show()
    }

    // ── GitHub · token (the PAT half of SSH / PAT, #695) ─────────────────

    /**
     * Paste a GitHub token, read ONE file — the vault export — and land it. The
     * token is this request's credential and nothing more: the box is emptied the
     * moment it is read, it is never stored, logged or shown, and it rides only in
     * a header (never a URL, never argv).
     */
    private fun showGithubPatDialog(via: String) {
        val ctx = requireContext()
        val repo = AuthDeclaration.configSource.gitRepo
        var tokenField: EditText? = null
        importDialog(
            title = getString(R.string.connect_pat_title),
            positive = getString(R.string.connect_pat_go),
            buildBody = { body, _ ->
                body.addView(caption(ctx, getString(R.string.connect_pat_caption, repo, vaultFile())))
                val field = EditText(ctx).apply {
                    hint = getString(R.string.connect_pat_hint)
                    setSingleLine()
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                }
                tokenField = field
                body.addView(field)
            },
            onGo = { go, status ->
                val token = tokenField?.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    show(status, RED, "✗ " + getString(R.string.connect_pat_empty))
                } else {
                    tokenField?.setText("")
                    go.isEnabled = false
                    show(status, NEUTRAL, getString(R.string.connect_fetching, vaultFile()))
                    val hint = getString(R.string.connect_pat_auth_hint, repo)
                    runVaultRead(status, via, { go.isEnabled = true }) { fetchVaultFileWithToken(token, hint) }
                }
            },
            onDismiss = { tokenField = null },
        ).show()
    }

    /**
     * The vault export over GitHub's contents API, raw (the file itself, not a
     * base64 envelope): the URL is the declared `github_contents_url` filled with
     * the ONE declared vault repo and ref. Blocking; call on IO. Through
     * [ConfigSyncClient.request], so a 401/403/404 reads as it does on every route
     * and the token is the redacted secret.
     */
    private fun fetchVaultFileWithToken(token: String, authHint: String): com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome {
        val cs = AuthDeclaration.configSource
        val url = connectDecl().optString("github_contents_url")
            .replace("{repo}", cs.gitRepo).replace("{path}", vaultFile()).replace("{ref}", cs.gitRef)
        return com.diegonmarcos.superapp.core.ConfigSyncClient.request(
            url = url,
            headers = mapOf("Authorization" to "Bearer $token", "X-GitHub-Api-Version" to "2022-11-28"),
            secret = token,
            authHint = authHint,
            connectTimeoutMs = cs.connectTimeoutMs,
            readTimeoutMs = cs.readTimeoutMs,
            accept = "application/vnd.github.raw",
        )
    }

    /** Fetch the vault export on IO and land it through [landVault]; the redraw waits for the dialog. */
    private fun runVaultRead(
        status: TextView,
        via: String,
        done: () -> Unit,
        fetch: () -> com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { fetch() }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    show(status, RED, "✗ ${o.kind}\n${o.message}")
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> {
                    landVault(status, o.body, redrawNow = false, via = via)
                }
            }
            done()
        }
    }

    /** One row of import tiles. */
    private fun importRow(ctx: android.content.Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 6) }
        }

    private fun show(status: TextView, color: Int, text: String) {
        status.visibility = View.VISIBLE
        status.setTextColor(color)
        status.text = text
    }

    /** Every action button on this page is the cockpit's pill — one shape, the
     *  palette's accent, so Connect and Infos read as the same screen as Fleet
     *  and a theme change restyles all three at once. */
    private fun pickButton(ctx: android.content.Context, currentLabel: String, onClick: () -> Unit): View =
        FleetCockpitView.pill(ctx, currentLabel, onClick)

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            alpha = 0.85f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Import-status colours. GREEN is the "authenticated + applied" state the
     *  auto-import is required to show explicitly.
     *
     *  Taken from [com.diegonmarcos.superapp.ui.StatusLight] rather than
     *  restated: this page and Configs ▸ Panel ▸ Control both tell the owner
     *  whether something worked, and two literals for that are two things that
     *  can be edited apart into two different greens meaning one thing.
     *
     *  Resolved per instance, not in the companion, because the values are
     *  colour RESOURCES now — a theme is allowed to change what healthy looks
     *  like, and a constant folded in at compile time could not follow it. */
    private val GREEN: Int by lazy {
        com.diegonmarcos.superapp.ui.StatusLight.colour(
            requireContext(), com.diegonmarcos.superapp.ui.StatusLight.State.ON)
    }
    private val RED: Int by lazy {
        com.diegonmarcos.superapp.ui.StatusLight.colour(
            requireContext(), com.diegonmarcos.superapp.ui.StatusLight.State.OFF)
    }
    /** "Nobody can currently say" — the shared light's own grey, not a literal
     *  that used to sit here and could drift from it. */
    private val NEUTRAL: Int by lazy {
        com.diegonmarcos.superapp.ui.StatusLight.colour(
            requireContext(), com.diegonmarcos.superapp.ui.StatusLight.State.UNKNOWN)
    }

    companion object {

        /** #873 a page of Cloud Account's, not a section: ui.sections[runtime].pages[fleetsetup]. [selectTab] speaks it; [onTabShown] reports its section. */
        const val PAGE_FLEETSETUP = "fleetsetup"
        private val PAGE_SECTION = mapOf(PAGE_FLEETSETUP to "runtime")
        private fun sectionOf(id: String) = PAGE_SECTION[id] ?: id

        /** #868 Fragment argument (Boolean): the host draws the tabs, so the fragment hides its own strip. */
        const val ARG_EXTERNAL_STRIP = "external_strip"

        /** The one Connect way kind this page implements itself (build.json::
         *  ui.profile.connect): read the vault export out of the vault repo with
         *  an SSH key or a pasted token. Every other kind is libs:auth's, matched
         *  generically, or has no handler and says why. Named once, so a typo in
         *  the blob is a way that says it is not wired, not a silent one. */
        private const val KIND_GITHUB_SSH_PAT = "github_ssh_pat"

        /** The Import File line's kind (#711): pick the decrypted vault export
         *  from a file instead of signing in. Named once, like the one above. */
        private const val KIND_VAULT_FILE = "vault_file"

        /** #713 GitHub ▸ WebAuth: gh's own `auth login`, run by the gh engine
         *  ([GhEngine], build.json::engines.gh). Named once, like the two above. */
        private const val KIND_GH_AUTH_LOGIN = "gh_auth_login"

        /** #766 libs:auth's two Authelia kinds, as a way declares them: the vault route's credential. */
        private val AUTHELIA_KINDS = setOf(SignIn.Kind.AUTHELIA_WEB.name.lowercase(), SignIn.Kind.AUTHELIA_BEARER.name.lowercase())

        /**
         * What the third box takes, stated in full because the wrong answer is
         * a permanently stored second factor.
         */
        private const val MAIL_2FA_TEXT =
            "This is the one-time code Authelia EMAILS you — the message it sends to " +
            "your account address when you enrol a new second factor or reset your " +
            "password. It is NOT your authenticator's secret, and nothing on this " +
            "screen will ever ask you for one.\n\n" +
            "It is not saved. The code is good for a few minutes and one use, so " +
            "storing it would only keep something already expired. It is copied to " +
            "the clipboard, the Authelia page opens for you to paste it into, and " +
            "the box is emptied — nothing reaches this device's storage and nothing " +
            "reaches the sync document."

        private const val ORPHAN_TOKEN_TEXT =
            "A bearer token is stored on this device with no account email, so it " +
            "is not being used. If it belongs to the address on your profile, link " +
            "it. Nothing was changed or deleted."

        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
