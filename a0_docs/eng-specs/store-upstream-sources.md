# Store upstream sources — the S21 profile's 212 upstream apps

Every package in `a0_tasks/upstream-apps-s21.json` has a row under `resolver.apps` of
`ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-install-sources.json`: a ladder, or an
explicit `unresolved` reason. Guarded by `ac_cloud-store/test/test-store-upstream-sources.sh`.

## How each rung was decided (measured 2026-10-09 from the owner's phone)

- **vendor** — the publisher's own direct APK or GitHub-releases feed. Only URLs that answered with an APK
  (HTTP 206, `PK` magic) on 2026-10-09 are declared; each carries that evidence in `verified`. Candidates
  whose release asset name is not derivable from the feed (Proton VPN/Drive embed a build number, FairEmail
  a letter suffix, Shizuku a commit hash, Termux ships debug-signed builds) were NOT declared as vendor.
- **fdroid** — `https://f-droid.org/api/v1/packages/<pkg>` answered 200.
- **play-anon** — Google Play's own `fdfe/details` answered an app for the package with an anonymous
  session from the declared dispenser (gplayapi S20+ profile). Delivery is then fetched at install time.
- **samsung** — the phone recorded Galaxy Store (or Galaxy Wearable) as the installer.
- **play** — kept as the last, hand-off rung on every Play app: the Play page is still the official path
  when the anonymous session is unavailable.
- **unresolved** — none of the above: no public source.

## Integrity (does the app need Google Play as its installer?)

`integrity: play` rows keep their play-anon rung (the bytes install) but carry the badge 'Play Integrity: may
refuse to run' and sit under 'Vendor requires Play' on Store > Phone. They were set by the owner's category
rule (banks/payments, government ID, tap-to-pay, DRM video) and are NOT yet measured on the phone. `none`
needs evidence (the publisher ships outside Play, or the S21 already runs it from Galaxy Store). Everything
else is `unknown`, never rounded to none; a user's 'Did not run' marks one play locally.

## Counts over the 212

| rung | apps whose FIRST rung it is | apps carrying it |
|---|---|---|
| vendor | 12 | 12 |
| fdroid | 7 | 13 |
| play-anon | 163 | 178 |
| samsung | 11 | 16 |
| play | 0 | 178 |
| unresolved | 19 | 19 |

| integrity | apps |
|---|---|
| none | 30 |
| play | 18 |
| unknown | 164 |

## Per app

| package | recorded installer | ladder | why |
|---|---|---|---|
| `ai.perplexity.app.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ai.perplexity.comet` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ai.x.grok` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `app.affine.pro` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `app.sterna` | org.fdroid.fdroid | fdroid | fdroid (in the official index); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `at.zuggabecka.radiofm4` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `be.greifmatthias.wakaship` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `br.gov.economia.receita.rfb` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `br.gov.meugovbr` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `cat.atm.tmobilitat` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ch.icoaching.typewise` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ch.protonvpn.android` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `chat.rocket.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `co.mangotechnologies.clickup` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.Slack` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.adobe.reader` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.airbnb.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.alphainventor.filemanager` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.amazon.avod.thirdpartyclient` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: DRM video — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.android.chrome` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.android.settings` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.android.vending` | com.android.vending | — | unresolved: Google Play itself: ships with Google Mobile Services, not published as an app; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.anthropic.claude` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.app.applistbackup` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.aspiro.tidal` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.aurora.store` | com.uptodown | fdroid | fdroid (in the official index); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `com.badoo.mobile` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.beemdevelopment.aegis` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.beeper.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.bereal.ft` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.binarysmith.webclipwidget.ad` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.booking` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.brave.browser` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.btg.pactual.banking` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.btg.pactual.digital.mobile` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.bumble.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.checkitt` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.cloudflare.onedotonedotonedotone` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.collabora.libreoffice` | — | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.comuto` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.devolutions.remotedesktopmanager` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.dgt.midgt` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.dhl.exp.dhlmobile` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.diegonmarcos.cloudcalendar` | — | — | unresolved: a Cloud fleet package that the fleet manifest does not list; not public anywhere; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.diegonmarcos.comms.contacts` | com.diegonmarcos.superapp | — | unresolved: a Cloud fleet package that the fleet manifest does not list; not public anywhere; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.digibites.accubattery` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.diotek.sec.lookup.dictionary` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.downdogapp` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.ebay.kleinanzeigen` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.epicgames.portal` | com.epicgames.portal | — | unresolved: installed from com.epicgames.portal; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.esim.numero` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.facebook.orca` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.fatsecret.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.flyersoft.moonreader` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.foxdebug.acode` | com.google.android.packageinstaller | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.garmin.android.apps.connectmobile` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.garmin.android.apps.explore` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.garmin.android.apps.messenger` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.garmin.connectiq` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.ghisler.android.TotalCommander` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.github.android` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.authenticator2` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.bard` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.docs` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.docs.editors.docs` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.docs.editors.sheets` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.fitness` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.labs.language.tailwind` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.magazines` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.maps` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.messaging` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.photos` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.tachyon` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.tasks` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.translate` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.apps.walletnfcrel` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: tap-to-pay — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.google.android.calculator` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.calendar` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.contacts` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.gm` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.googlequicksearchbox` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.inputmethod.latin` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.android.youtube` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.google.audio.hearing.visualization.accessibility.scribe` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.hostelworld.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.imaginecurve.curve.prd` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.instagram.android` | com.facebook.system | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.instagram.barcelona` | com.facebook.system | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.linkedin.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.lonelycatgames.Xplore` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mattermost.rn` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.medantapatient.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.memrise.android.memrisecompanion` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mi.earphone` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.copilot` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.lists.public` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.office.onenote` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.office.outlook` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.skydrive` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.microsoft.todos` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mo2o.alsa` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mobidia.android.mdm` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mobillium.airalo` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.mobisystems.fileman` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.netflix.mediaclient` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store); integrity play: DRM video — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.nomadmania.presentation` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.paypal.android.p2pmobile` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.phlox.simpleserver` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.pinterest` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.playdead.limbo.egs.full` | com.epicgames.portal | — | unresolved: installed from com.epicgames.portal; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.rarlab.rar` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.revolut.revolut` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.ridedott.rider` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.rome2rio.www.rome2rio` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.rsupport.rs.activity.rsupport.aas2` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.ryanair.cheapflights` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.app.contacts` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.app.notes` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.app.reminder` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.app.routines` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.app.spage` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.app.watchmanager` | com.samsung.android.app.watchmanager | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.bixby.agent` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.calendar` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.dialer` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.email.provider` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.game.gamehome` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.lool` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.samsung.android.mdx` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.android.messaging` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.samsung.knox.securefolder` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.sec.android.app.camera` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.sec.android.app.clockpackage` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.sec.android.app.myfiles` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.sec.android.app.popupcalculator` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.sec.android.app.samsungapps` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.sec.android.app.voicenote` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.sec.android.easyMover` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.sec.android.gallery3d` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store); integrity none: installed from Galaxy Store on the S21, i.e. it already runs without a Play install |
| `com.sensorberg.oneaccess` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.shazam.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.skiplagged` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.smlnskgmail.jaman.hashchecker` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.spanishcoders.pepephone` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.spotify.music` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.strava` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.termux` | com.google.android.packageinstaller | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `com.termux.nix` | com.google.android.packageinstaller | fdroid | fdroid (in the official index); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `com.termux.nix.boot` | com.diegonmarcos.superapp | — | unresolved: installed from com.diegonmarcos.superapp; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.thetrainline` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.tinder` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.trafi.whitelabel.bvg` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.transferwise.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.trello` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.trianguloy.openInWhatsapp` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.ubercab` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.ubnt.usurvey` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.uptodown` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.urbandroid.sleep` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.urbansportsclub` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.viber.voip` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.viscouspot.gitsync` | org.fdroid.fdroid | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.wbd.stream` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: DRM video — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `com.werewolfapps.online` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.wggesucht.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.whatsapp` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.whatsapp.w4b` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.wikiloc.wikilocandroid` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.wireguard.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.wizzair.WizzAirApp` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `com.x8bit.bitwarden` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `com.xe.currency` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.congstar.meincongstar` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.deutschepost.postident` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `de.dhl.paket` | com.aurora.store | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.eos.uptrade.android.fahrinfo.berlin` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.flixbus.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.is24.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `de.number26.android` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `dk.shelter.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ee.mtakso.client` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `es.aeat.pin24h` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `es.bancosantander.apps` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: bank / payments — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `es.correos.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `es.fnmtrcm.ceres.certificadoDigitalFNMT` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `es.gob.interior.policia.midni` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity play: government ID / e-signature — the owner's category rule (these enforce Play Integrity / a Play-installer check); not yet measured on this phone |
| `eu.faircode.email` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `eu.faircode.netguard` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `im.vector.app` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `io.japp.blackscreen` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `io.vicoach` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `md.obsidian` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `me.proton.android.drive` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `mobi.eup.easygerman` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `moe.shizuku.privileged.api` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `net.esim.twa` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `net.skyscanner.android.main` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `net.techet.netanalyzerlite.an` | com.google.android.packageinstaller | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `org.chromium.webapk.a64d263d6f7d935c4_v2` | com.android.vending | — | unresolved: a Chrome WebAPK minted on-device by Chrome for one site; no store publishes it; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `org.chromium.webapk.afdfe84a0acbbed2f_v2` | com.android.vending | — | unresolved: a Chrome WebAPK minted on-device by Chrome for one site; no store publishes it; integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `org.faudroids.werewolf` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `org.fdroid.fdroid` | com.google.android.packageinstaller | vendor → fdroid | vendor (existing, re-verified); fdroid (in the official index); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `org.kde.kdeconnect_tp` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (fdroid), so it cannot require the Play installer |
| `org.telegram.messenger` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `org.thoughtcrime.securesms` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered); integrity none: the publisher itself distributes this app outside Play (vendor), so it cannot require the Play installer |
| `pub.hanks.appfolderwidget` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `ride.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `taxi.android.client` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `travel.goki.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `tv.arte.plus7` | com.uptodown | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `vivino.web.app` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
| `xyz.chatboxapp.chatbox` | com.android.vending | play-anon → play | play-anon (Play details answered); integrity unknown: no evidence either way; the row offers 'did not run' to mark it play locally |
