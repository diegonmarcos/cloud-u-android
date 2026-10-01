package com.diegonmarcos.superapp.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.AppTabsStyle
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.ui.snack
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs → ACCOUNT (#695) — exactly THREE tabs, declared {id, label} in
 * build.json::ui.profile.tabs; this file maps an id to its column and names no
 * label, order or membership of its own.
 *
 *  • CONNECT — the #573 journey, whose step 1 is the declared sign-in LINES
 *    (ui.profile.connect: Authelia → Gitea = WebAuth | Bearer, GitHub = WebAuth |
 *    SSH / PAT, Import File), every way one pill of the same design, dispatched on
 *    its `kind` alone; the Authelia ways host the SHARED libs:auth SignInWays, and
 *    GitHub WebAuth is gh's own sign-in in the gh engine ([GhEngine], #713). Then
 *    the VaultConnect fetch ([renderVault]) — and nothing after it (#713).
 *  • INFOS — the SCHEMA the vault JSON fills ([renderInfos]): one card per
 *    declared section, every declared field filled (through the mask, [InfoMask])
 *    or `empty`; nothing on it applies anything.
 *  • SETUP ("Cloud Constellation Setup") — applies that config APP BY APP
 *    ([renderSetup]): the Fleet Setup index (one row per mapped app, then the
 *    #622 wizard's steps), the per-peer config Apply, the device pick on the hero,
 *    one section per app the cockpit declaration maps (every item applied / not
 *    applied / why, and that app's Apply), the contact card and the repos.
 *
 * RESTRUCTURED, NOT REBUILT: #626's Setup | Infos pieces all moved — journey +
 * fetch to Connect, cockpit + person + repos + wizard to Setup, tokens to Connect
 * — and the hand-listed Infos sections became the bundle itself.
 *
 * The contact card is bound to [ProfilePrefs] and auto-saves on every text
 * change (no explicit Save button) — the drawer header reads from the same
 * prefs on every open, so changes are visible immediately next time the
 * drawer slides in.
 *
 * MANDATORY NAME + EMAIL, enforced in three places that escalate rather than
 * block. The app stays completely usable if someone declines to fill them in;
 * what is not allowed is for the omission to be INVISIBLE, because a silently
 * blank contact record is indistinguishable from a working one right up until
 * the day the fleet needs it:
 *   1. inline — the field shows its own error while it is unacceptable;
 *   2. persistent — [statusBanner] sits at the top of the screen and states
 *      whether the profile is complete, and stays there until it is;
 *   3. sync gate — [ProfileSync.push] refuses to upload an incomplete profile,
 *      so a half-filled record never overwrites a good one on the server.
 * No dialog, no interstitial, nothing to dismiss and nothing gated behind it.
 *
 * PERSONAL DATA IS DISCLOSED IN PLACE. The "What is stored and where" section
 * below lists exactly which fields leave the device and offers the erase
 * action, so the answer to "what do you have on me, and take it down" is on
 * the same screen that collects it rather than in a policy nobody opens.
 */
class ProfileFragment : Fragment() {

    private lateinit var prefs: ProfilePrefs

    /** Live handle to the completeness banner so every field's TextWatcher can
     *  refresh it without rebuilding the form (which would drop focus). */
    private var statusBanner: TextView? = null

    /** Which tab is showing. Held on the fragment so the many detach/attach
     *  redraws below do not bounce the user off the tab they were on. Negative
     *  until the strip is first built. */
    private var selectedTab = -1

    /** Position of the Connect tab — the sign-in, the fetch and the device pick. */
    private var connectTab = 0

    /** Position of the Infos tab, read off the strip's own list. */
    private var infosTab = 0

    /** Position of the Setup tab — where the fetched config is applied. */
    private var setupTab = 0

    /** The declared tab labels, in strip order — so a pointer to a tab names it in the declaration's words. */
    private var tabLabels: List<String> = emptyList()

    private fun tabLabel(index: Int): String = tabLabels.getOrNull(index).orEmpty()

    /** The strip itself, so the cockpit can send the owner to Connect. */
    private var strip: TabLayout? = null

    /** The cockpit hero, repainted whenever a card's light changes. */
    private var heroViews: FleetCockpitView.Hero? = null

    /** The page's scroll, so a Fleet Setup index row can bring its app's section into view. */
    private var pageScroll: ScrollView? = null

    /** #713 The Fleet Setup index: one row per app, by cockpit section id, and the card each opens. */
    private val indexRows = linkedMapOf<String, TextView>()
    private val indexCards = mutableMapOf<String, FleetCockpitView.Card>()

    /** Every card's light by section id — what the hero's overall light sums. */
    private val cardStates = linkedMapOf<String, StatusLight.State>()

    /** Gallery picker for the profile photo (round avatar). */
    private val picturePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = false) }
        }
    /** Gallery picker for the cover/banner photo (wide). */
    private val bannerPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = true) }
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
        // pill CHROME is shared, through [AppTabsStyle]. Nothing here touches
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
        pageScroll = scroll

        // THREE content columns (#695), one per declared tab id; `col` is the
        // Infos read-out.
        val connect = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val setup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        page.addView(connect)
        page.addView(col)
        page.addView(setup)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // THE STRIP IS DATA (#614; three tabs since #695): build.json::ui.profile.tabs
        // (UI_PROFILE_TABS_B64) lists {id, label} per tab in render order, and this
        // map is only the id → column lookup. The LABEL comes off the declaration;
        // an id with no column here draws no tab rather than an invented one.
        val columns = mapOf("connect" to connect, "infos" to col, "setup" to setup)
        val tabs = profileTabs().mapNotNull { t -> columns[t.id]?.let { Tab(t.label, it) } }
        connectTab = tabs.indexOfFirst { it.column === connect }
        infosTab = tabs.indexOfFirst { it.column === col }
        setupTab = tabs.indexOfFirst { it.column === setup }
        tabLabels = tabs.map { it.title }
        // The page opens on CONNECT until the journey is walked once (#573):
        // Infos and Setup have nothing to show before the vault is fetched.
        // Once walked, the read-out is the page again.
        if (selectedTab < 0) {
            selectedTab = if (VaultConnect.Imported.bundle == null && !ProfileJourney.allDone(journeyState(ctx))) connectTab else infosTab
        }
        root.addView(tabStrip(ctx, tabs))
        root.addView(scroll)

        // ── CONNECT: sign in, fetch, say which machine this is ────────────
        // #713 NOTHING renders below the vault export: the device pick moved to
        // the Setup hero (the only place that applies for it, and where the
        // journey's peer pick already points it) and the credentials read-out
        // repeated what each sign-in line already says it holds.
        renderJourney(ctx, connect)
        renderVault(ctx, connect)

        // ── INFOS: the fetched vault configs, section by section ──────────
        renderInfos(ctx, col)

        // ── SETUP: apply that config for the picked machine ───────────────
        renderSetup(ctx, setup)

        return root
    }

    /**
     * The contact card on this device — its photos, the privacy disclosure and
     * the erase action — unchanged since the old Infos tab; on Setup since #695,
     * right under the `about` cockpit card that applies the vault's copy to it.
     */
    private fun renderPerson(ctx: android.content.Context, col: LinearLayout) {
        col.addView(caption(ctx, "Edit your contact card — auto-saved on change. Your initials in the drawer are derived from your name; the rest powers the Virtual Business Card."))

        // Persistent completeness banner — enforcement step 2. Added first so
        // it is the first thing read, and never removed while incomplete.
        val banner = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        statusBanner = banner
        col.addView(banner)
        refreshStatus()

        // ── Required ─────────────────────────────────────────────────────
        // Name and email are what make a person reachable when the app itself
        // can no longer be updated, which is the entire reason this screen
        // syncs anywhere. Everything below them is optional.
        col.addView(label(ctx, "Name  *required"))
        col.addView(requiredField(ctx, prefs.name, { prefs.name = it }) { prefs.nameError })

        col.addView(label(ctx, "Email  *required"))
        col.addView(requiredField(ctx, prefs.email, { prefs.email = it }) { prefs.emailError }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })

        col.addView(label(ctx, "Phone"))
        col.addView(field(ctx, prefs.phone) { prefs.phone = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        })

        col.addView(label(ctx, "Date of birth  (YYYY-MM-DD)"))
        col.addView(field(ctx, prefs.birth) { prefs.birth = it }.apply {
            hint = "1990-04-23"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or
                android.text.InputType.TYPE_DATETIME_VARIATION_DATE
            // Advisory WHILE TYPING — a wrong-looking date is flagged, never
            // rejected, and never blocks saving. The field is optional.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty().trim()
                    error = when {
                        text.isEmpty() || DATE_PATTERN.matches(text) -> null
                        // Name the correction instead of only the rule.
                        else -> isoFromDmy(text)?.let { "Use YYYY-MM-DD — saving as $it" }
                            ?: DMY_PATTERN.matchEntire(text)?.let {
                                val (d, m, y) = it.destructured
                                "Use YYYY-MM-DD — did you mean $y-$m-$d?"
                            } ?: "Use YYYY-MM-DD"
                    }
                }
            })
            // Committing happens on BLUR, which is this form's save: there is
            // no Save button, every field persists per keystroke, and leaving
            // the box is the moment the user is done with it. Rewriting on
            // each keystroke would reorder a date under a finger still typing
            // it.
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) return@setOnFocusChangeListener
                val typed = (v as EditText).text?.toString().orEmpty().trim()
                val iso = isoFromDmy(typed) ?: return@setOnFocusChangeListener
                // Rewrite the BOX, not just the stored value. The old code
                // refused to convert because "a date silently reordered under
                // the user is worse than a wrong one they can see" — the
                // silence was the problem, not the reordering, so the field
                // visibly becomes what was stored and says so.
                v.setText(iso)
                v.setSelection(iso.length)
                prefs.birth = iso
                v.error = null
                view?.snack("Date of birth saved as $iso (was $typed)")
            }
        })

        col.addView(label(ctx, "About"))
        col.addView(field(ctx, prefs.titles) { prefs.titles = trimSeparators(it) }.apply {
            isSingleLine = false; maxLines = 4
        })

        col.addView(label(ctx, "Company"))
        col.addView(field(ctx, prefs.company) { prefs.company = it })

        col.addView(label(ctx, "Location"))
        col.addView(field(ctx, prefs.location) { prefs.location = it })

        col.addView(label(ctx, "Website"))
        col.addView(field(ctx, prefs.website) { prefs.website = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        })

        col.addView(label(ctx, "Profile picture"))
        col.addView(pickButton(ctx, prefs.pictureUri.ifBlank { "Pick from gallery…" }) {
            picturePicker.launch("image/*")
        })

        col.addView(label(ctx, "Banner photo"))
        col.addView(pickButton(ctx, prefs.bannerUri.ifBlank { "Pick from gallery…" }) {
            bannerPicker.launch("image/*")
        })

        // ── Privacy ──────────────────────────────────────────────────────
        // Disclosure lives on the collecting screen on purpose: "what is held
        // about me and how do I get rid of it" should not require finding a
        // separate policy page.
        col.addView(sectionHeader(ctx, "What is stored and where"))
        col.addView(caption(ctx, PRIVACY_TEXT))
        col.addView(syncStateView(ctx))
        col.addView(actionTile(ctx, "Erase my profile (device + server)", 0xFFB91C1C.toInt()) {
            confirmErase()
        })
    }

    // ── Infos · THE FETCHED VAULT CONFIGS (#695) ──────────────────────────

    /**
     * THE READ-OUT is the SCHEMA the vault JSON fills (#713): one card per
     * section the vault declares (build.json::ui.profile.infos.schema, the
     * vault's schema.json sections + each section's sources.json fields), each
     * tagged `infos:<section id>`, and in it EVERY declared field — its value
     * through the mask once the fetched bundle fills it, `empty` when it does
     * not ([InfoMask.schemaRows]). A section or key the bundle carries beyond the
     * declaration is drawn too, after it, so the schema never hides data. No
     * section, field or label is written here.
     *
     * SECRETS NEVER REACH A VIEW: a masked row carries only its length (see
     * [InfoMask]); what may be drawn is build.json::ui.profile.infos.mask.
     * DISPLAY ONLY — applying is Setup's, per item.
     */
    private fun renderInfos(ctx: android.content.Context, into: LinearLayout) {
        val sections = VaultConnect.Imported.last
        val bundle = VaultConnect.Imported.bundle
        val schema = InfoMask.schema
        if (sections == null || bundle == null) {
            into.addView(caption(ctx, getString(R.string.infos_empty, tabLabel(connectTab))))
            into.addView(pickButton(ctx, tabLabel(connectTab)) { strip?.getTabAt(connectTab)?.select() })
        } else {
            into.addView(caption(ctx, getString(R.string.infos_caption, sections.size, tabLabel(setupTab))))
        }
        val declared = schema.map { it.id }.toSet()
        val all = schema + sections.orEmpty().filter { it.id !in declared }
            .map { InfoMask.SchemaSection(it.id, it.label, emptyList()) }
        val mask = InfoMask.declared
        for (section in all) {
            val rows = mask.schemaRows(section, bundle?.opt(section.id))
            val empty = rows.count { it.kind == InfoMask.Kind.EMPTY }
            val card = FleetCockpitView.card(ctx, section.label, "infos:${section.id}",
                Sections.iconResFor(ctx, ""), getString(R.string.vault_cockpit_card_toggle))
            FleetCockpitView.paint(card.light,
                if (rows.isEmpty() || empty == rows.size) StatusLight.State.UNKNOWN else StatusLight.State.ON, section.label)
            card.summary.text = getString(R.string.infos_schema_summary, section.fields.size, rows.size - empty, empty,
                rows.count { it.kind == InfoMask.Kind.MASKED })
            for (row in rows) {
                card.body.addView(label(ctx, row.path.ifBlank { section.id }))
                card.body.addView(infoValue(ctx, row))
            }
            into.addView(card.root)
        }
    }

    /** One read-out value. A MASKED row has no text to show — only its length. */
    private fun infoValue(ctx: android.content.Context, row: InfoMask.Row): TextView =
        TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            when (row.kind) {
                InfoMask.Kind.MASKED -> { text = getString(R.string.infos_row_masked, row.size); setTextColor(NEUTRAL) }
                InfoMask.Kind.COLLAPSED -> { text = getString(R.string.infos_row_collapsed, row.size); setTextColor(NEUTRAL) }
                InfoMask.Kind.PENDING -> { text = getString(R.string.infos_row_pending, row.text); setTextColor(NEUTRAL) }
                InfoMask.Kind.EMPTY -> { text = getString(R.string.infos_row_empty); setTextColor(NEUTRAL) }
                InfoMask.Kind.SHOWN -> {
                    setTextIsSelectable(true)
                    val short = if (row.text.length > IMPORTED_PREVIEW_CHARS)
                        row.text.take(IMPORTED_PREVIEW_CHARS) + "… (+${row.text.length - IMPORTED_PREVIEW_CHARS})"
                    else row.text
                    text = short
                    if (short != row.text) {
                        var full = false
                        setOnClickListener { full = !full; text = if (full) row.text else short }
                    }
                }
            }
        }

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
            Sections.iconResFor(ctx, layout.journeyIcons[step.name.lowercase()].orEmpty())
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
    private data class Way(val id: String, val label: String, val kind: String, val note: String, val rung: String = "")

    /** One line of ways, side by side; [note] is what the line cannot do yet. */
    private data class Line(val id: String, val label: String, val note: String, val ways: List<Way>)

    /** build.json::ui.profile.connect (UI_PROFILE_CONNECT_B64); a broken bake is no JSON. */
    private fun connectDecl(): org.json.JSONObject = runCatching {
        org.json.JSONObject(String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_PROFILE_CONNECT_B64, android.util.Base64.NO_WRAP)))
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
                    Way(x.getString("id"), x.optString("label", x.getString("id")), x.optString("kind"), x.optString("note"), x.optString("rung"))
                })
        }
    }.getOrDefault(emptyList())

    /**
     * Step 1: the declared LINES, one row of ways each, side by side — every way
     * drawn by [buildWay] from its kind, never from its label. Below each row,
     * what the line holds now and what it cannot do yet (its declared note).
     */
    private fun buildSignInStep(ctx: android.content.Context, s: ProfileJourney.State, body: LinearLayout) {
        body.addView(caption(ctx, getString(R.string.connect_caption, tabLabel(infosTab), tabLabel(setupTab))))
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
                        if (which == 0) showGithubSshDialog() else showGithubPatDialog()
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
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> landVault(status, o.body)
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
                runFetch(status, { if (importedThisSession) { importedThisSession = false; redraw() } },
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

    /**
     * Step 4 on Connect: getting is here, APPLYING is Setup's (#695) — the step
     * says so and goes there; then the manual file route, the one entry that
     * needs no credential.
     */
    private fun buildGetStep(ctx: android.content.Context, s: ProfileJourney.State, body: LinearLayout) {
        body.addView(caption(ctx, getString(R.string.journey_get_on_setup, tabLabel(setupTab))))
        body.addView(pickButton(ctx, tabLabel(setupTab)) { strip?.getTabAt(setupTab)?.select() })
        // #585: the file must be the DECRYPTED export. Said here, before the tap,
        // because the encrypted repo file is what the owner has at hand.
        body.addView(caption(ctx, getString(R.string.journey_import_file_caption)))
        body.addView(pickButton(ctx, getString(R.string.journey_import_file)) {
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked("action:import_configs")
        })
    }

    /**
     * Setup · the per-peer CONFIG (ex journey step 4, #573): what the fetched
     * artifact carries for the peer picked on Connect, and the ONE Apply on this
     * page (the chosen peer's profiles are what ConfigAutoImport writes). With no
     * artifact or no pick it says which is missing instead of an Apply.
     */
    private fun renderConfigApply(ctx: android.content.Context, into: LinearLayout) {
        into.addView(sectionHeader(ctx, getString(R.string.setup_config_header)))
        val artifact = UserRegistry.Current.artifact
        val peer = journeyState(ctx).chosenPeer
        if (artifact == null || peer == null) {
            into.addView(caption(ctx, if (artifact == null) getString(R.string.setup_config_none, tabLabel(connectTab))
                                   else getString(R.string.journey_lock_pick_peer)))
            return
        }
        into.addView(caption(ctx, getString(R.string.journey_get_caption, peer.label)))
        for (section in ConfigAutoImport.SECTIONS) {
            if (section == "wireguard")
                into.addView(caption(ctx, getString(R.string.journey_get_wireguard, UserRegistry.peerProfiles(artifact, peer.id).size, peer.label)))
            else
                into.addView(caption(ctx, getString(if (artifact.has(section)) R.string.journey_get_line_present else R.string.journey_get_line_absent, section)))
        }
        val status = statusView(ctx)
        into.addView(pickButton(ctx, getString(R.string.journey_apply)) {
            val report = ConfigAutoImport.apply(ctx.applicationContext, artifact)
            if (report.ok) { failedSteps -= ProfileJourney.Step.GET; UserRegistry.markApplied(ctx, stamp()) }
            else failedSteps += ProfileJourney.Step.GET
            show(status, if (report.ok) GREEN else RED, report.text())
            paintJourney()
        })
        into.addView(status)
    }

    // ── Connect · the VaultConnect fetch surface (#614; on Connect since #695) ─

    /**
     * The VaultConnect fetch surface, directly below the sign-in journey, which
     * is what earns the credential it spends. The credential is the durable
     * bearer or the in-memory browser session; the WebOAuth browser login earns
     * the session, the code box takes the mailed one-time code, and a successful
     * fetch lands on Infos. Reuses the same [vaultStart]/[vaultFetch]/[VaultConnect]
     * the journey used — no second client. RENDERING WRITES NOTHING; every call
     * is behind a button.
     */
    private fun renderVault(ctx: android.content.Context, into: LinearLayout) {
        into.addView(sectionHeader(ctx, getString(R.string.journey_vault_header)))
        into.addView(caption(ctx, getString(R.string.vault_connect_auth_state, vaultAuthText(ctx))))
        val status = TextView(ctx).apply { visibility = View.GONE; setTextIsSelectable(true) }
        into.addView(pickButton(ctx, getString(R.string.vault_connect_browser)) { showVaultBrowserDialog() })
        into.addView(pickButton(ctx, getString(R.string.vault_connect_send_code)) { vaultStart(status) })
        into.addView(vaultCodeField(ctx))
        into.addView(pickButton(ctx, getString(R.string.journey_vault_fetch_open)) { vaultFetch(status) })
        into.addView(status)
    }

    // ── Setup · CLOUD CONSTELLATION SETUP (#695) ─────────────────────────

    /**
     * Applies the fetched config for the machine picked on its hero, APP BY APP
     * (#713): first the Fleet Setup INDEX — one row per app the cockpit maps
     * ([renderFleetIndex]), each with its live status, then the wizard's steps —
     * then the per-peer config ([renderConfigApply]), then one section per app
     * ([renderImported] — what the device holds against what the JSON will apply,
     * every item applied / not applied / why, and that app's own Apply), the
     * contact card the `about` section applies to, and the declared repos.
     */
    private fun renderSetup(ctx: android.content.Context, into: LinearLayout) {
        renderFleetIndex(ctx, into)
        renderWizard(ctx, into)
        renderConfigApply(ctx, into)
        renderImported(ctx, into)
        into.addView(sectionHeader(ctx, getString(R.string.setup_person_header)))
        renderPerson(ctx, into)
        into.addView(sectionHeader(ctx, getString(R.string.profile_repos_header)))
        renderRepos(ctx, into)
    }

    /**
     * #713 THE FLEET SETUP INDEX — the first table on Setup: one row per app the
     * cockpit declaration maps (build.json::ui.vault_connect.cockpit.sections,
     * tagged `setup-index:<id>`), in its order. A row starts as "nothing fetched"
     * or "reading"; [paintCard] rewrites it with that app's own light and tally
     * the moment its section is drawn, so the index can never claim more than the
     * section below it shows. Tapping a row opens that section and scrolls to it.
     */
    private fun renderFleetIndex(ctx: android.content.Context, into: LinearLayout) {
        into.addView(sectionHeader(ctx, getString(R.string.setup_index_header)))
        into.addView(caption(ctx, getString(R.string.setup_index_caption)))
        indexRows.clear()
        indexCards.clear()
        val waiting = if (VaultConnect.Imported.bundle == null) getString(R.string.setup_index_unfetched, tabLabel(connectTab))
                      else getString(R.string.setup_index_reading)
        for (section in VaultCockpit.layout.sections) {
            val row = TextView(ctx).apply {
                tag = "setup-index:${section.id}"
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                setTextColor(NEUTRAL)
                text = getString(R.string.setup_index_row, section.label, waiting)
                setOnClickListener { openIndexed(section.id) }
            }
            indexRows[section.id] = row
            into.addView(row)
        }
    }

    /** Open [id]'s section and bring it into view. */
    private fun openIndexed(id: String) {
        val card = indexCards[id] ?: return
        card.body.visibility = View.VISIBLE
        pageScroll?.post { pageScroll?.smoothScrollTo(0, (card.root.parent as? View)?.top?.plus(card.root.top) ?: card.root.top) }
    }

    // ── Setup ▸ repos · the owner's repositories (#614) ───────────────────

    /**
     * The owner's repositories (the Repos tab until #626, verbatim), DATA-DRIVEN
     * from build.json::ui.profile_default.repos (UI_PROFILE_REPOS_B64) — the same
     * declared set Configs ▸ About surfaces. The signed-in GitHub grant (scope
     * `repo`) is what a live listing would reuse, but the declared set is the
     * source of truth and needs no network to show. Each row opens the repo.
     */
    private fun renderRepos(ctx: android.content.Context, into: LinearLayout) {
        into.addView(caption(ctx, getString(R.string.profile_repos_caption)))
        val repos = profileRepos()
        if (repos.isEmpty()) { into.addView(caption(ctx, getString(R.string.profile_repos_empty))); return }
        for ((name, url) in repos) {
            into.addView(label(ctx, name))
            into.addView(pickButton(ctx, url) {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }.onFailure { view?.snack("Could not open $url") }
            })
        }
    }

    // ── Setup · THE FLEET WIZARD (#622) ───────────────────────────────────

    /**
     * The wizard, rendered at the BOTTOM of the Setup tab (#695): the ordered,
     * resumable fleet-configuration flow that is also the account centre. The steps, their order and each step's done-check + route
     * are DATA ([Wizard.steps], from build.json::ui.profile.wizard). Each row
     * shows a REAL, live-measured done light ([Wizard.done] — never a stored
     * flag, #452) and, tapped, DELEGATES to the surface that step configures
     * (another tab of this strip, or a launcher route). Re-entering never breaks
     * a completed step; a step undone elsewhere is pending again on reopen.
     */
    private fun renderWizard(ctx: android.content.Context, into: LinearLayout) {
        val steps = Wizard.steps()
        if (steps.isEmpty()) return
        into.addView(caption(ctx, getString(R.string.setup_wizard_caption)))
        for (step in steps) {
            val done = Wizard.done(ctx, step.check)
            into.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                tag = "wizard:${step.id}"
                isClickable = step.route.isNotBlank()
                if (step.route.isNotBlank()) setOnClickListener { openWizardRoute(step.route) }
                addView(TextView(ctx).apply {
                    text = if (done) "✓" else "○"
                    setTextColor(if (done) 0xFF48BB78.toInt() else 0x99FFFFFF.toInt())
                    textSize = 16f; setPadding(0, 0, dp(ctx, 12), 0)
                })
                addView(TextView(ctx).apply {
                    text = step.label
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }
    }

    /** Delegate a DECLARED route to its surface: a `tab:<id>` selects that tab of
     *  this strip; anything else is a launcher route handed to the host. Used by
     *  the wizard's steps and, since #626, by the Infos link sections — one
     *  dispatcher, so a `tab:`/`page:`/`section:` target means the same thing
     *  wherever it is declared. */
    private fun openWizardRoute(route: String) {
        if (route.startsWith("tab:")) {
            val idx = profileTabOrder().indexOf(route.removePrefix("tab:"))
            if (idx >= 0) strip?.getTabAt(idx)?.select()
            return
        }
        (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
            ?.onTileClicked(route)
    }

    /** Repo {label,url} list — data-driven from build.json::ui.profile_default.repos. */
    private fun profileRepos(): List<Pair<String, String>> = runCatching {
        val json = String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_PROFILE_REPOS_B64, android.util.Base64.NO_WRAP))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it); o.optString("label").ifBlank { "repo" } to o.optString("url")
        }
    }.getOrDefault(emptyList())

    /** One declared tab: [id] selects the column, [label] is the only word drawn. */
    private data class TabDecl(val id: String, val label: String)

    /** #614/#695 the tab strip is data: build.json::ui.profile.tabs (UI_PROFILE_TABS_B64),
     *  one {id, label} per tab in render order. An unparseable blob yields the empty
     *  list, so a broken bake shows no strip rather than an invented one. */
    private fun profileTabs(): List<TabDecl> = runCatching {
        val json = String(android.util.Base64.decode(
            com.diegonmarcos.superapp.BuildConfig.UI_PROFILE_TABS_B64, android.util.Base64.NO_WRAP))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TabDecl(o.getString("id"), o.optString("label", o.getString("id")))
        }
    }.getOrDefault(emptyList())

    /** The declared tab ids, in order — what a `tab:<id>` route resolves against. */
    private fun profileTabOrder(): List<String> = profileTabs().map { it.id }

    private fun statusView(ctx: android.content.Context): TextView = TextView(ctx).apply {
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setTextIsSelectable(true)
        visibility = View.GONE
        setPadding(0, dp(ctx, 8), 0, 0)
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())

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
     * [AppTabsStyle] so the pills read exactly like the launcher's section
     * strips, which is the whole of what that idiom is worth here.
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
    ): TabLayout {
        fun show(index: Int) {
            tabs.forEachIndexed { i, tab ->
                tab.column.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }
        show(selectedTab)
        return TabLayout(ctx).apply {
            strip = this
            tabs.forEach { addTab(newTab().setText(it.title)) }
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    tabs.getOrNull(tab.position) ?: return
                    selectedTab = tab.position
                    show(tab.position)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            // Styled AFTER the tabs exist — the helper builds a pill per tab.
            // BOTH halves: apply() paints the chrome, equalise() MEASURES it.
            // Calling only the first is what left this strip ragged while the
            // launcher's sections looked right — the sizing pass used to be a
            // private method of SectionTabsFragment, so nothing else could
            // reach it. It is the real work: one slot per tab, the font
            // stepping down before any label is allowed to clip.
            AppTabsStyle.apply(this)
            AppTabsStyle.equalise(this)
            getTabAt(selectedTab)?.select()
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

    /** The vault code box. Like [mailConfirmationField] it is never stored:
     *  a one-use code with minutes of life, cleared once it has been sent. */
    private var vaultCodeBox: EditText? = null

    private fun vaultCodeField(ctx: android.content.Context): EditText =
        EditText(ctx).apply {
            hint = getString(R.string.vault_connect_code_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            vaultCodeBox = this
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
        viewLifecycleOwner.lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { VaultConnect.start(e, auth) }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    showVaultFailure(status, o)
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok ->
                    show(status, GREEN, getString(R.string.vault_connect_sent))
            }
        }
    }

    private fun vaultFetch(status: TextView) {
        val auth = vaultAuth(status) ?: return
        val box = vaultCodeBox ?: return
        val code = box.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            box.error = getString(R.string.vault_connect_no_code)
            return
        }
        box.setText("")
        val e = vaultEndpoints()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { VaultConnect.fetch(e, auth, code) }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    showVaultFailure(status, o)
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> landVault(status, o.body)
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
    private fun landVault(status: TextView, body: org.json.JSONObject, redrawNow: Boolean = true): Boolean {
        VaultConnect.unknownSchemaVersion(body, VaultConnect.knownSchemaVersions)?.let { v ->
            show(status, RED, "✗ " + getString(R.string.vault_connect_schema_unknown, v,
                VaultConnect.knownSchemaVersions.sorted().joinToString(", ")))
            return false
        }
        val sections = VaultConnect.sections(body)
        VaultConnect.Imported.last = sections
        VaultConnect.Imported.bundle = body.optJSONObject("bundle") ?: body
        show(status, GREEN, getString(
            R.string.vault_connect_fetched, sections.sumOf { it.rows.size }, sections.size, tabLabel(infosTab)))
        selectedTab = infosTab
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

    /**
     * The COCKPIT, on Setup since #695 (the Fleet tab until #626, then an Infos
     * section): what Cloud Constellation Setup applies, item by item.
     *
     * A hero card for the device this phone is — orb, name, mesh identity, the
     * overall light and the way back to Connect, where it is picked — then one
     * card per cockpit section (build.json::ui.vault_connect.cockpit) with the
     * section's own light, a summary line, the rows (each saying applied / not
     * applied / why, [renderRows]) and its Apply; then one last card, raw, for
     * every vault section no cockpit section names. The chrome is
     * [FleetCockpitView]; the lights are the shared [StatusLight], summed per
     * card by [VaultCockpit.sectionLight] and over the page by
     * [VaultCockpit.overallLight]. RENDERING WRITES NOTHING — every apply is a tap.
     */
    private fun renderImported(ctx: android.content.Context, into: LinearLayout) {
        val sections = VaultConnect.Imported.last
        val bundle = VaultConnect.Imported.bundle
        val layout = VaultCockpit.layout
        cardStates.clear()
        if (sections == null || bundle == null) {
            // Empty state: the same hero, saying what is missing and where to get it.
            val hero = FleetCockpitView.hero(ctx,
                getString(R.string.vault_cockpit_hero_title_empty),
                getString(R.string.vault_imported_empty),
                Sections.iconResFor(ctx, VaultCockpit.deviceIcon(layout, null)))
            FleetCockpitView.paint(hero.light, StatusLight.State.UNKNOWN, hero.title.text.toString())
            hero.summary.text = getString(R.string.vault_cockpit_hero_empty)
            hero.slot.addView(pickButton(ctx, getString(R.string.vault_cockpit_connect_cta)) {
                strip?.getTabAt(connectTab)?.select()
            })
            into.addView(hero.root)
            return
        }
        val devices = VaultCockpit.devices(bundle)
        val device = devices.firstOrNull { it.id == VaultCockpit.selectedDevice(ctx) }
        val hero = FleetCockpitView.hero(ctx,
            device?.label ?: getString(R.string.vault_cockpit_hero_unpicked),
            device?.let { getString(R.string.vault_cockpit_hero_identity, it.wgIp, it.wgIpv6.ifBlank { "—" }) }
                ?: getString(R.string.vault_cockpit_device_label),
            Sections.iconResFor(ctx, VaultCockpit.deviceIcon(layout, device)))
        heroViews = hero
        into.addView(hero.root)
        // #713 WHICH machine this is, picked right here — the one place that applies for it.
        renderDeviceSelector(ctx, hero.slot, devices)
        into.addView(caption(ctx, getString(R.string.vault_cockpit_caption)))

        for (section in VaultCockpit.layout.sections) {
            val card = FleetCockpitView.card(ctx, section.label, section.id,
                Sections.iconResFor(ctx, section.icon), getString(R.string.vault_cockpit_card_toggle))
            indexCards[section.id] = card
            into.addView(card.root)
            if (section.vault.none { bundle.has(it) }) {
                card.body.addView(caption(ctx, getString(R.string.vault_cockpit_section_absent, section.vault.joinToString(", "))))
                paintCard(card, emptyList(), section, getString(R.string.vault_cockpit_card_absent_summary))
                continue
            }
            val status = TextView(ctx).apply { visibility = View.GONE; setTextIsSelectable(true) }
            val rows: List<VaultCockpit.Row> = when (section.apply) {
                "mail"     -> renderMail(ctx, card.body, bundle, status)
                "keyboard" -> VaultCockpit.keyboardRows(bundle, getString(R.string.vault_cockpit_keyboard_device)).also { renderRows(ctx, card.body, it, section.observed) }
                "mesh"     -> renderMesh(ctx, card.body, bundle, device, status)
                "drive"    -> renderDrive(ctx, card.body, bundle, status)
                "ai"       -> renderAi(ctx, card.body, bundle, status, card, section)
                "apps"     -> renderApps(ctx, card.body, bundle, device)
                "about"    -> renderAbout(ctx, card.body, bundle, section, status)
                else       -> { sections.filter { it.id in section.vault }.forEach { renderRaw(ctx, card.body, it) }; emptyList() }
            }
            card.body.addView(status)
            paintCard(card, rows, section)
        }
        val consumed = VaultCockpit.consumed(VaultCockpit.layout)
        val rest = sections.filter { it.id !in consumed }
        if (rest.isNotEmpty()) {
            val raw = FleetCockpitView.card(ctx, getString(R.string.vault_cockpit_raw), RAW_CARD,
                Sections.iconResFor(ctx, ""), getString(R.string.vault_cockpit_card_toggle))
            FleetCockpitView.paint(raw.light, StatusLight.State.UNKNOWN, raw.label)
            raw.summary.text = getString(R.string.vault_cockpit_card_raw_summary)
            raw.body.addView(caption(ctx, getString(R.string.vault_imported_caption, IMPORTED_PREVIEW_CHARS)))
            rest.forEach { renderRaw(ctx, raw.body, it) }
            into.addView(raw.root)
        }
    }

    /**
     * One card's light and summary from its rows, then the hero's from every
     * card's. [summary] overrides the counted line for a card with nothing to
     * count (a section absent from this export).
     */
    private fun paintCard(
        card: FleetCockpitView.Card, rows: List<VaultCockpit.Row>,
        section: VaultCockpit.Section, summary: String? = null,
    ) {
        val state = VaultCockpit.sectionLight(rows, section.observed)
        FleetCockpitView.paint(card.light, state, card.label)
        val t = VaultCockpit.tally(rows)
        card.summary.text = summary
            ?: if (section.observed) getString(R.string.vault_cockpit_card_summary, t.match, t.differ, t.pending)
               else getString(R.string.vault_cockpit_card_unobserved_summary, rows.size)
        cardStates[card.tag] = state
        indexRows[card.tag]?.apply {
            text = getString(R.string.setup_index_row, card.label, card.summary.text)
            setTextColor(StatusLight.colour(context, state))
        }
        repaintHero()
    }

    private fun repaintHero() {
        val hero = heroViews ?: return
        FleetCockpitView.paint(hero.light, VaultCockpit.overallLight(cardStates.values), hero.title.text.toString())
        hero.summary.text = getString(R.string.vault_cockpit_hero_summary,
            cardStates.values.count { it == StatusLight.State.ON }, cardStates.size)
    }

    /** One vault section no cockpit section owns, read-only, through the SAME mask
     *  as Infos (#695) — a section the vault grows tomorrow cannot leak here either. */
    private fun renderRaw(ctx: android.content.Context, into: LinearLayout, section: VaultConnect.Section) {
        val rows = InfoMask.declared.rows(section.id, VaultConnect.Imported.bundle?.opt(section.id))
        into.addView(label(ctx, "${section.label}  (${rows.size})"))
        for (row in rows) {
            into.addView(label(ctx, row.path.ifBlank { section.id }))
            into.addView(infoValue(ctx, row))
        }
    }

    /**
     * WHICH machine this is: a pick among the vault's declared devices, in the
     * hero's slot. Only the chosen id is stored; address, key and profiles
     * derive from the declaration every time the tab draws, and the hero's
     * title and identity line are that declaration read back.
     */
    private fun renderDeviceSelector(
        ctx: android.content.Context, into: LinearLayout, devices: List<VaultCockpit.Device>,
    ) {
        if (devices.isEmpty()) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_no_devices)))
            return
        }
        val chosenId = VaultCockpit.selectedDevice(ctx)
        val chosen = devices.firstOrNull { it.id == chosenId }
        val labels = listOf(getString(R.string.vault_cockpit_device_pick)) + devices.map { "${it.label} (${it.id})" }
        val spinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(if (chosen == null) 0 else devices.indexOf(chosen) + 1)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val picked = devices.getOrNull(pos - 1)?.id ?: ""
                    if (picked == VaultCockpit.selectedDevice(ctx)) return
                    VaultCockpit.selectDevice(ctx, picked)
                    redraw()
                }
            }
        }
        into.addView(spinner)
    }

    /**
     * One Setup ITEM per row (#695): applied, or not applied AND WHY — never a
     * tick for what is not done. [observed] false is a section whose device side
     * this app cannot read (the keyboard): its rows say "not verifiable", whatever
     * a comparison would have guessed.
     */
    private fun renderRows(ctx: android.content.Context, into: LinearLayout, rows: List<VaultCockpit.Row>, observed: Boolean = true) {
        for (row in rows) {
            into.addView(label(ctx, row.label))
            into.addView(TextView(ctx).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                tag = "item:${row.state.name.lowercase()}"
                val (verdict, colour) = itemVerdict(row.state, observed && row.observed)
                setTextColor(colour)
                text = "$verdict\n  vault:  ${row.declared}\n  device: ${row.device}"
            })
        }
    }

    /** The verdict line and its ink for one item — the only place a state becomes words. */
    private fun itemVerdict(state: VaultCockpit.State, observed: Boolean): Pair<String, Int> = when {
        !observed -> getString(R.string.setup_item_unverifiable) to NEUTRAL
        state == VaultCockpit.State.MATCH -> getString(R.string.setup_item_applied) to GREEN
        state == VaultCockpit.State.DIFFERS -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_differs)) to RED
        state == VaultCockpit.State.ABSENT -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_absent)) to RED
        else -> getString(R.string.setup_item_not_applied, getString(R.string.setup_why_pending)) to NEUTRAL
    }

    private fun applyButton(ctx: android.content.Context, what: String, onApply: () -> Unit): View =
        pickButton(ctx, getString(R.string.vault_cockpit_apply, what)) { onApply() }

    private fun redraw() {
        if (!isAdded) return
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    // Every render* below returns the rows it drew, so the card's light is
    // computed from exactly what is on screen and never from a second reading.

    /**
     * EVERY account the vault's mail section declares (#695), each its own item:
     * applied when it is the account cloud-sa's mail holds, not applied (and why)
     * otherwise, one Apply each. The card's light is the signed-in address's
     * account, as before — the one this device is meant to read. cloud-mail, the
     * separate app, accepts no account from another app, and the card says so.
     */
    private fun renderMail(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject, status: TextView): List<VaultCockpit.Row> {
        val email = ConfigsPrefs(ctx).autheliaEmail.ifBlank { prefs.email.trim() }
            .ifBlank { VaultCockpit.ownerEmail(bundle, VaultCockpit.layout) }
        val jmap = com.diegonmarcos.superapp.mail.JmapPrefs(ctx)
        val accounts = VaultCockpit.mailAccounts(bundle, email.substringAfter('@', ""))
        if (accounts.isEmpty()) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_mail_none, email.ifBlank { "—" })))
            return emptyList()
        }
        into.addView(caption(ctx, getString(R.string.setup_mail_accounts, accounts.size)))
        renderRows(ctx, into, VaultCockpit.mailAccountRows(accounts, jmap.email))
        val declared = VaultCockpit.mailDeclared(bundle, email)
        val rows: List<VaultCockpit.Row> = if (declared == null) emptyList() else VaultCockpit.mailRows(declared, jmap)
        if (declared != null) {
            into.addView(label(ctx, declared.email))
            renderRows(ctx, into, rows)
        }
        for (d in accounts) {
            into.addView(applyButton(ctx, d.email) {
                show(status, GREEN, VaultCockpit.applyMail(com.diegonmarcos.superapp.mail.JmapPrefs(ctx), d))
                redraw()
            })
        }
        into.addView(caption(ctx, getString(R.string.setup_mail_cloud_mail)))
        return rows
    }

    private fun renderMesh(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject,
                           device: VaultCockpit.Device?, status: TextView): List<VaultCockpit.Row> {
        if (device == null) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_pick_first)))
            return emptyList()
        }
        val profiles = VaultCockpit.meshProfiles(bundle, device)
        if (profiles.isEmpty()) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_mesh_none, device.wgIp)))
            return emptyList()
        }
        val wg = com.diegonmarcos.superapp.network.WgState.prefs(ctx)
        val rows = VaultCockpit.meshRows(bundle, device, VaultCockpit.tunnelState(wg))
        renderRows(ctx, into, rows)
        for ((name, conf) in profiles) {
            into.addView(applyButton(ctx, name) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.vault_cockpit_apply, name))
                    .setMessage(getString(R.string.vault_cockpit_mesh_confirm, device.label))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(getString(R.string.vault_cockpit_apply, name)) { _, _ ->
                        val line = VaultCockpit.applyMesh(wg, name, conf)
                        show(status, if (line.startsWith("✓")) GREEN else RED, line)
                        redraw()
                    }
                    .show()
            })
        }
        into.addView(pickButton(ctx, getString(R.string.vault_cockpit_open_wireguard)) {
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked(WG_ROUTE)
        })
        return rows
    }

    private fun renderDrive(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject, status: TextView): List<VaultCockpit.Row> {
        val rows = VaultCockpit.driveSessionRows(AuthDeclaration.gitChain, getString(R.string.setup_drive_session_device)) +
            VaultCockpit.driveRows(bundle, ConfigsPrefs(ctx), getString(R.string.setup_drive_repo_device))
        renderRows(ctx, into, rows)
        into.addView(applyButton(ctx, getString(R.string.vault_cockpit_drive_credentials)) {
            val line = VaultCockpit.applyDrive(bundle, ConfigsPrefs(ctx))
            show(status, if (line.startsWith("✓")) GREEN else RED, line)
            redraw()
        })
        return rows
    }

    /**
     * The device column is what the serving app ANSWERS over the binder, read
     * on IO with a deadline — a wedged peer must not hang this tab. Until the
     * answer lands the card's light is Unknown (no rows returned); the answer
     * repaints the card, and through it the hero, once.
     */
    private fun renderAi(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject, status: TextView,
                         card: FleetCockpitView.Card, section: VaultCockpit.Section): List<VaultCockpit.Row> {
        val rowsView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        into.addView(rowsView)
        val peerDown = getString(R.string.vault_cockpit_ai_peer_down)
        val unmapped = getString(R.string.vault_cockpit_ai_unmapped)
        val layout = VaultCockpit.layout
        renderRows(ctx, rowsView, VaultCockpit.aiRows(bundle, layout, null, getString(R.string.vault_cockpit_reading), unmapped))
        val appCtx = ctx.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = kotlinx.coroutines.withTimeoutOrNull(AI_PEER_DEADLINE_MS) {
                withContext(Dispatchers.IO) { com.diegonmarcos.superapp.texttools.TextToolsClient(appCtx).aiRoutingSnapshot() }
            }
            rowsView.removeAllViews()
            val rows = VaultCockpit.aiRows(bundle, layout, VaultCockpit.aiState(snapshot), peerDown, unmapped)
            renderRows(ctx, rowsView, rows)
            paintCard(card, rows, section)
        }
        into.addView(applyButton(ctx, getString(R.string.vault_cockpit_ai_tokens)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val line = kotlinx.coroutines.withTimeoutOrNull(AI_PEER_DEADLINE_MS) {
                    withContext(Dispatchers.IO) {
                        VaultCockpit.applyAi(bundle, layout, com.diegonmarcos.superapp.texttools.TextToolsClient(appCtx))
                    }
                } ?: ("✗ " + peerDown)
                show(status, if (line.startsWith("✓")) GREEN else RED, line)
                redraw()
            }
        })
        into.addView(pickButton(ctx, getString(R.string.vault_cockpit_open_ai)) {
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked(AI_ROUTE)
        })
        return emptyList()
    }

    /** #565's exporter writes the file; #565's plan + summary do the compare.
     *  This tab adds no second inventory format and no second installer. */
    private val appListExport =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            val app = requireContext().applicationContext
            kotlin.concurrent.thread(name = "vault-apps-export") {
                val result = runCatching {
                    val entries = com.diegonmarcos.superapp.appstore.AppInventory.entriesFor(
                        app, com.diegonmarcos.superapp.appstore.AppInventory.launchable(app))
                    app.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(com.diegonmarcos.superapp.appstore.AppInventory.toJson(entries).toByteArray())
                    }
                    entries.size
                }
                view?.post { view?.snack(result.fold({ getString(R.string.vault_cockpit_apps_exported, it) }, { "✗ ${it.message}" })) }
            }
        }

    private fun renderApps(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject, device: VaultCockpit.Device?): List<VaultCockpit.Row> {
        if (device == null) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_pick_first)))
            return emptyList()
        }
        val fleet = com.diegonmarcos.superapp.appstore.AppInventory.fleetPackages()
        val declared = VaultCockpit.appsDeclared(bundle, device, fleet)
        var rows: List<VaultCockpit.Row> = emptyList()
        if (declared.isEmpty()) {
            into.addView(caption(ctx, getString(R.string.vault_cockpit_apps_none, device.id)))
        } else {
            val pm = ctx.packageManager
            val installed = declared.count { runCatching { pm.getPackageInfo(it.pkg, 0) }.isSuccess }
            rows = listOf(VaultCockpit.Row(
                getString(R.string.vault_cockpit_apps_row), "${declared.size} apps", "$installed installed",
                if (installed == declared.size) VaultCockpit.State.MATCH else VaultCockpit.State.DIFFERS))
            renderRows(ctx, into, rows)
            into.addView(pickButton(ctx, getString(R.string.vault_cockpit_apps_plan, declared.size)) {
                val app = ctx.applicationContext
                kotlin.concurrent.thread(name = "vault-apps-plan") {
                    val have = declared.map { it.pkg }
                        .filter { runCatching { app.packageManager.getPackageInfo(it, 0) }.isSuccess }.toSet()
                    val plan = com.diegonmarcos.superapp.appstore.AppInventory.plan(
                        declared, have, fleet, com.diegonmarcos.superapp.appstore.PhoneAppActions.sources(app))
                    view?.post { if (isAdded) com.diegonmarcos.superapp.appstore.StoreImport.show(this, plan) }
                }
            })
        }
        into.addView(pickButton(ctx, getString(R.string.vault_cockpit_apps_export)) {
            appListExport.launch(APPS_EXPORT_NAME)
        })
        return rows
    }

    /**
     * `about` (#695): the vault's contact card against this device's, one item per
     * DECLARED field (the section's `fields`: device field → vault key), and one
     * Apply that writes the fields the vault carries. The editable card itself is
     * drawn right below the cockpit on Setup.
     */
    private fun renderAbout(ctx: android.content.Context, into: LinearLayout, bundle: org.json.JSONObject,
                            section: VaultCockpit.Section, status: TextView): List<VaultCockpit.Row> {
        val rows = VaultCockpit.aboutRows(bundle, section.fields) { profileField(it) }
        renderRows(ctx, into, rows)
        into.addView(applyButton(ctx, section.label) {
            val written = VaultCockpit.applyAbout(bundle, section.fields) { f, v -> setProfileField(f, v) }
            show(status, if (written.isEmpty()) RED else GREEN,
                if (written.isEmpty()) getString(R.string.setup_about_nothing)
                else getString(R.string.setup_about_applied, written.size, written.joinToString(", ")))
            redraw()
        })
        return rows
    }

    /** This device's contact-card field [field] — the ProfilePrefs vocabulary; null for a field it has not. */
    private fun profileField(field: String): String? = when (field) {
        "name" -> prefs.name
        "email" -> prefs.email
        "company" -> prefs.company
        "location" -> prefs.location
        "website" -> prefs.website
        "titles" -> prefs.titles
        else -> null
    }

    private fun setProfileField(field: String, value: String): Boolean = when (field) {
        "name" -> { prefs.name = value; true }
        "email" -> { prefs.email = value; true }
        "company" -> { prefs.company = value; true }
        "location" -> { prefs.location = value; true }
        "website" -> { prefs.website = value; true }
        "titles" -> { prefs.titles = value; true }
        else -> false
    }

    /** Retry a queued upload whenever this screen comes back — a plausible
     *  moment for connectivity to have returned since the last failure. */
    override fun onResume() {
        super.onResume()
        ProfileSync.flush(requireContext())
        refreshStatus()
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
        statusBanner = null
        strip = null
        heroViews = null
        pageScroll = null
        indexRows.clear()
        indexCards.clear()
        journey = null
        // The mailed code is never stored; it dies with the view that held it.
        mailCodeField = null
    }

    // ── mandatory-field enforcement ──────────────────────────────────────

    /**
     * A [field] that additionally reports [validate]'s complaint on itself and
     * refreshes the banner on every keystroke.
     *
     * The value is SAVED even while invalid. Refusing to persist a half-typed
     * name would mean losing it on rotation, and the enforcement goal is that
     * the gap is loud, not that the text box fights the user.
     */
    private fun requiredField(
        ctx: android.content.Context,
        initial: String,
        save: (String) -> Unit,
        validate: () -> String?,
    ): EditText = field(ctx, initial) {
        save(it)
        refreshStatus()
    }.apply {
        error = validate()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            // Runs after the save watcher installed by field(), so prefs are
            // already current when validate() reads them back.
            override fun afterTextChanged(s: Editable?) { error = validate() }
        })
    }

    /**
     * Repaint the completeness banner. Called on every keystroke, so it does
     * no I/O beyond reading prefs — the upload is NOT driven from here.
     *
     * Syncing per keystroke would mean a POST per character typed. The edits
     * are already durable in prefs the moment they are typed, and the profile
     * is full-state, so the natural send point is leaving the screen
     * ([onPause]) — one upload carrying the finished card.
     */
    private fun refreshStatus() {
        val banner = statusBanner ?: return
        val complete = prefs.isComplete
        if (complete) {
            banner.setBackgroundColor(0x2216A34A)
            banner.setTextColor(GREEN)
            banner.text = if (ProfileSync.isPending(banner.context))
                "Profile complete — saved, waiting to reach the server (it will retry)."
            else
                "Profile complete — saved and synced."
        } else {
            banner.setBackgroundColor(0x22DC2626)
            banner.setTextColor(RED)
            banner.text = "Profile incomplete — " +
                listOfNotNull(prefs.nameError, prefs.emailError).joinToString("; ") + ".\n" +
                "Nothing is blocked, but without a name and an email there is no way to reach " +
                "you if an update ever breaks the app, and your profile is not synced."
        }
    }

    /** Small read-only line stating whether a document is still queued. */
    private fun syncStateView(ctx: android.content.Context): TextView =
        caption(ctx, if (ProfileSync.isPending(ctx))
            "Sync status: an edit is queued on this device and has not reached the server yet. It retries automatically."
        else
            "Sync status: nothing queued.")

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

    // ── erasure ──────────────────────────────────────────────────────────

    /**
     * Confirm, then erase locally AND ask the server to drop the record.
     *
     * Two-step because it is destructive and irreversible; the result is
     * reported verbatim (including a failed server delete) rather than
     * optimistically claiming success, so an erasure that did not fully happen
     * can be chased instead of assumed.
     */
    private fun confirmErase() {
        val ctx = requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Erase your profile?")
            .setMessage(
                "This deletes your name, email, phone, date of birth, location, " +
                "company, website, about and photos from this device, and asks the " +
                "server to delete its copy.\n\n" +
                "Your stored credentials are NOT touched — they were never sent to the " +
                "server, so erasing the server copy has nothing to do with them. Clear " +
                "them with their own buttons above.\n\n" +
                "Your device also gets a new random sync id, so the old server-side " +
                "record can no longer be linked to this install.\n\nThis cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Erase") { _, _ ->
                ProfileSync.forgetMe(ctx) { message ->
                    // Callback arrives on the delete thread.
                    view?.post {
                        view?.snack(message)
                        if (isAdded) {
                            parentFragmentManager.beginTransaction().detach(this).commitNow()
                            parentFragmentManager.beginTransaction().attach(this).commitNow()
                        }
                    }
                }
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
            view?.post { if (importedThisSession) { importedThisSession = false; redraw() } }
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
            else -> when (val v = com.diegonmarcos.superapp.settings.ImportConfigsFragment.classify(text)) {
                is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle -> landVault(status, v.bundle)
                else -> refuse(com.diegonmarcos.superapp.settings.ImportConfigsFragment.refusal(ctx, v).orEmpty())
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

    private fun showGithubSshDialog() {
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
                    runVaultRead(status, { go.isEnabled = true }) {
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
    private fun showGithubPatDialog() {
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
                    runVaultRead(status, { go.isEnabled = true }) { fetchVaultFileWithToken(token, hint) }
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
        done: () -> Unit,
        fetch: () -> com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val o = withContext(Dispatchers.IO) { fetch() }) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    show(status, RED, "✗ ${o.kind}\n${o.message}")
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> {
                    landVault(status, o.body, redrawNow = false)
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

    private fun actionTile(ctx: android.content.Context, label: String, bg: Int, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(bg)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 14), dp(ctx, 10), dp(ctx, 14))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(ctx, 4); marginStart = dp(ctx, 4) }
        }

    /** Copy the picked image into our cache dir + store the cached path
     *  in ProfilePrefs. We don't rely on the original `content://` URI
     *  surviving — the source app may revoke permission later. */
    private fun saveImage(uri: android.net.Uri, isBanner: Boolean) {
        runCatching {
            val ctx = requireContext()
            val name = if (isBanner) "profile_banner.png" else "profile_picture.png"
            val outFile = java.io.File(ctx.filesDir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            if (isBanner) prefs.bannerUri = outFile.absolutePath
            else          prefs.pictureUri = outFile.absolutePath
            // Re-render so the buttons show the new path.
            parentFragmentManager.beginTransaction().detach(this).commitNow()
            parentFragmentManager.beginTransaction().attach(this).commitNow()
        }
    }

    /** Every action button on this page is the cockpit's pill — one shape, the
     *  palette's accent, so Connect and Infos read as the same screen as Fleet
     *  and a theme change restyles all three at once. */
    private fun pickButton(ctx: android.content.Context, currentLabel: String, onClick: () -> Unit): View =
        FleetCockpitView.pill(ctx, currentLabel, onClick)

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 4))
        }

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

    private fun field(ctx: android.content.Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    /**
     * Drop a leading/trailing `|` from About.
     *
     * The field's old label asked for ' | ' between items, so stored values
     * carry an opening separator that now means nothing. Only
     * the outer ones go; separators BETWEEN items are the user's own text.
     * This runs on save, over what the user is looking at — the box is never
     * rewritten underneath them.
     */
    private fun trimSeparators(value: String): String =
        value.trim().trim('|').trim()

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

        /**
         * The AI page's existing route. It is a LINK, not a copy: `config/ai`
         * has no `action` of its own, so it resolves through SectionPages to
         * whatever that page is — now the five-tab AI strip (WebSearch,
         * LocalSearch, Text Enhance, Library, Tokens Fleet) rather than the
         * single fragment that used to sit there. The route did not change when
         * the page did, which is the point of naming a page and not a class.
         */
        private const val AI_ROUTE = "page:config/ai"

        /**
         * The WireGuard screen's route.
         *
         * `section:wg`, NOT `page:config/wg`. The Configs page `wg` declares
         * `action: section:wg`, and LauncherNavController dispatches a page's
         * action instead of opening it — so `page:config/wg` is silently
         * rewritten to this anyway, and `page:wg/config` would push a SECOND
         * copy of the fragment on top of the one the section already opened.
         * `wg` is a single_page section, so this lands WireGuardFragment
         * directly, and its `parent: config` makes Back return to Configs.
         */
        private const val WG_ROUTE = "section:wg"

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

        /** Imported values longer than this are shortened until tapped. */
        private const val IMPORTED_PREVIEW_CHARS = 400

        /** How long the Fleet tab waits for the AI serving app's binder. */
        private const val AI_PEER_DEADLINE_MS = 4_000L

        /** Tag of the one card that compares nothing: the vault sections no
         *  cockpit section names, shown raw. Not a cockpit section id. */
        private const val RAW_CARD = "raw"

        /** Same file name Store ▸ Phone Apps exports, so the vault gets one shape. */
        private const val APPS_EXPORT_NAME = "cloud-sa-apps.json"

        /** Advisory shape check for the birth field. Range/real-calendar
         *  validity is deliberately not checked — the field is optional and a
         *  false rejection is worse than a typo here. */
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** DD-MM-YYYY, the shape people actually type here. */
        private val DMY_PATTERN = Regex("^(\\d{2})-(\\d{2})-(\\d{4})$")

        /**
         * The ISO date [text] unambiguously means, or null.
         *
         * ONLY when the first field is >12, which cannot be a month and so
         * cannot be the American MM-DD-YYYY. `18-07-1987` is 1987-07-18 in
         * every reading and converts; `05-07-1987` is the 5th of July or the
         * 7th of May depending on which side of an ocean it was typed on, and
         * no amount of confidence here would settle it — that one keeps the
         * advisory and waits for the user to retype it.
         *
         * The month is still checked, because a first field >12 tells us which
         * position is the day, not that the other one is a real month.
         */
        fun isoFromDmy(text: String): String? {
            val m = DMY_PATTERN.matchEntire(text) ?: return null
            val (d, mo, y) = m.destructured
            if (d.toInt() !in 13..31) return null
            if (mo.toInt() !in 1..12) return null
            return "$y-$mo-$d"
        }

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

        private const val PRIVACY_TEXT =
            "Your name, email, phone, date of birth, location, company, website and " +
            "about are stored on this device and mirrored to " +
            "the constellation server over HTTPS, so the fleet operator can contact you " +
            "out-of-band when an update breaks the app and it can no longer fix itself. " +
            "That is the only reason this is collected.\n\n" +
            "Your credentials are NOT in that list and never leave this device — the " +
            "sync document is built from a fixed list of contact fields and filtered " +
            "against it again before sending.\n\n" +
            "Your photos stay on this device and are never uploaded. Your profile is " +
            "identified by a random id generated on this install — not by any device, " +
            "SIM or advertising identifier. It is not synced until name and email are " +
            "filled in, and it is never written to logs or crash reports.\n\n" +
            "Erase removes it here and asks the server to delete its copy."

        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
