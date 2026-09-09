package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. `MailRepository` needs Room, an Android `Context` and a live
 */
class LocalSearchAccountScopeWiringTest {

    @Test fun `searchIndex hands the DAO the account ids it has just read, on every call`() {
        // The two arguments that carry the value are `accountIds` and `accountIds.size`, from the
        // SAME local: a literal, a stale field or a cached list would type-check and pass every
        // SQL case. And the read sits INSIDE the function rather than in a field, because the
        // sub-account reconcile (issue #31) adds and prunes accounts mid-session.
        assertEquals(
            "MailRepository.searchIndex is no longer, line for line, what this test was written " +
                "against. An INSERTED or LENGTHENED line is what this pin exists to catch: the " +
                "account scope only works if this function fills it on every call.",
            listOf(
                "{",
                "val match = ftsMatch(query) ?: return emptyList()",
                "val accountIds = accountStore.accounts().map { it.id }",
                "return emailFtsDao.search(match, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)",
                ".map { it.toEmail() }",
                "}",
            ),
            DaoQuerySource.mailFunctionBody("MailRepository", "searchIndex")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
