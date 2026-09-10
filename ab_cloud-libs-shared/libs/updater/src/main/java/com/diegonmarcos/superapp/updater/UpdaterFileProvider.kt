package com.diegonmarcos.superapp.updater

import androidx.core.content.FileProvider

/**
 * The updater's FileProvider, as a NAMED SUBCLASS rather than androidx's class itself.
 *
 * A `<provider>`'s `android:name` IS its component name, and a package may declare each component
 * name exactly once. This module merges into ten apps. The moment one of them declares its own
 * `androidx.core.content.FileProvider` the merger sees two providers wearing one name and fails the
 * build — which is precisely what happened to cloud-mail when 34a089d72 gave it this module, and
 * why that app then could not compile at all.
 *
 * The merger's own suggestion, `tools:replace`, is not a fix here: it resolves the clash by keeping
 * ONE of two providers that both have to exist. The app's serves its attachments; this one hands a
 * verified APK to the system installer, which [BootstrapInstall] documents as the only install
 * channel that survives losing every privileged one. Their AUTHORITIES are already distinct and
 * deliberately so, for the reason the manifest states — the class name needed to be distinct for
 * exactly the same reason, and was not.
 *
 * Nothing resolves this provider by class. [BootstrapInstall.authority] builds the authority string
 * and `FileProvider.getUriForFile` looks that up through the PackageManager, so the rename is
 * invisible to every caller and to every already-installed app.
 */
class UpdaterFileProvider : FileProvider()
