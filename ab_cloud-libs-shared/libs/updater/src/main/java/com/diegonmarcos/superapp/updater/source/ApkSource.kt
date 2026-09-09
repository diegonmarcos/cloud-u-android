package com.diegonmarcos.superapp.updater.source

import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import android.content.Context
import java.io.File

/**
 * Where an APK can come from.
 *
 * There were two download paths written as two private functions with an early
 * `return` between them, and because nothing forced them to be the same shape
 * their guarantees drifted apart: the GHCR one verified a digest, the release
 * one verified a length — and guarded that check with `if (total > 0 …)`, so a
 * missing Content-Length turned it off entirely. One contract makes the
 * asymmetry impossible to write: every source returns a [VerifiedApk] or
 * nothing, and "try the next one" is a list rather than a control-flow
 * accident.
 */
internal interface ApkSource {
    /** For logs and the Diagnose report — which channel actually served it. */
    val name: String

    /**
     * Fetch [app]'s APK, verified.
     *
     * Returns null when this source DOES NOT APPLY to this app at all — no
     * release URL configured, say. Throws, with a message naming what went
     * wrong, when it applies and fails.
     *
     * Either way [Fleet.download] moves on to the next source: a release URL
     * that 404s is a reason to fall through to GHCR, not a reason to fail an
     * install the other channel could still complete. The distinction is not
     * about control flow, it is about EVIDENCE. Returning null for a failure
     * threw the reason away, and the pipeline's final message could then only
     * report that everything had failed for no stated reason — which is how a
     * transfer that stalled at 182 MB of 265 MB was reported to the user as
     * nothing at all.
     */
    fun fetch(ctx: Context, app: Fleet.App): VerifiedApk?
}

/**
 * THE RELEASE ASSET FIRST, BECAUSE IT IS THE REPO.
 *
 * A GHCR package is owned by the ACCOUNT, not the repository. GitHub creates
 * every new user-owned package private regardless of the repo it was pushed
 * from, image.source only LINKS it, and there is no API to change it —
 * measured, not assumed: a package created by GITHUB_TOKEN inside this public
 * repo's own workflow came out private, and PATCH /user/packages/... 404s even
 * with write:packages.
 *
 * So GHCR can never follow repo visibility, and a private package on a public
 * repo shows up as 401 — which reads to a user as "the app is gone". A release
 * asset has no visibility of its own: it IS the repo, so a public repo's asset
 * is public, for every app, with nothing to click and nothing to remember.
 */
internal object ReleaseSource : ApkSource {
    override val name = "release"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        if (app.releaseUrl.isBlank()) return null
        // abiReleaseUrl, not releaseUrl: the release asset name is per-ABI
        // (see Fleet.App.assets), and this source is tried FIRST, so a flat
        // arm64 name here is what put an arm64 APK on an x86_64 device.
        val url = app.abiReleaseUrl
        val target = File(ctx.cacheDir, "fleet-${app.id}-release.apk")
        // The declared size, so Download can tell a resumable prefix of THIS
        // artifact from a leftover part of a previous release under the same
        // per-app filename, and so progress has a denominator from the first
        // byte instead of after the first response.
        val declared = Fleet.releaseSize(app)
        return try {
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, declared))
            Download.toFile(
                url = url,
                target = target,
                expectedBytes = declared,
                shouldCancel = { UpdateProgress.cancelRequested },
            ) { written, total ->
                val t = if (total > 0) total else declared
                val pct = if (t > 0) ((written * 100) / t).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, t))
            }
            // A truncated download is the failure this catches: an APK that is
            // short is not an APK, and the installer's error for one is far
            // less useful than saying so here.
            //
            // The size check USED to be guarded by `total > 0`, which made it
            // no check at all whenever the CDN omitted Content-Length: total
            // came back -1, the comparison was skipped, and an unverified file
            // went to the installer. That is exactly how a 383 kB
            // fleet-mail-release.apk — 1.3% of a 29 MB APK — reached
            // PackageInstaller and came back
            // "INSTALL_PARSE_FAILED_NOT_APK: Failed to load asset path".
            // One construction, three guarantees: a declared length must
            // exist, must match, and the bytes must actually be a zip. Failing
            // here falls through to GHCR, which does carry a digest — an
            // unverifiable download must never be RETURNED, because the caller
            // cannot tell the difference once it is just a File.
            VerifiedApk.bySize(target, declared) ?: run {
                // Known-bad bytes: drop the partial too, or every later attempt
                // resumes on top of them forever.
                Download.discard(target)
                error("release asset failed verification " +
                      "(${target.length()} B against a declared $declared) — deferring to GHCR")
            }
        } catch (c: java.util.concurrent.CancellationException) {
            // A cancel is the user's decision, not a reason to go and try the
            // other source with the same 265 MB.
            throw c
        } catch (t: Throwable) {
            // THIS CATCH USED TO BE `runCatching { ... }.getOrNull()`.
            //
            // That swallowed every Throwable — socket timeout, connection
            // reset, OutOfMemoryError, the explicit verification error — with
            // no log line and, far worse, no UpdateProgress transition. The
            // last state the user could see stayed `Downloading(43%)` while
            // control quietly fell through to GHCR, which republished
            // `Downloading(0, …)` and began the same 265 MB again from byte
            // zero. A progress bar that resets and a progress bar that is
            // frozen are the same picture to someone watching: "it sticks on
            // downloading and never progresses".
            //
            // At 25 MB this path essentially never ran, because the transfer
            // finished inside one TCP session. Nothing about it was safe; it
            // was untested.
            //
            // Falling through to GHCR is still right — GHCR carries a digest
            // and may well succeed where the release CDN did not — but it must
            // be a decision that leaves evidence, and the partial file stays on
            // disk so a retry resumes rather than restarts.
            val kept = File(target.parentFile, target.name + ".part").length()
            throw java.io.IOException(
                "${t.message ?: t.javaClass.simpleName} (kept $kept B on disk for resume)", t)
        }
    }
}

/**
 * The OCI blob, kept as the fallback because it carries a digest and a
 * manifest — a stronger integrity story than a declared length. Access first,
 * integrity second: an app nobody can download is not made safer by its
 * digest.
 */
internal object GhcrSource : ApkSource {
    override val name = "ghcr"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        val client = GhcrClient(app.registry, app.namespace, app.image)
        val token = client.token()
        val layer = Fleet.remoteLayerFor(app, client, token)
        val target = File(ctx.cacheDir, "fleet-${app.id}-${layer.digest.substringAfter(':').take(12)}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, layer.size))
        // Raw fleet threads aren't WorkManager — the Cancel button reaches them
        // only through UpdateProgress.cancelRequested.
        client.blob(layer.digest, token, target, layer.size, { UpdateProgress.cancelRequested }) { bytes, total ->
            val t = if (total > 0) total else layer.size
            val pct = if (t > 0) ((bytes * 100) / t).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, t))
        }
        val verified = VerifiedApk.byDigest(target, layer.digest)
        if (verified == null) {
            // Both the file AND the partial: bytes that failed a digest are
            // known-bad, and a resume on top of them can only ever fail again.
            Download.discard(target)
            // Throw rather than publish Failed and return null. Fleet.download
            // owns the terminal state now, and it needs this reason to put in
            // it; publishing here as well raced its own caller and reported a
            // digest mismatch as the outcome of a pass that had another source
            // still to try.
            error("digest mismatch against ${layer.digest}")
        }
        // Keep the verified APK, drop this app's superseded ones. Keeping it
        // means a retry after a failed install reuses the download instead of
        // pulling the blob again.
        client.pruneCache("fleet-${app.id}-", target)
        return verified
    }
}
