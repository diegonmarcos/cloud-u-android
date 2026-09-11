package com.diegonmarcos.superapp.updater

/**
 * Where a candidate APK sits relative to what is already installed.
 *
 * ## Why this is its own file, with no imports
 * The downgrade refusal in [Fleet.commit] is the guard that stands between the
 * owner and an update pass that walks his phone BACKWARDS - the failure #17 was
 * filed for ("superapp installs a STALE APK causing downgrade"). It was four
 * words of an `if` in the middle of a 60-line function that needs a [Context],
 * a staged file and a live PackageManager to reach, so nothing could execute it
 * and no test did. This file imports NOTHING - not Android, not the rest of the
 * module - so a plain JVM unit test can drive every branch of it.
 *
 * Moving the comparison here does not change a single outcome: [Fleet.commit]
 * asks this question instead of spelling it out, and answers it the same way.
 */
object VersionOrder {

    /** How a candidate compares to the installed build. */
    enum class Order {
        /** Candidate is ahead of what is installed: a real update. */
        NEWER,

        /** Same versionCode. Re-installing the same code is how a damaged
         *  install is repaired, so this is NOT refused. */
        SAME,

        /** Candidate is BEHIND what is installed. This is the one to refuse. */
        OLDER,

        /**
         * One of the two codes could not be read.
         *
         * A null candidate means the staged APK's manifest would not parse; a
         * null installed means the package is not on the device (a first
         * install) or PackageManager would not say. Neither is a downgrade, and
         * neither may be *reported* as an ordering - saying NEWER here is how a
         * guard comes to wave through the exact artifact it exists to stop.
         */
        UNKNOWN,
    }

    /**
     * Order [candidateCode] against [installedCode]. Nulls are UNKNOWN, never
     * coerced to 0 - a missing version is not version zero, and treating it as
     * one makes every unreadable APK look like a downgrade.
     */
    fun compare(candidateCode: Long?, installedCode: Long?): Order = when {
        candidateCode == null || installedCode == null -> Order.UNKNOWN
        candidateCode > installedCode -> Order.NEWER
        candidateCode == installedCode -> Order.SAME
        else -> Order.OLDER
    }

    /**
     * Must this candidate be refused because it would move the device backwards?
     *
     * ONLY [Order.OLDER] refuses. UNKNOWN deliberately does not: that preserves
     * the behaviour [Fleet.commit] has always had - when the manifest cannot be
     * read we install anyway and let PackageInstaller be the judge, because it
     * has the real answer and we only have a guess.
     */
    fun isDowngrade(candidateCode: Long?, installedCode: Long?): Boolean =
        compare(candidateCode, installedCode) == Order.OLDER
}
