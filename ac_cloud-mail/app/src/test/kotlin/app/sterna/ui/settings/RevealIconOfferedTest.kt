package app.sterna.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DECISION, RUN — not a source read. [revealIconOffered] is the whole of "is there an eye on
 */
class RevealIconOfferedTest {

    @Test fun `a secret field with something typed in it offers the eye`() {
        assertTrue(
            "a password field holding 'hunter2' must offer the reveal icon: there is text under " +
                "the dots and no other way to check it before saving",
            revealIconOffered(true, "hunter2"),
        )
    }

    @Test fun `a secret field holding only a space still offers the eye`() {
        assertTrue(
            "a password field holding a single space must offer the reveal icon: there is a " +
                "character under the dots, and the eye is the only way to see that it is a stray " +
                "space. The rule is isNotEmpty, not isNotBlank — this is about what is DISPLAYED. " +
                "⛔ It says nothing about saving, and must not be read as an argument to change " +
                "saving: AccountsViewModel.kt:259 guards 'if (password.isNotBlank())' and drops an " +
                "all-blank value on purpose. Relaxing THAT guard to isNotEmpty would overwrite the " +
                "stored secret with a space — unrecoverable, account dead.",
            revealIconOffered(true, " "),
        )
    }

    @Test fun `an empty secret field offers no eye at all`() {
        assertFalse(
            "an EMPTY password field must offer no reveal icon — this is #118. The account screen " +
                "opens with this field blank on purpose (blank means 'keep the stored secret'), so " +
                "an eye there is a control that cannot do anything when pressed",
            revealIconOffered(true, ""),
        )
    }

    @Test fun `a plain field never offers the eye, whatever it holds`() {
        assertFalse(
            "a non-secret field with text ('https://mail.example.org') must offer no reveal icon: " +
                "it is not masked, there is nothing to unmask",
            revealIconOffered(false, "https://mail.example.org"),
        )
        assertFalse(
            "an empty non-secret field must offer no reveal icon either",
            revealIconOffered(false, ""),
        )
    }
}
