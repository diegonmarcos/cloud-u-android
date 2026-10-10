package com.diegonmarcos.superapp.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitSearchTags
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kit search bar Cloud ▸ Apps pins under its grid: the placeholder while empty, the clear (×)
 * only while there is text, and the keyboard's Go reaching the host — under the superapp's real
 * palette, through the same kitComposeView the page hosts it in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KitSearchBarTest : KitPageHarness() {

    private var submitted = 0
    private var last = ""

    private fun bar() = showKit {
        var q by remember { mutableStateOf("") }
        KitSearchBar(query = q, onQueryChange = { q = it; last = it }, onSubmit = { submitted++ },
            placeholder = "Search apps")
    }

    @Test fun `empty bar shows the placeholder and no clear button`() {
        bar()
        compose.onNodeWithText("Search apps").assertExists()
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(0)
    }

    @Test fun `typing reaches the host and brings the clear button, which empties it`() {
        bar()
        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("mai")
        assertEquals("mai", last)
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(1)
        compose.onNodeWithTag(KitSearchTags.CLEAR).performClick()
        assertEquals("", last)
        compose.onAllNodesWithTag(KitSearchTags.CLEAR).assertCountEquals(0)
    }

    @Test fun `the keyboard Go action submits`() {
        bar()
        compose.onNodeWithTag(KitSearchTags.FIELD).performTextInput("vault")
        compose.onNodeWithTag(KitSearchTags.FIELD).performImeAction()
        assertEquals(1, submitted)
    }
}
