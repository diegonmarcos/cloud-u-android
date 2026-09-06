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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.launcher.AppTabsStyle
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.ui.snack
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs → Profile — the contact card, bound to [ProfilePrefs]. Auto-saves on
 * every text change (no explicit Save button) — the drawer header reads from
 * the same prefs on every open, so changes are visible immediately next time
 * the drawer slides in.
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

    /** Which of the two tabs is showing. Held on the fragment so the many
     *  detach/attach redraws below do not bounce the user back to Connect. */
    private var selectedTab = 0

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

        // TWO content columns; `col` keeps its name so every field that is only
        // MOVING between tabs keeps its existing call site. WireGuard and AI
        // have no column at all — see [tabStrip].
        val connect = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        page.addView(connect)
        page.addView(col)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // A null column means a LINK, not a page — see [tabStrip]. WireGuard
        // and AI both already have a screen, and re-hosting either here would
        // be a second copy to keep in step.
        root.addView(tabStrip(ctx, listOf(
            Tab("Connect", connect),
            Tab("WireGuard", null, WG_ROUTE),
            Tab("AI", null, AI_ROUTE),
            Tab("Infos", col),
        )))
        root.addView(scroll)

        col.addView(sectionHeader(ctx, "Personal Data"))
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
            // Advisory only — a wrong-looking date is flagged, never rejected,
            // and never blocks saving. The field is optional to begin with.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty().trim()
                    error = when {
                        text.isEmpty() || DATE_PATTERN.matches(text) -> null
                        // Name the correction instead of only the rule. The
                        // stored value is NOT rewritten — a date silently
                        // reordered under the user is worse than a wrong one
                        // they can see and fix.
                        else -> DMY_PATTERN.matchEntire(text)?.let {
                            val (d, m, y) = it.destructured
                            "Use YYYY-MM-DD — did you mean $y-$m-$d?"
                        } ?: "Use YYYY-MM-DD"
                    }
                }
            })
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

        // ── Connect (tab 1) ──────────────────────────────────────────────
        // Two stored fields and the code that confirms them, and nothing else.
        // Everything on this tab is a way IN; none of it is ever synced.
        connect.addView(sectionHeader(ctx, "Connect  (this device only)"))
        connect.addView(caption(ctx, CONNECT_TEXT))

        // ONE address for this section. It is the account the bearer belongs
        // to AND the "user" the provider request asked for — those are the
        // same identity, so a second box would only invite them to disagree.
        connect.addView(label(ctx, "Account email"))
        connect.addView(autheliaEmailEditor(ctx))

        connect.addView(label(ctx, "Authelia bearer token"))
        connect.addView(secretField(
            ctx,
            stored = { ConfigsPrefs(ctx).autheliaToken.isNotBlank() },
            save = { saveAutheliaToken(it) },
        ))
        connect.addView(clearSecretButton(ctx, "Authelia bearer token") {
            ConfigsPrefs(ctx).clearAutheliaCredential()
        })
        if (ConfigsPrefs(ctx).hasOrphanToken()) {
            connect.addView(caption(ctx, ORPHAN_TOKEN_TEXT))
            connect.addView(pickButton(ctx, "Link the stored token to this address") {
                linkOrphanToken()
            })
        }

        connect.addView(label(ctx, "Mail 2FA confirmation code"))
        connect.addView(mailConfirmationField(ctx))
        connect.addView(caption(ctx, MAIL_2FA_TEXT))
        connect.addView(pickButton(ctx, "Confirm in Authelia") { confirmMailCode() })

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

        // ── Imports ──────────────────────────────────────────────────────
        // All five entries live HERE. "Import Configs" used to be its own
        // Configs-grid tile (build.json::ui.sections[config].pages[import],
        // action:import_configs) — the tile is gone and the action route it
        // used is reused verbatim below, so the launcher shortcut and the
        // radial menu still reach the same screen.
        col.addView(sectionHeader(ctx, "Imports"))
        col.addView(caption(ctx, "One artifact, five ways in. The manual route is its own row; the four below differ only in how they prove who you are — Authelia by pasted token or by browser login, GitHub by login or by SSH key. All of them end in the same apply step, so whichever you use, the same sections are written."))

        // Row 1 — the manual route, alone and full width. It is the only entry
        // that needs no credential, so grouping it with the four authenticated
        // ones made it read as a fifth variant of the same thing.
        val manualRow = importRow(ctx)
        manualRow.addView(actionTile(ctx, "Import file · paste", 0xFF7C3AED.toInt()) {
            // Same route MainActivity already owns, so chrome/back-stack
            // behaviour is identical to the old Configs tile.
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked("action:import_configs")
        })
        col.addView(manualRow)

        // Row 2 — the authenticated routes. Teal is Authelia, slate is GitHub,
        // so the pair-of-pairs is legible before reading a word.
        val authRow = importRow(ctx)
        authRow.addView(actionTile(ctx, "Import\nAuthelia\nBearer", 0xFF0F766E.toInt()) {
            showAutheliaBearerDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nAuthelia\nOWebAuth", 0xFF0E7490.toInt()) {
            showAutheliaWebAuthDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nGh\nOWebAuth", 0xFF334155.toInt()) {
            showGithubDeviceDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nGH\nSSH", 0xFF1F2937.toInt()) {
            showGithubSshDialog()
        })
        col.addView(authRow)

        return root
    }

    // ── tabs ─────────────────────────────────────────────────────────────

    /**
     * The Connect | Info strip.
     *
     * Two plain columns swapped by visibility — no child fragments, no pane
     * host ids, no build.json pages (see the note in [onCreateView]). It reuses
     * [AppTabsStyle] so the pills read exactly like the launcher's section
     * strips, which is the whole of what that idiom is worth here.
     *
     * The selection is held on the FRAGMENT, not the view, because this screen
     * redraws itself with detach/attach — which destroys the view and keeps the
     * instance. Without that the user would be thrown back to Connect every
     * time they picked a photo from the Info tab.
     */
    /** One tab. A null [column] makes it a LINK: selecting it dispatches
     *  [route] and hands the selection straight back. */
    private data class Tab(val title: String, val column: View?, val route: String = "")

    private fun tabStrip(
        ctx: android.content.Context,
        tabs: List<Tab>,
    ): TabLayout {
        fun show(index: Int) {
            tabs.forEachIndexed { i, tab ->
                tab.column?.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }
        show(selectedTab)
        return TabLayout(ctx).apply {
            tabs.forEach { addTab(newTab().setText(it.title)) }
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    // A tab with NO column is a LAUNCH tab — a button wearing a
                    // tab, the idiom [SectionTabsFragment] already uses for
                    // C3's Watchdog and Morpheus. AI is one because the AI page
                    // ALREADY EXISTS at page:config/ai; re-hosting it here would
                    // be a second copy to keep in step. So the tab navigates
                    // and hands the selection straight back — leaving for
                    // another page must not also leave this strip parked on a
                    // tab with nothing behind it, which is what the user would
                    // return to.
                    val picked = tabs.getOrNull(tab.position) ?: return
                    if (picked.column == null) {
                        (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                            ?.onTileClicked(picked.route)
                        getTabAt(selectedTab)
                            ?.takeIf { it != tab }
                            ?.let { back -> post { back.select() } }
                        return
                    }
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
        showAutheliaWebAuthDialog()
        view?.snack("Code copied — paste it into the Authelia page")
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

    /**
     * A write-mostly box for one credential.
     *
     * WRITE-MOSTLY, not read-write, and that is the whole design. The stored
     * value is NEVER put back into the view — the box starts empty and its
     * hint says only whether something is on file. A masked EditText still
     * holds the plaintext in the view hierarchy (recents screenshots,
     * accessibility tree, a "show password" toggle), so the way to keep a
     * bearer or a private key out of the UI is to not load it into the UI.
     *
     * Consequences that are intentional:
     *  • blank does NOT clear — leaving the box untouched must not wipe a key
     *    the user cannot see to retype. Clearing is the explicit button below.
     *  • the value is saved on change, matching the rest of this auto-saving
     *    screen, so there is no unsaved-state trap.
     *  • nothing here is logged. The credential never becomes a string this
     *    class hands to Log, and it is not in the profile sync document —
     *    [ProfileSync] enumerates its keys and re-filters them through an
     *    allowlist that contains neither of these.
     */
    private fun secretField(
        ctx: android.content.Context,
        stored: () -> Boolean,
        save: (String) -> Unit,
    ): EditText = EditText(ctx).apply {
        hint = if (stored()) "•••••••• stored — type to replace" else "Paste to store on this device"
        setSingleLine(false)
        maxLines = 4
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Keep it out of the keyboard's learned-words store, and out of
        // autofill's — both persist what is typed well beyond this screen.
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) save(typed)
            }
        })
    }

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

    /** Live handle to the account-email box, so the token box (which saves on
     *  every keystroke) can pair against whatever is currently typed there. */
    private var autheliaEmailField: EditText? = null

    /**
     * The account the bearer belongs to.
     *
     * Seeded from the stored credential when there is one, otherwise from the
     * profile email already on the device — a sensible default, not a
     * decision: it stays editable because a user may hold a token for one of
     * the other fleet accounts rather than their own.
     *
     * Editing it re-pairs an existing credential so the address follows the
     * token. A blank or malformed address does NOT rewrite storage — the last
     * good pairing is left intact and the error is shown instead, because
     * destroying a working credential mid-keystroke is not a correction.
     */
    private fun autheliaEmailEditor(ctx: android.content.Context): EditText {
        val configs = ConfigsPrefs(ctx)
        val initial = configs.autheliaEmail.ifBlank { prefs.email.trim() }
        return field(ctx, initial) { typed ->
            val address = typed.trim()
            if (configs.autheliaToken.isBlank()) return@field   // nothing to re-pair yet
            val error = configs.setAutheliaCredential(address, configs.autheliaToken)
            autheliaEmailField?.error = error
        }.apply {
            hint = "me@diegonmarcos.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            autheliaEmailField = this
        }
    }

    /**
     * Store the bearer WITH its address, or not at all.
     *
     * [ConfigsPrefs.setAutheliaCredential] is the only writer and refuses a
     * partial write, so the refusal is reported on the field that can fix it
     * rather than swallowed into a token that would read back as absent.
     */
    private fun saveAutheliaToken(token: String) {
        val configs = ConfigsPrefs(requireContext())
        val address = autheliaEmailField?.text?.toString()?.trim().orEmpty()
        val error = configs.setAutheliaCredential(address, token)
        autheliaEmailField?.error = error
        if (error == null) view?.snack("Bearer stored for $address")
    }

    /** Attach an address to a token that predates the pairing rule. Explicit
     *  and user-driven: the app does not get to decide whose token it is. */
    private fun linkOrphanToken() {
        val configs = ConfigsPrefs(requireContext())
        val address = autheliaEmailField?.text?.toString()?.trim().orEmpty()
        val error = configs.adoptOrphanToken(address)
        if (error != null) {
            autheliaEmailField?.error = error
            return
        }
        view?.snack("Stored token linked to $address")
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
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

    // ── OWebAuth · Authelia auto-import ──────────────────────────────────

    /** Live handle to the dialog's token field, so the file picker (which
     *  must be registered on the Fragment, not the dialog) can fill it. */
    private var tokenField: EditText? = null

    /** Set when an auto-import wrote something, so the form above is
     *  redrawn with the new values once the dialog is dismissed. */
    private var importedThisSession = false

    /** "Import from file" inside the dialog — the file holds either the raw
     *  token or a JSON blob with `auth.authelia_token` (the shape already
     *  declared in build.json::ui.import_schema). */
    private val tokenFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val field = tokenField ?: return@registerForActivityResult
            runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                field.setText(extractToken(text))
            }.onFailure {
                field.error = "Could not read that file: ${it.message}"
            }
        }

    /** Raw token, or `auth.authelia_token` / `authelia_token` / `token` out
     *  of a JSON file. Falls back to the trimmed file contents. */
    private fun extractToken(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val o = org.json.JSONObject(trimmed)
            o.optJSONObject("auth")?.optString("authelia_token").orEmpty()
                .ifBlank { o.optString("authelia_token") }
                .ifBlank { o.optString("token") }
                .ifBlank { trimmed }
        }.getOrDefault(trimmed)
    }

    private fun showAutheliaBearerDialog() {
        val ctx = requireContext()
        val endpoint = com.diegonmarcos.superapp.core.ConfigSyncClient.endpoint(
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
        )
        var input: EditText? = null

        importDialog(
            title = "Authelia · Bearer token",
            positive = "Authenticate & Import",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Paste your Authelia bearer token. It is sent once as an Authorization header to:\n$endpoint\n\nThe token is never written to disk, to a log, or to any export — it is used for this one request and then dropped."))
                val field = EditText(ctx).apply {
                    hint = "eyJhbGciOi…"
                    setSingleLine(false)
                    maxLines = 4
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    // Keep the token off the keyboard's learned-words / suggestion store.
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                tokenField = field
                input = field
                body.addView(field)
                body.addView(pickButton(ctx, "Import token from file…") {
                    tokenFilePicker.launch("*/*")
                })
            },
            onGo = { go, status ->
                val token = input?.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    show(status, RED, "✗ Paste a token first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… authenticating and fetching $endpoint")
                    runFetch(status, { go.isEnabled = true }) { fetchWithBearer(token) }
                }
            },
            onDismiss = { tokenField = null },
        ).show()
    }

    /**
     * Fetch on IO, apply on the main thread, report either way.
     *
     * Every authenticated tile ends here. The four routes differ only in the
     * [fetch] lambda — a bearer header, a session cookie, a GitHub token or a
     * clone — and share the apply step, the success/failure wording and the
     * redraw. That is the whole reason the transports were made to return one
     * [ConfigSyncClient.Outcome] type: four import buttons, one place where
     * config is actually written.
     */
    private fun runFetch(
        status: TextView,
        done: () -> Unit,
        fetch: () -> com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome,
    ) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { fetch() }
            when (outcome) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    show(status, RED, "✗ ${outcome.kind}\n${outcome.message}")

                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> {
                    val report = ConfigAutoImport.apply(appCtx, outcome.body)
                    val head = if (report.ok) "✓ Authenticated · ${outcome.bytes} bytes applied"
                               else "✗ Authenticated, but nothing was applied"
                    show(status, if (report.ok) GREEN else RED, "$head\n\n${report.text()}")
                    if (report.ok) {
                        view?.snack("Config imported")
                        importedThisSession = true   // form redraws on dialog dismiss
                    }
                }
            }
            done()
        }
    }

    /** The config route, authenticated by a pasted bearer. */
    private fun fetchWithBearer(token: String) =
        com.diegonmarcos.superapp.core.ConfigSyncClient.fetch(
            baseUrl          = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            pathTemplate     = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            user             = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
            bearer           = token,
            connectTimeoutMs = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs    = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_READ_MS,
        )

    /** The same route, authenticated by the cookie a browser login left behind. */
    private fun fetchWithCookie(cookie: String) =
        com.diegonmarcos.superapp.core.ConfigSyncClient.fetchWithCookie(
            baseUrl          = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            pathTemplate     = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            user             = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
            cookie           = cookie,
            connectTimeoutMs = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs    = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_READ_MS,
        )

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

    // ── Authelia · browser login ─────────────────────────────────────────

    /**
     * Sign in to Authelia in an embedded WebView, then reuse that session's
     * cookie for the config request.
     *
     * Why a WebView and not a Custom Tab: the whole point is to get the cookie
     * back, and a Custom Tab's cookie jar belongs to the browser, not to this
     * app. The WebView's jar is readable through [CookieManager], which is the
     * only reason this flow can hand a credential to [fetchWithCookie].
     *
     * The cookie is never persisted by us — it lives in the WebView jar for as
     * long as the app keeps it and is dropped from memory after the request.
     */
    private fun showAutheliaWebAuthDialog() {
        val ctx = requireContext()
        val endpoint = com.diegonmarcos.superapp.core.ConfigSyncClient.endpoint(
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
        )
        var web: android.webkit.WebView? = null

        val dialog = importDialog(
            title = "Authelia · browser login",
            positive = "Import with this session",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Sign in below. Authelia will redirect back to:\n$endpoint\n\nWhen you are through the login, press Import — the session cookie the browser just earned is used for one request and then dropped. Nothing is written to disk."))
                val view = android.webkit.WebView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380),
                    ).apply { topMargin = dp(ctx, 10) }
                    settings.javaScriptEnabled = true      // Authelia's portal is a JS app
                    settings.domStorageEnabled = true      // and keeps its state in DOM storage
                    webViewClient = android.webkit.WebViewClient()
                }
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                view.loadUrl(endpoint)
                web = view
                body.addView(view)
            },
            onGo = { go, status ->
                val cookie = android.webkit.CookieManager.getInstance().getCookie(endpoint).orEmpty()
                if (cookie.isBlank()) {
                    show(status, RED, "✗ No cookie for $endpoint yet — finish the login above first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… fetching $endpoint with the browser session")
                    runFetch(status, { go.isEnabled = true }) { fetchWithCookie(cookie) }
                }
            },
            // Free the WebView before the shell's redraw tears the view down.
            onDismiss = { web?.destroy(); web = null },
        )
        dialog.show()
    }

    // ── GitHub · OAuth device flow ───────────────────────────────────────

    /**
     * Approve a short code in a browser, then read the artifact out of the
     * vault repo with the token GitHub hands back.
     *
     * The device grant is used rather than a redirect flow because it needs no
     * client secret and no registered redirect URI — the app only ever holds
     * the public client_id, which is why that id sits in build.json as data.
     */
    private fun showGithubDeviceDialog() {
        val ctx = requireContext()
        var pollJob: kotlinx.coroutines.Job? = null

        val dialog = importDialog(
            title = "GitHub · browser login",
            positive = "Start",
            buildBody = { body, _ ->
                body.addView(caption(ctx,
                    if (GithubImport.deviceFlowConfigured())
                        "Press Start. GitHub will show a code to type at github.com/login/device; " +
                        "once you approve it, the artifact is read from " +
                        "${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_REPO} " +
                        "(${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH}) and applied."
                    else
                        "This build has no GitHub OAuth client_id, so the login cannot start.\n\n" +
                        "Register an OAuth App at github.com/settings/developers with Device Flow " +
                        "enabled, put its client_id in build.json under " +
                        "ui.config_source.github_oauth.client_id, and rebuild."))
            },
            onGo = { go, status ->
                if (!GithubImport.deviceFlowConfigured()) {
                    show(status, RED, "✗ No client_id in this build — see above.")
                } else {
                    go.isEnabled = false
                    pollJob = startGithubDeviceFlow(status) { go.isEnabled = true }
                }
            },
            // Stop polling GitHub the moment the user walks away.
            onDismiss = { pollJob?.cancel() },
        )
        dialog.show()
    }

    /** Request a code, show it, poll until approved, then fetch and apply. */
    private fun startGithubDeviceFlow(status: TextView, done: () -> Unit): kotlinx.coroutines.Job =
        viewLifecycleOwner.lifecycleScope.launch {
            show(status, NEUTRAL, "… asking GitHub for a device code")
            val code = withContext(Dispatchers.IO) { GithubImport.requestDeviceCode() }
                .getOrElse {
                    show(status, RED, "✗ ${it.message}")
                    done(); return@launch
                }

            show(status, NEUTRAL,
                "Enter this code at ${code.verificationUri}\n\n    ${code.userCode}\n\n" +
                "Waiting for approval… (the code expires in ${code.expiresInSeconds / 60} min)")
            // Open the page for them; if no browser handles it the code above is
            // still on screen and selectable, so the flow is not blocked on this.
            runCatching {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(code.verificationUri),
                ))
            }

            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var interval = code.intervalSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(interval)
                when (val step = withContext(Dispatchers.IO) { GithubImport.pollForToken(code.deviceCode) }) {
                    is GithubImport.Step.Token -> {
                        show(status, NEUTRAL, "✓ Approved · reading ${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH}")
                        runFetch(status, done) { GithubImport.fetchArtifact(step.accessToken) }
                        return@launch
                    }
                    is GithubImport.Step.Failed -> {
                        show(status, RED, "✗ ${step.message}")
                        done(); return@launch
                    }
                    is GithubImport.Step.Pending -> {
                        // slow_down means GitHub wants a longer gap, and ignoring
                        // it gets the whole flow rate-limited.
                        if (step.message.contains("slow down")) interval += 5_000L
                        show(status, NEUTRAL,
                            "Enter this code at ${code.verificationUri}\n\n    ${code.userCode}\n\n${step.message}")
                    }
                }
            }
            show(status, RED, "✗ The code expired before it was approved.")
            done()
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

    private fun showGithubSshDialog() {
        val ctx = requireContext()
        val repo = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_REPO
        val path = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH
        var passField: EditText? = null

        val dialog = importDialog(
            title = "GitHub · SSH key",
            positive = "Clone & Import",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Paste the private key that has read access to $repo, or import it from a file (a previously exported config works — the key is read from ssh.vault_repo_key).\n\nThis route CLONES the repository: GitHub offers no file-read over SSH, so the whole repo is fetched shallow and bare into the cache and deleted immediately after $path is read. Nothing is checked out. If you have a token, the GitHub login tile fetches only the one file."))

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
                    runFetch(status, { go.isEnabled = true }) {
                        GitSshVault.fetchArtifact(cacheDir, key, pass)
                    }
                }
            },
            onDismiss = { sshKeyField = null },
        )
        dialog.show()
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

    private fun pickButton(ctx: android.content.Context, currentLabel: String, onClick: () -> Unit): View {
        val tv = android.widget.TextView(ctx).apply {
            text = currentLabel
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 4) }
        tv.layoutParams = lp
        return tv
    }

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

    companion object {
        /** Import-status colours. GREEN is the "authenticated + applied"
         *  state the auto-import is required to show explicitly. */
        private val GREEN   = 0xFF16A34A.toInt()
        private val RED     = 0xFFDC2626.toInt()
        private val NEUTRAL = 0xFF9CA3AF.toInt()

        /**
         * The AI page's existing route. It is a LINK, not a copy: `config/ai`
         * has no `action` of its own and resolves through
         * SectionPages → AiFragment, so this reuses the one implementation.
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

        /** Advisory shape check for the birth field. Range/real-calendar
         *  validity is deliberately not checked — the field is optional and a
         *  false rejection is worse than a typo here. */
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** DD-MM-YYYY, the shape people actually type here. Only used to
         *  SUGGEST the ISO reordering in the field's advisory error. */
        private val DMY_PATTERN = Regex("^(\\d{2})-(\\d{2})-(\\d{4})$")

        private const val CONNECT_TEXT =
            "None of this is part of your profile and none of it is ever uploaded — " +
            "the sync document carries contact fields only.\n\n" +
            "The Authelia bearer goes into the same encrypted store the config import " +
            "writes (AES-256-GCM, key in the Android keystore), at the same " +
            "auth.authelia_token path, so there is one bearer on this device rather " +
            "than two that can disagree.\n\n" +
            "The Authelia bearer is stored WITH the account email it belongs to, " +
            "and cannot be stored without one. Authelia issues tokens per account " +
            "and this fleet has several, so a token on its own cannot say whose " +
            "access it carries — which is exactly what you need to know when a " +
            "request comes back refused. A token with no address is treated as not " +
            "stored at all.\n\n" +
            "Stored values are never displayed again. An empty box means unchanged."

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
            "is not being used. Check the address above is the account the token " +
            "belongs to, then link it. Nothing was changed or deleted."

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
