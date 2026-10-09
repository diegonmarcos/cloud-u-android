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

## Counts over the 212

| rung | apps whose FIRST rung it is | apps carrying it |
|---|---|---|
| vendor | 12 | 12 |
| fdroid | 7 | 13 |
| play-anon | 163 | 178 |
| samsung | 11 | 16 |
| play | 0 | 178 |
| unresolved | 19 | 19 |

## Per app

| package | recorded installer | ladder | why |
|---|---|---|---|
| `ai.perplexity.app.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ai.perplexity.comet` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ai.x.grok` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `app.affine.pro` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `app.sterna` | org.fdroid.fdroid | fdroid | fdroid (in the official index) |
| `at.zuggabecka.radiofm4` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `be.greifmatthias.wakaship` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `br.gov.economia.receita.rfb` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `br.gov.meugovbr` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `cat.atm.tmobilitat` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ch.icoaching.typewise` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ch.protonvpn.android` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered) |
| `chat.rocket.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `co.mangotechnologies.clickup` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.Slack` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.adobe.reader` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.airbnb.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.alphainventor.filemanager` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.amazon.avod.thirdpartyclient` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.android.chrome` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.android.settings` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK |
| `com.android.vending` | com.android.vending | — | unresolved: Google Play itself: ships with Google Mobile Services, not published as an app |
| `com.anthropic.claude` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.app.applistbackup` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.aspiro.tidal` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.aurora.store` | com.uptodown | fdroid | fdroid (in the official index) |
| `com.badoo.mobile` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.beemdevelopment.aegis` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered) |
| `com.beeper.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.bereal.ft` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.binarysmith.webclipwidget.ad` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.booking` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.brave.browser` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `com.btg.pactual.banking` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.btg.pactual.digital.mobile` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.bumble.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.checkitt` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.cloudflare.onedotonedotonedotone` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.collabora.libreoffice` | — | play-anon → play | play-anon (Play details answered) |
| `com.comuto` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.devolutions.remotedesktopmanager` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.dgt.midgt` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.dhl.exp.dhlmobile` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.diegonmarcos.cloudcalendar` | — | — | unresolved: a Cloud fleet package that the fleet manifest does not list; not public anywhere |
| `com.diegonmarcos.comms.contacts` | com.diegonmarcos.superapp | — | unresolved: a Cloud fleet package that the fleet manifest does not list; not public anywhere |
| `com.digibites.accubattery` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.diotek.sec.lookup.dictionary` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK |
| `com.downdogapp` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.ebay.kleinanzeigen` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.epicgames.portal` | com.epicgames.portal | — | unresolved: installed from com.epicgames.portal; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.esim.numero` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.facebook.orca` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.fatsecret.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.flyersoft.moonreader` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.foxdebug.acode` | com.google.android.packageinstaller | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered) |
| `com.garmin.android.apps.connectmobile` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.garmin.android.apps.explore` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.garmin.android.apps.messenger` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.garmin.connectiq` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.ghisler.android.TotalCommander` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.github.android` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.authenticator2` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.bard` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.docs` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.docs.editors.docs` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.docs.editors.sheets` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.fitness` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.labs.language.tailwind` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.magazines` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.maps` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.messaging` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.photos` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.tachyon` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.tasks` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.translate` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.apps.walletnfcrel` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.calculator` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.calendar` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.contacts` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.gm` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.googlequicksearchbox` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.inputmethod.latin` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.android.youtube` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.google.audio.hearing.visualization.accessibility.scribe` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.hostelworld.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.imaginecurve.curve.prd` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.instagram.android` | com.facebook.system | play-anon → play | play-anon (Play details answered) |
| `com.instagram.barcelona` | com.facebook.system | play-anon → play | play-anon (Play details answered) |
| `com.linkedin.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.lonelycatgames.Xplore` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.mattermost.rn` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.medantapatient.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.memrise.android.memrisecompanion` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.mi.earphone` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.microsoft.copilot` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.microsoft.lists.public` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.microsoft.office.onenote` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.microsoft.office.outlook` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.microsoft.skydrive` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.microsoft.todos` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.mo2o.alsa` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.mobidia.android.mdm` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.mobillium.airalo` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.mobisystems.fileman` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.netflix.mediaclient` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store) |
| `com.nomadmania.presentation` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.paypal.android.p2pmobile` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.phlox.simpleserver` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.pinterest` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.playdead.limbo.egs.full` | com.epicgames.portal | — | unresolved: installed from com.epicgames.portal; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.rarlab.rar` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.revolut.revolut` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.ridedott.rider` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.rome2rio.www.rome2rio` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.rsupport.rs.activity.rsupport.aas2` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store) |
| `com.ryanair.cheapflights` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.samsung.android.app.contacts` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK |
| `com.samsung.android.app.notes` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.samsung.android.app.reminder` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.app.routines` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.app.spage` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.app.watchmanager` | com.samsung.android.app.watchmanager | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store) |
| `com.samsung.android.bixby.agent` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.calendar` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store) |
| `com.samsung.android.dialer` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK |
| `com.samsung.android.email.provider` | com.sec.android.app.samsungapps | play-anon → samsung → play | play-anon (Play details answered); samsung (installed from Galaxy Store) |
| `com.samsung.android.game.gamehome` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.lool` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.samsung.android.mdx` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.android.messaging` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.samsung.knox.securefolder` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.sec.android.app.camera` | — | — | unresolved: preinstalled / no installer recorded; not on Play, not in F-Droid, no publisher APK |
| `com.sec.android.app.clockpackage` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.sec.android.app.myfiles` | com.android.vending | — | unresolved: installed from com.android.vending; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.sec.android.app.popupcalculator` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.sec.android.app.samsungapps` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.sec.android.app.voicenote` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.sec.android.easyMover` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.sec.android.gallery3d` | com.sec.android.app.samsungapps | samsung | samsung (installed from Galaxy Store) |
| `com.sensorberg.oneaccess` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.shazam.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.skiplagged` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.smlnskgmail.jaman.hashchecker` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.spanishcoders.pepephone` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.spotify.music` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.strava` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.termux` | com.google.android.packageinstaller | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered) |
| `com.termux.nix` | com.google.android.packageinstaller | fdroid | fdroid (in the official index) |
| `com.termux.nix.boot` | com.diegonmarcos.superapp | — | unresolved: installed from com.diegonmarcos.superapp; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.thetrainline` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.tinder` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.trafi.whitelabel.bvg` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.transferwise.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.trello` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.trianguloy.openInWhatsapp` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.ubercab` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.ubnt.usurvey` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.uptodown` | com.uptodown | — | unresolved: installed from com.uptodown; not on Play (anonymous details 404), not in F-Droid, no verified publisher APK |
| `com.urbandroid.sleep` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.urbansportsclub` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.viber.voip` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.viscouspot.gitsync` | org.fdroid.fdroid | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered) |
| `com.wbd.stream` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.werewolfapps.online` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.wggesucht.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.whatsapp` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `com.whatsapp.w4b` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.wikiloc.wikilocandroid` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `com.wireguard.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.wizzair.WizzAirApp` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `com.x8bit.bitwarden` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `com.xe.currency` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.congstar.meincongstar` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.deutschepost.postident` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.dhl.paket` | com.aurora.store | play-anon → play | play-anon (Play details answered) |
| `de.eos.uptrade.android.fahrinfo.berlin` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.flixbus.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.is24.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `de.number26.android` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `dk.shelter.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ee.mtakso.client` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `es.aeat.pin24h` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `es.bancosantander.apps` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `es.correos.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `es.fnmtrcm.ceres.certificadoDigitalFNMT` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `es.gob.interior.policia.midni` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `eu.faircode.email` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered) |
| `eu.faircode.netguard` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered) |
| `im.vector.app` | com.android.vending | vendor → fdroid → play-anon → play | vendor (publisher's GitHub releases); fdroid (in the official index); play-anon (Play details answered) |
| `io.japp.blackscreen` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `io.vicoach` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `md.obsidian` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `me.proton.android.drive` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `mobi.eup.easygerman` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `moe.shizuku.privileged.api` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `net.esim.twa` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `net.skyscanner.android.main` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `net.techet.netanalyzerlite.an` | com.google.android.packageinstaller | play-anon → play | play-anon (Play details answered) |
| `org.chromium.webapk.a64d263d6f7d935c4_v2` | com.android.vending | — | unresolved: a Chrome WebAPK minted on-device by Chrome for one site; no store publishes it |
| `org.chromium.webapk.afdfe84a0acbbed2f_v2` | com.android.vending | — | unresolved: a Chrome WebAPK minted on-device by Chrome for one site; no store publishes it |
| `org.faudroids.werewolf` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `org.fdroid.fdroid` | com.google.android.packageinstaller | vendor → fdroid | vendor (existing, re-verified); fdroid (in the official index) |
| `org.kde.kdeconnect_tp` | com.android.vending | fdroid → play-anon → play | fdroid (in the official index); play-anon (Play details answered) |
| `org.telegram.messenger` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `org.thoughtcrime.securesms` | com.android.vending | vendor → play-anon → play | vendor (existing, re-verified); play-anon (Play details answered) |
| `pub.hanks.appfolderwidget` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `ride.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `taxi.android.client` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `travel.goki.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `tv.arte.plus7` | com.uptodown | play-anon → play | play-anon (Play details answered) |
| `vivino.web.app` | com.android.vending | play-anon → play | play-anon (Play details answered) |
| `xyz.chatboxapp.chatbox` | com.android.vending | play-anon → play | play-anon (Play details answered) |
