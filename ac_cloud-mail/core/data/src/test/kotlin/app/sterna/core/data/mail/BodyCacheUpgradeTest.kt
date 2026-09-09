package app.sterna.core.data.mail

import app.sterna.core.data.db.BODY_CACHE_CLEAR_SQL
import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * The upgrade purge of the cached message bodies: ONCE per threshold crossed, and nothing on any
 */
class BodyCacheUpgradeTest {

    /**
     * THE THRESHOLD ITSELF, written out as a literal — the parade `IncognitoImeOptionsTest`
     */
    @Test fun `the threshold is the release that needed it, by number`() {
        assertEquals(168, BODY_CACHE_PURGE_VERSION)
    }

    /**
     * THE SECOND THRESHOLD, same parade, same reason. Every rule below is about the SHAPE of the
     */
    @Test fun `the second threshold is the release that needs it, by number`() {
        assertEquals(170, BODY_CACHE_PURGE_VERSION_2)
    }

    /**
     * Bare literal on both sides, like its two elders. 172 = the `versionCode` of the cut that
     */
    @Test fun `the third threshold is the release that needs it, by number`() {
        assertEquals(172, BODY_CACHE_PURGE_VERSION_3)
    }

    @Test fun `arriving at the threshold purges, and records the version it purged for`() {
        assertEquals(
            BODY_CACHE_PURGE_VERSION,
            bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = BODY_CACHE_PURGE_VERSION),
        )
    }

    /**
     * CROSSED, not equalled. Someone who skips the release that introduced the need — 1.4.6
     * straight to 1.4.9 — still arrives with a cache written before it, and must purge once.
     */
    @Test fun `jumping over the threshold purges on arrival`() {
        assertEquals(169, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 169))
        assertEquals(200, bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 200))
    }

    /**
     * THE BUG. Every version bump used to purge, for ever: `currentVersion > purgedForVersion`.
     */
    @Test fun `a version bump that crosses no threshold never purges`() {
        assertNull(
            "168 is already purged for, and 169 has no need of its own",
            bodyCachePurgeVersion(purgedForVersion = 168, currentVersion = 169),
        )
        assertNull(bodyCachePurgeVersion(purgedForVersion = 172, currentVersion = 173))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 200, currentVersion = 999))
    }

    /**
     * THE DEFECT THIS SECOND THRESHOLD ANSWERS, in bare literals on both sides — no constant,
     */
    @Test fun `the 1_4_9 install purges on arriving at the read-receipt release`() {
        assertEquals(
            "a 1.4.9 install must purge on arrival, or no read-receipt banner on mail already read",
            170,
            bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 170),
        )
    }

    /**
     * THE DEFECT THE THIRD THRESHOLD ANSWERS, in bare literals on both sides.
     */
    @Test fun `the 1_5_1 install purges on arriving at the origin-sender release`() {
        assertEquals(
            "a 1.5.1 install must purge on arrival, or no Original sender line on mail already cached",
            172,
            bodyCachePurgeVersion(purgedForVersion = 171, currentVersion = 172),
        )
    }

    /** CROSSED, not equalled — for the third threshold too: 171 straight to 173 purges once. */
    @Test fun `jumping over the third threshold purges on arrival`() {
        assertEquals(173, bodyCachePurgeVersion(purgedForVersion = 171, currentVersion = 173))
        assertEquals(300, bodyCachePurgeVersion(purgedForVersion = 171, currentVersion = 300))
    }

    /** Once per threshold. Having purged for 172, nothing purges again at 172, 173, or ever. */
    @Test fun `the third threshold purges once and never again`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 172, currentVersion = 172))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 172, currentVersion = 173))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 172, currentVersion = 999))
    }

    /** CROSSED, not equalled — for the second threshold too: 169 straight to 171 purges once. */
    @Test fun `jumping over the second threshold purges on arrival`() {
        assertEquals(171, bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 171))
        assertEquals(200, bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 200))
    }

    /**
     * Once per threshold. Having purged for 170, nothing purges again for 170 — at 170, at 171,
     */
    @Test fun `the second threshold purges once and never again`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 170))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 171))
        assertEquals(
            "999 is past the THIRD threshold, so it purges for that one — not a second time for 170",
            999,
            bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 999),
        )
    }

    /** A fresh install lands past both thresholds and purges once — an empty cache, once. */
    @Test fun `a fresh install arriving at the second threshold purges exactly once`() {
        assertEquals(170, bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 170))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 170))
    }

    /** The first threshold keeps behaving exactly as it did, literals on both sides. */
    @Test fun `the first threshold still behaves exactly as before`() {
        assertEquals(168, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 168))
        assertEquals(169, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 169))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 168, currentVersion = 168))
    }

    /** The witness. Same version, second start: nothing to do. */
    @Test fun `the same version does not purge again`() {
        assertNull(
            bodyCachePurgeVersion(
                purgedForVersion = BODY_CACHE_PURGE_VERSION,
                currentVersion = BODY_CACHE_PURGE_VERSION,
            ),
        )
    }

    /** A build older than the threshold has nothing to do with it — it is not why the purge exists. */
    @Test fun `a version bump below the threshold does nothing`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 167))
    }

    /** A downgrade (a sideloaded older build) purges nothing: its own bodies are its own. */
    @Test fun `an older build does not purge`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 166))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 166))
    }

    /**
     * What the purge is allowed to delete, executed against real in-memory SQLite and built
     */
    @Test fun `the purge empties the body cache and touches nothing else`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
                st.executeUpdate(EMAILS_CREATE_SQL)
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m1','accA','{\"id\":\"m1\"}','{}',1000)",
                )
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m2','accB','{\"id\":\"m2\"}','{}',2000)",
                )
                st.executeUpdate(
                    "INSERT INTO emails VALUES('m1','accA','inbox',NULL,'Weekly digest',NULL," +
                        "NULL,NULL,NULL,0,0,0,1000)",
                )
            }

            db.createStatement().use { it.executeUpdate(BODY_CACHE_CLEAR_SQL) }

            assertEquals("every account's bodies go, not just one", 0, count(db, "email_bodies"))
            assertEquals("the message list must survive the purge", 1, count(db, "emails"))
        }
    }

    private fun count(db: java.sql.Connection, table: String): Int =
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    /**
     * The life of one install, played out: restarts, then update after update. One purge per
     */
    @Test fun `the purge happens exactly once per threshold over the life of an install`() {
        var recorded = 0
        val purges = mutableListOf<Int>()
        val launches = listOf(166, 166, 167, 168, 168, 169, 170, 170, 171, 172, 172, 173)
        for (version in launches) {
            bodyCachePurgeVersion(recorded, version)?.let {
                purges += it
                recorded = it
            }
        }
        assertEquals(listOf(168, 170, 172), purges)
    }
}
