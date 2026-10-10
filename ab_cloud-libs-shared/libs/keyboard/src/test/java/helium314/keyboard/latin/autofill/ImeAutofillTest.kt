// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autofill

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 3 of the fleet autofill (a0_docs/eng-specs/autofill-3-tier.md), pure JVM: EditorInfo → mode,
 * the owner's two-row arbitration (inline present/absent × normal/suppressed), and the keyboard's own
 * candidates from the Cloud Account SOT. Fixtures are obvious fakes.
 */
class ImeAutofillTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private fun t(variation: Int, flags: Int = 0) = text or variation or flags

    // ── EditorInfo → mode ───────────────────────────────────────────────

    @Test fun every_password_variation_is_suppressed() {
        listOf(
            t(InputType.TYPE_TEXT_VARIATION_PASSWORD),
            t(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            t(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).forEach { type ->
            val d = FieldPolicy.decide(type, 0)
            assertTrue("0x${type.toString(16)}", d.suppressed)
            assertEquals("password", d.reason)
            assertNull(d.key)
        }
    }

    @Test fun one_time_codes_and_cards_are_suppressed_by_hint_or_autofill_hint() {
        assertEquals("otp", FieldPolicy.decide(InputType.TYPE_CLASS_NUMBER, 0, listOf("one-time-code")).reason)
        assertEquals("otp", FieldPolicy.decide(InputType.TYPE_CLASS_NUMBER, 0, listOf("smsOTPCode")).reason)
        assertEquals("otp", FieldPolicy.decide(text, 0, listOf("Enter the verification code")).reason)
        assertEquals("otp", FieldPolicy.decide(text, 0, listOf("Código de verificación")).reason)
        assertEquals("otp", FieldPolicy.decide(text, 0, listOf("Bestätigungscode")).reason)
        assertEquals("card", FieldPolicy.decide(InputType.TYPE_CLASS_NUMBER, 0, listOf("Card number")).reason)
        assertEquals("card", FieldPolicy.decide(text, 0, listOf("cc-number")).reason)
        assertEquals("password", FieldPolicy.decide(text, 0, listOf("Kennwort")).reason)
        assertEquals("password", FieldPolicy.decide(text, 0, listOf("current-password")).reason)
    }

    @Test fun no_personalised_learning_is_suppressed() {
        val d = FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_NORMAL), EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(d.suppressed)
        assertEquals("no_learning", d.reason)
    }

    @Test fun normal_fields_map_to_sot_keys() {
        assertEquals("email", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS), 0).key)
        assertEquals("email", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS), 0).key)
        assertEquals("tel", FieldPolicy.decide(InputType.TYPE_CLASS_PHONE, 0).key)
        assertEquals("address-line1", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS), 0).key)
        assertEquals("name", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_PERSON_NAME), 0).key)
        assertEquals("postal-code", FieldPolicy.decide(InputType.TYPE_CLASS_NUMBER, 0, listOf("ZIP code")).key)
        assertEquals("postal-code", FieldPolicy.decide(text, 0, listOf("PLZ")).key)
        assertEquals("given-name", FieldPolicy.decide(text, 0, listOf("Vorname")).key)
        assertEquals("family-name", FieldPolicy.decide(text, 0, listOf("Apellido")).key)
        assertEquals("address-level2", FieldPolicy.decide(text, 0, listOf("Ciudad")).key)
        val free = FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_FLAG_MULTI_LINE), 0, listOf("Message"))
        assertFalse(free.suppressed); assertNull(free.key); assertTrue("multi-line free text takes snippets", free.snippets)
        assertFalse("a URL bar takes no snippets", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_URI), 0).snippets)
    }

    // ── the owner's two rows: inline present/absent × normal/suppressed ──

    @Test fun row_arbitration_matrix() {
        // inline present, normal: Vault on row 1, our own row 2 under it — separate rows, never mixed
        assertEquals(RowState(row1 = true, row2 = true, row2Sot = true), SuggestionRows.decide(2, FieldMode.NORMAL, 3))
        // inline present, suppressed (password/OTP): ONLY row 1 shows
        assertEquals(RowState(row1 = true, row2 = false, row2Sot = false), SuggestionRows.decide(2, FieldMode.SUPPRESSED, 3))
        // inline absent, normal: row 1 collapses to zero height, row 2 as usual
        assertEquals(RowState(row1 = false, row2 = true, row2Sot = true), SuggestionRows.decide(0, FieldMode.NORMAL, 3))
        assertEquals(RowState(row1 = false, row2 = true, row2Sot = false), SuggestionRows.decide(0, FieldMode.NORMAL, 0))
        // inline absent, suppressed: nothing of ours, nothing of Vault's
        assertEquals(RowState(row1 = false, row2 = false, row2Sot = false), SuggestionRows.decide(0, FieldMode.SUPPRESSED, 0))
    }

    // ── row 2 candidates ────────────────────────────────────────────────

    private val spain = AutofillProfile(1, "Testy - Spain-A", isDefault = true,
        fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson", "family_name2" to "Example"),
        addresses = listOf(AutofillAddress(isDefault = true, fields = mapOf("postal_code" to "00000", "country" to "ES"))),
        contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "testy@example.invalid", isDefault = true),
            AutofillContact(kind = ContactKind.TEL, value = "+00 600 000 000", type = "mobile")))
    private val germany = AutofillProfile(2, "Testy - Germany-A", fields = mapOf("given_name" to "Testy", "family_name" to "Fakeson"),
        addresses = listOf(AutofillAddress(fields = mapOf("postal_code" to "00000", "country" to "DE"))),
        contacts = listOf(AutofillContact(kind = ContactKind.EMAIL, value = "de@example.invalid"), AutofillContact(kind = ContactKind.EMAIL, value = "de2@example.invalid")))
    private val snippets = listOf(Snippet(1, "About me", "Line one 🙂\nLine two"), Snippet(2, "", " "))
    private val multi = t(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_FLAG_MULTI_LINE)

    @Test fun candidates_match_the_field_with_a_profile_picker() {
        val email = FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS), 0)
        val r = ImeCandidates.row(email, listOf(germany, spain), snippets)
        assertEquals("default profile first", listOf("Testy - Spain-A", "Testy - Germany-A"), r.profiles)
        assertTrue(r.switcher)
        assertEquals(listOf("testy@example.invalid"), r.values)
        assertEquals("the picker switches profile", listOf("de@example.invalid", "de2@example.invalid"), ImeCandidates.row(email, listOf(germany, spain), snippets, active = 1).values)
        assertEquals("and wraps", r.values, ImeCandidates.row(email, listOf(germany, spain), snippets, active = 2).values)
        assertEquals(listOf("+00 600 000 000"), ImeCandidates.build(FieldPolicy.decide(InputType.TYPE_CLASS_PHONE, 0), listOf(germany, spain), snippets))
        assertFalse("one profile with a phone: no picker", ImeCandidates.row(FieldPolicy.decide(InputType.TYPE_CLASS_PHONE, 0), listOf(germany, spain), snippets).switcher)
        assertEquals(listOf("00000"), ImeCandidates.build(FieldPolicy.decide(text, 0, listOf("Postal code")), listOf(spain), snippets))
        assertEquals(listOf("Testy Fakeson Example"), ImeCandidates.build(FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_PERSON_NAME), 0), listOf(spain), snippets))
        assertEquals("typed prefix narrows", listOf("de2@example.invalid"), ImeCandidates.row(email, listOf(germany), snippets, typed = "de2").values)
    }

    @Test fun snippets_only_on_multi_line_fields() {
        assertTrue(FieldPolicy.decide(multi, 0).snippets)
        assertFalse("single-line free text takes no snippets", FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_NORMAL), 0).snippets)
        assertEquals(listOf("Line one 🙂\nLine two"), ImeCandidates.build(FieldPolicy.decide(multi, 0), listOf(spain), snippets))
        assertTrue(ImeCandidates.build(FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_NORMAL), 0), listOf(spain), snippets).isEmpty())
    }

    @Test fun suppressed_and_id_fields_get_nothing() {
        val pw = FieldPolicy.decide(t(InputType.TYPE_TEXT_VARIATION_PASSWORD), 0)
        assertTrue(ImeCandidates.build(pw, listOf(spain), snippets).isEmpty())
        val otp = FieldPolicy.decide(text, 0, listOf("one-time-code"))
        assertTrue(ImeCandidates.build(otp, listOf(spain), snippets).isEmpty())
        for (hint in listOf("DNI / NIE", "Ausweisnummer", "Passport number", "CPF", "RG")) {
            val d = FieldPolicy.decide(text, 0, listOf(hint))
            assertEquals(hint, "id", d.reason)
            assertTrue(hint, ImeCandidates.build(d, listOf(spain), snippets).isEmpty())
        }
    }
}
