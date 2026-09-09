package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision behind Codeberg #122, RUN rather than restated: given what the server says about a
 */
class DestroyableIdsTest {

    private val trash = "mbTrash"

    @Test fun aMessageAnotherClientMovedOutOfTheTrashIsSpared() {
        // m2 was rescued to the Inbox while the destroy waited for its window.
        val located = mapOf(
            "m1" to setOf(trash),
            "m2" to setOf("mbInbox"),
        )

        assertEquals(
            listOf("m1"),
            TrashPurge.destroyableIds(listOf("m1", "m2"), trash, located),
        )
    }

    @Test fun aMessageFiledSOMEWHERE_ELSE_withoutLeavingTheTrashIsSpared() {
        // The set is the whole point: another client can add a folder without removing the Trash.
        // `contains(trash)` would be true here, and destroying would take the copy the user just
        // filed into their own folder with it.
        val located = mapOf("m1" to setOf(trash, "mbKeep"))

        assertEquals(
            emptyList<String>(),
            TrashPurge.destroyableIds(listOf("m1"), trash, located),
        )
    }

    @Test fun anIdTheServerDidNotReturnIsSpared() {
        // notFound: already destroyed elsewhere. Nothing to destroy, and nothing that could be
        // lost by leaving it alone.
        assertEquals(
            listOf("m1"),
            TrashPurge.destroyableIds(listOf("m1", "gone"), trash, mapOf("m1" to setOf(trash))),
        )
    }

    @Test fun aWaveStillWhollyInTheFolderIsWhollyDestroyed() {
        val ids = (1..5).map { "m$it" }
        val located = ids.associateWith { setOf(trash) }

        assertEquals(ids, TrashPurge.destroyableIds(ids, trash, located))
    }

    @Test fun noExpectedFolderDestroysNothing() {
        // A destroy enqueued by a version predating the folder key, or a cached row that named no
        // folder: the order cannot be verified, so it is not executed.
        val located = mapOf("m1" to setOf(trash), "m2" to setOf(trash))

        assertEquals(emptyList<String>(), TrashPurge.destroyableIds(listOf("m1", "m2"), null, located))
        assertEquals(emptyList<String>(), TrashPurge.destroyableIds(listOf("m1", "m2"), "", located))
        assertEquals(emptyList<String>(), TrashPurge.destroyableIds(listOf("m1", "m2"), "  ", located))
    }

    @Test fun aServerThatAnsweredNothingDestroysNothing() {
        // The shape a swallowed location read would take. Nothing may be destroyed on it.
        assertEquals(
            emptyList<String>(),
            TrashPurge.destroyableIds(listOf("m1", "m2"), trash, emptyMap()),
        )
    }

    @Test fun theExpectedFolderIsMatchedExactly_neverByPrefixOrMembership() {
        // Mailbox ids collide between accounts of one server (issue #31) and are opaque strings:
        // "mbTrash2" is another folder, not this one.
        val located = mapOf("m1" to setOf("mbTrash2"), "m2" to setOf(trash))

        assertEquals(listOf("m2"), TrashPurge.destroyableIds(listOf("m1", "m2"), trash, located))
    }

    @Test fun theSurvivorsKeepTheWavesOwnOrderAndNothingIsAdded() {
        // The result feeds an Email/set destroy: it may only ever be a SUBSET of what was asked,
        // in the same order — never an id the server volunteered.
        val located = mapOf(
            "m1" to setOf(trash),
            "m2" to setOf("mbInbox"),
            "m3" to setOf(trash),
            "stranger" to setOf(trash),
        )

        assertEquals(
            listOf("m1", "m3"),
            TrashPurge.destroyableIds(listOf("m1", "m2", "m3"), trash, located),
        )
    }
}
