package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [ChunkedIdReadsSourceTest] says:
 */
class DestroyChecksTheFolderWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theDestroyRequestCarriesTheSURVIVORS_notTheWaveItWasHanded() {
        // THE line of #122. Handing `emailIds` (or any re-derivation of it) to the destroyer is
        // the defect itself: the rescued message is destroyed in the folder it was rescued into.
        assertEquals(
            "destroyAll must destroy the ids the server just confirmed are still in the expected " +
                "folder — never the wave it was handed (Codeberg #122)",
            listOf("val result = jmapDestroyAll(ctx, stillThere)"),
            codeLinesNaming(bodyOf("destroyAll"), "jmapDestroyAll("),
        )
    }

    /**
     * The door the fix of #122 left open: the destroy of ONE draft. `destroyDraft`'s JMAP branch
     */
    @Test fun aDraftIsDestroyedThroughTheSameCheck_neverStraightOnTheWire() {
        val body = bodyOf("destroyDraft")
        assertEquals(
            "destroyDraft's JMAP branch must destroy through `destroyAll`, naming the DRAFT'S OWN " +
                "folder (`draftMailboxOf`, i.e. the cached row's mailbox else the Drafts role) so " +
                "a draft moved out of Drafts since the composer read its id is spared instead of " +
                "destroyed in its new folder. Whole line, arguments included: handing `null`, the " +
                "Drafts role alone, or a folder read from anywhere else is the defect back. " +
                "Body was:\n$body",
            listOf("destroyAll(credentials, listOf(emailId), draftMailboxOf(credentials, emailId))"),
            codeLinesNaming(body, "destroyAll("),
        )
        assertEquals(
            "and nothing on this path may reach the unchecked destroyer: `jmapDestroyAll` opposes " +
                "NOTHING, and calling it here — even after computing the guard — destroys the " +
                "draft wherever it now is. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "jmapDestroyAll("),
        )
    }

    @Test fun everyWaveIsCheckedAgainstTheServerBeforeItIsDestroyed() {
        assertEquals(
            "destroyAll must locate its ids on the server, with the folder the caller expects, " +
                "before any Email/set destroy",
            listOf("val stillThere = idsStillOnlyIn(ctx, emailIds, expectedMailboxId)"),
            codeLinesNaming(bodyOf("destroyAll"), "idsStillOnlyIn("),
        )
    }

    @Test fun theCheckHandsItsVerdictToTheFunctionThatCanBeRun() {
        val body = bodyOf("idsStillOnlyIn")
        assertEquals(
            "the location read must be the ids-only + mailboxIds Email/get, asked for THIS " +
                "account's session",
            listOf("val located = client.mailboxIdsOf(ctx.session, ctx.accountId, emailIds, ctx.auth)"),
            codeLinesNaming(body, "mailboxIdsOf("),
        )
        assertEquals(
            "the verdict must be TrashPurge.destroyableIds — a pure function a test can execute, " +
                "not a predicate written inline where nothing can reach it",
            listOf("return TrashPurge.destroyableIds(emailIds, expectedMailboxId, located)"),
            codeLinesNaming(body, "destroyableIds("),
        )
    }

    @Test fun aFailedCheckDestroysNothingAndPropagates() {
        // The red line of #122: swallowing the location read and falling back on the requested
        // wave brings the data loss back whole, and silently, at the first network hiccup.
        // `client.destroy` throws on transport failure by design and the worker retries three
        // times; the check must behave the same way.
        val swallowers = listOf("runCatching", "getOrElse", "getOrNull", "getOrDefault", "catch (")
        listOf("destroyAll", "idsStillOnlyIn").forEach { function ->
            val body = bodyOf(function)
            val caught = swallowers.filter { swallower ->
                codeLinesNaming(body, swallower).isNotEmpty()
            }
            assertEquals(
                "$function must let a failed location read propagate (the worker retries); " +
                    "falling back on the requested ids re-opens #122 without a trace",
                emptyList<String>(), caught,
            )
        }
    }

    @Test fun thePurgeTellsTheDestroyWhichFolderItFroze() {
        val body = bodyOf("purgeSnapshot")
        assertEquals(
            "the expected folder is the one the snapshot froze — the head row, not the folder " +
                "currently on screen",
            listOf("val expectedMailboxId = head?.mailboxId"),
            codeLinesNaming(body, "head?.mailboxId"),
        )
        assertEquals(
            "the purge must hand destroyAll the folder its snapshot named",
            listOf("val result = destroyAll(credentials, ids, expectedMailboxId, expectedUidValidity)"),
            codeLinesNaming(body, "destroyAll("),
        )
    }

    @Test fun aPurgeThatSparedAnythingPutsTheListBackInFrontOfTheServer() {
        // The other half of #122, and the half an Empty trash was missing: a spared message is
        // alive on the server but its row was evicted when the purge was confirmed, and it never
        val body = bodyOf("purgeSnapshot")
        assertEquals(
            "every wave's refusals must be noticed — spared ids come back in BulkResult.failed",
            listOf("if (result.failed.isNotEmpty()) refusedAny = true"),
            codeLinesNaming(body, "result.failed"),
        )
        assertEquals(
            "a purge that spared anything must drop the sync cursors, so the next refresh " +
                "re-queries the folder in full and the spared message comes back into view",
            listOf("if (refusedAny) resetSyncState()"),
            codeLinesNaming(body, "resetSyncState()"),
        )
    }

    @Test fun aSparedWaveSaysSoInTheSyncLog() {
        // A guard doing its job and a server that never reports `mailboxIds` at all look IDENTICAL
        // from outside: both spare, both destroy nothing. The second would be total and permanent,
        val body = bodyOf("destroyAll")
        assertEquals(
            "the spared ids must be logged, and only when there are some",
            listOf("if (spared.isNotEmpty()) {"),
            codeLinesNaming(body, "spared.isNotEmpty()"),
        )
        assertEquals(
            "it belongs in the sync journal, where a bench log is read",
            listOf("\"MailSync\","),
            codeLinesNaming(body, "MailSync"),
        )
        assertEquals(
            "the line must name the account AND the folder (folder ids collide between two " +
                "accounts of one server, #31) and count what was spared",
            listOf("\"destroy spared \${credentials.id}/\$expectedMailboxId: \${spared.size} of \" +"),
            codeLinesNaming(body, "destroy spared"),
        )
        assertEquals(
            "and it must name the ids themselves, on the SAME line — one line per id is what " +
                "makes a wave of a thousand unreadable",
            listOf("\"\${emailIds.size} not in that folder alone: \${spared.joinToString()}\","),
            codeLinesNaming(body, "joinToString"),
        )
    }

    @Test fun theWaveLoopStillDrainsWhateverTheServerSays() {
        // Invariant, not decoration: read a wave → destroy → delete THE WHOLE WAVE. Ids the check
        // spared must leave the snapshot too, or the loop reads them again forever; and the delete
        // must stay behind the destroy, so a killed process resumes on exactly the ids left.
        val lines = bodyOf("purgeSnapshot").lines().map { it.trim() }
        val destroy = lines.indexOfFirst { it == "val result = destroyAll(credentials, ids, expectedMailboxId, expectedUidValidity)" }
        val delete = lines.indexOfFirst { it == "purgeSnapshotDao.deleteIds(purgeId, credentials.id, ids)" }
        assertTrue("the purge no longer destroys the wave it read: $lines", destroy >= 0)
        assertTrue(
            "the purge must delete the WHOLE wave it read (`ids`), spared ids included, or the " +
                "loop re-reads them forever",
            delete >= 0,
        )
        assertTrue("deleteIds must stay AFTER the destroy (resumability)", delete > destroy)
    }
}
