# Contributing to Sterna

Thanks for your interest. Sterna is a released app, published on F-Droid. It is
written and maintained by one developer and still changes often, so expect the
code around your patch to move.

## Requirements

- JDK 17
- Android SDK (compileSdk 36, build-tools 36.0.0)
- An Android device or emulator running Android 8.0 (API 26) or newer

The Gradle wrapper (`./gradlew`) pins the Gradle version, so you don't need
Gradle installed.

## Build

```sh
# Run the unit tests, every module that carries any
./gradlew :core:jmap:test :core:imap:test :core:data:testDebugUnitTest :app:testDebugUnitTest

# Build a debug APK
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install on a connected device/emulator
./gradlew :app:installDebug
```

## Tests, and what they cannot see

Every test in this repository is a **JVM unit test**. There is **no instrumented test and no
Robolectric**, in any module. Nothing here composes, lays out or renders a composable, and nothing
instantiates an `AndroidViewModel`, a DataStore or a Room database. Read a green suite as exactly
that much.

Two habits follow from it, and both are load-bearing:

- **A decision that matters is extracted as a pure function and RUN by a test**, not described by
  one. A test that recomputes the rule from the same inputs in order to choose what to assert is a
  copy of the rule: invert the shipped condition and it stays green.
- **Where the decision cannot be reached (a composable, a ViewModel), a SOURCE LINT pins the call
  site**: it reads the file as text and checks that the screen calls the tested function with the
  right arguments. Such a lint proves that a call is *written*, never that it *works*. Every one of
  them says so in its own header, and each says what it does not cover.

A source lint should pin **whole arguments and whole calls** rather than substrings. A rule looking
for `f(a,` accepts anything after the comma, and that is not theoretical: pinning only the first
argument of one call let its last two be swapped, which put about 72 dp of blank space at the end of
every message with the whole suite green. Two rules in the tree still match by substring, knowingly:
`ReplyBarWiringTest` looks for the word "menu" anywhere in a translated sentence (a sentence with
the two halves reversed passes), and `ComposeDeleteWiringTest` looks for the delete call anywhere in
the dialog block rather than inside the confirming arm. Do not copy the pattern; do not add a third.

### What a source lint cannot see, even pinned whole

Four holes, all found by audit on rules that looked airtight. A lint is worth writing anyway, but
write it knowing these, and say in its header which ones apply to it.

- **An argument name says nothing about what the name is bound to.** `f(defaultPx, clearancePx)`
  pinned whole is satisfied while the two `val`s one line above are swapped. That is the same 72 dp
  bug as the one cited above, moved by one line. **Where a value can be lifted INTO the function, do
  that instead**, the constant, the lookup, the default. Then the mutation dies in a test that runs
  the decision, and the lint stops being the thing holding it.
- **A rule about the arguments says nothing about the expression that wraps the call.** `!f(...)`,
  `f(...) || x`, `f(...).let { … }` all contain the pinned text.
- **Textual order is not execution order, and a pinned line says nothing about its position in a
  control structure.** Moving an assignment into a `catch` leaves it lower in the file, which is
  what the rule reads; adding a branch ABOVE the pinned branch of a `when` leaves the pinned line
  untouched and never reached.
- **Nothing here can hold a value at the point a composable collects it.** Replacing
  `val x by viewModel.x.collectAsStateWithLifecycle()` with `val x = false` annuls the fix behind it
  and passes every rule that reads the call sites below. It would take a rendering test, and there
  is none. When that is where a fix lives, write the blind spot down rather than adding a rule that
  does not close it.

### The shape of a rule that has held

The two rules of this repository that generalise both **prove a relation between two artefacts**,
not the presence of a text: the screen's list predicate compared against the selection's, and two
SQL statements run over the same rows and required to answer the same ids. Prefer that shape. A rule
that can only be satisfied by one exact spelling teaches the next contributor to "fix" it by pasting
in the new spelling, which is how a test gets disarmed.

`DaoQuerySource` runs a DAO's `@Query` as written in the shipped source against in-memory SQLite,
which is the strongest instrument here. Its limit belongs in every test that uses it: the schema is
recopied by hand in the test, so it proves neither that the table Room creates matches it, nor
Room's parameter binding, nor its mapping of result columns onto a data class by name.

**No fix is complete without a test that was seen to FAIL before it and pass after.** If the fix is
a new function, stub it to reproduce the shipped behaviour, watch the test go red, then write it.
And **break the finished code once on purpose**: mutate the decision, watch the test that claims to
cover it fail, put it back. A mutation that stays green is a rule that does not cover what its name
says.

## Behind a proxy

Put proxy settings in `~/.gradle/gradle.properties` (not in the repo):

```properties
systemProp.https.proxyHost=HOST
systemProp.https.proxyPort=PORT
systemProp.https.proxyUser=USER
systemProp.https.proxyPassword=PASS
systemProp.jdk.http.auth.tunneling.disabledSchemes=
```

## Project layout

See [ARCHITECTURE.md](ARCHITECTURE.md). In short:

- `:app`, Android app: Compose UI, ViewModels, push services and workers,
  notifications, OpenPGP provider binding, app lock.
- `:core:jmap`, Kotlin JVM library: JMAP client, session, typed RFC 8620/8621
  models, EventSource push, OAuth device flow and authorization code + PKCE
  (unit-tested without Android).
- `:core:imap`, Kotlin JVM library: IMAP/SMTP clients, IDLE, MIME parser and
  builder, PGP/MIME, XOAUTH2 (unit-tested without Android).
- `:core:data`, Android library: `MailRepository`, Room cache, account store,
  settings, PGP seam, storage caps.
- `:libs:openpgp-api`, Android library: vendored openpgp-api client (Java), for
  the bound OpenPGP provider service. It carries no tests of its own.

## Conventions

- Kotlin official code style (`kotlin.code.style=official`).
- **A comment says only what the code cannot**: an invariant, a trap, or a decision and what I ruled
  out. Write only what a machine can contradict, or what no reading of the code will give you;
  naming a symbol in backticks is encouraged, precisely because a machine can contradict it. What
  not to write, worst first:
  - a `File.kt:NNN` reference. Line numbers drift silently and most of the ones this tree used to
    carry had come to name the wrong line, while not one symbol named in backticks has gone missing.
  - the story of what the code used to do. Git carries that, and git does not drift.
  - warning glyphs and shouted capitals. They belong to the procedure files, not to a source.
  - a paraphrase of the line below it.
  - the reason an extraction pattern exists, recopied into every file that uses it. It is worth
    writing once, in one place.

  Two kinds of comment are not what this is aimed at, and I want them kept. A source lint's
  self-documenting header stays required, as above: a header that says what the lint does not cover
  is a trap and a decision at once. And the reason a guard exists goes in the source, because the
  source is the only place that outlives the branch that added it.
- Keep `:core:jmap` free of Android dependencies so it stays testable on the JVM.
- **FOSS dependencies only.** Every library must be free/open-source
  (OSI/FSF-approved, GPLv3-compatible). No proprietary or closed-source libraries,
  no Google Play Services / Firebase / GMS, no analytics or tracking SDKs, and
  nothing that requires a non-free service to function. This keeps Sterna
  F-Droid-eligible (build-from-source, no anti-features) and Google-free.
- No telemetry, ever. Don't add tracking or analytics.

## License

By contributing, you agree your contributions are licensed under the GPLv3.
