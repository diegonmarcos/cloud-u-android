# Encrypted email with OpenPGP

Sterna can read and send **OpenPGP** mail: messages that are signed (so the
reader can prove they really came from you) and/or encrypted (so only the
intended recipient can read them). This works on both JMAP and IMAP/SMTP
accounts.

This page explains, in plain terms, how it works and how to set it up.

## How it works, in one minute

OpenPGP gives every person **two matching keys**:

- a **public key**, which you hand out freely. Think of it as an open padlock:
  anyone can snap it shut, but only you hold the key that opens it.
- a **private key**, which never leaves your device and is protected by a
  passphrase. It is the only thing that can open padlocks made with your public
  key.

From that, two things follow:

- **Encrypting.** To send Bob a message only he can read, you lock it with
  *Bob's* public padlock. From that moment only Bob's private key can open it,
  not even you, and certainly not the mail servers the message passes through.
- **Signing.** To prove a message is really from you, you seal it with *your*
  private key. Anyone can then check the seal against your public key. If a
  single character was changed in transit, the seal no longer matches and the
  reader is warned.

So to **send someone encrypted mail you need their public key**, and to let
people **send encrypted mail to you (or check your signature) they need yours**.
Public keys are meant to be shared: by email, on a website, or on a key server.

Two limits worth knowing up front:

- **The subject line is not encrypted.** OpenPGP protects the body and
  attachments, not the envelope. Keep sensitive details out of the subject.
- **A decrypted message body is never stored.** It lives only in memory while
  you read it: not written to disk, not cached, not search-indexed. Close and
  reopen the message and it is decrypted again. **Attachments are the
  exception**: opening one writes the decrypted file into the app's own storage,
  where it stays until the cache is cleared (Settings → Storage), and saving one
  writes it, decrypted, into the folder you pick — outside the app, where
  clearing the cache does not reach it. Printing or saving a decrypted message as
  PDF hands its plain text to the system print service. All three happen only on
  your request.

## What you need: two apps

Sterna does not handle your secret keys itself. Instead it talks to a dedicated
OpenPGP app over Android's OpenPGP interface (the same one K-9 Mail /
Thunderbird for Android uses). Your private key and its passphrase stay inside
that app and never enter Sterna's process. Sterna only ever asks it to sign,
encrypt, or decrypt on its behalf, and the OpenPGP app prompts you when it needs
your passphrase.

The OpenPGP app most people use is
**[OpenKeychain](https://f-droid.org/packages/org.sufficientlysecure.keychain/)**,
an open-source key manager, and it is the one this page follows as its example.
It is not the only one that can be used: Sterna looks for the OpenPGP apps
installed on the device, and does not require a particular one.

So the setup is two apps:

1. **Sterna**: your mail client (this app).
2. **An OpenPGP app**: your keyring.

Install OpenKeychain from
[F-Droid](https://f-droid.org/packages/org.sufficientlysecure.keychain/) (or
from inside Sterna: **Settings → your account → OpenPGP encryption → Get
OpenKeychain**).

## Step 1: Create your key in your OpenPGP app

The steps below are OpenKeychain's; another OpenPGP app names the same things
its own way. Open OpenKeychain and either create a new key or import one you
already have.

**To create one:**

1. Tap **Create my key**.
2. Enter your name and the email address of the account you will use it with.
   The address matters: Sterna matches keys to recipients by email.
3. Set a strong passphrase. This protects your private key; OpenKeychain will
   ask for it (and can remember it for a while) whenever a message needs your
   private key.
4. Finish. OpenKeychain now holds your key pair.

**If you already have a key** (for example a `.asc` / `.gpg` file, or one on a
key server), use OpenKeychain's **import** option instead and point it at the
file or key.

## Step 2: Connect the key to your account in Sterna

1. In Sterna, go to **Settings → your account → OpenPGP encryption**.
2. Turn on **Use OpenPGP**.
3. Tap **Your Key ID → Choose key**. Sterna asks the OpenPGP app to show your
   keys; pick the one for this account. (This does not copy the key into Sterna,
   it just records which key to use.)
4. Optionally turn on **Encrypt by default** so new messages start with
   encryption on whenever every recipient's public key is available.

That is the whole setup. You only do it once per account.

If more than one OpenPGP app is installed, a row named **OpenPGP app** on that
same screen says which one Sterna uses and lets you change it; switching clears
every account's recorded key, since a key from one keyring means nothing in
another, so pick your key again afterwards.

## Step 3: Send signed / encrypted mail

When composing, a **padlock button** in the toolbar cycles through three modes,
and a short banner explains each as you switch. A **long press** on that same
button opens a menu holding those three plus a fourth one, which the cycle never
reaches: a short tap must not walk anyone into sending unsigned mail. That
fourth mode is a stop, not a station: tap the button while it is set and you go
straight back to **Off**, not on to another encrypting mode.

| Icon | Mode | What it means |
|------|------|---------------|
| open padlock | **Off** | Not encrypted. Anyone handling the mail can read it. |
| pen / seal | **Sign** | Readable by everyone, but proves it comes from you. |
| closed padlock | **Encrypt** | Encrypted **and** signed. Only the recipient can read it. |
| key (long press only) | **Encrypt without signing** | Encrypted, not signed: only the recipient can read it, but it doesn't prove it comes from you. |

To **encrypt**, Sterna needs a public key for every recipient. A recipient
without a known key is flagged, and you will not be able to send encrypted until
their key is in your OpenPGP app. It can get there three ways: you ask them for
it, you fetch it from a key server, or it simply arrives with one of their
messages (see below). **Signing** has no such requirement: you can sign to
anyone.

Once an account has OpenPGP turned on and a key chosen, Sterna attaches that
key's public half to the messages that account sends, in a header. Nothing is
attached before you have chosen a key, and turning OpenPGP off stops it. Only
your public key travels, and only the identity of the account it belongs to:
other identities on the same key stay in your OpenPGP app. A message sent from
an alias identity, that is with a **From** address other than the account's own,
announces no key at all. And nothing is announced at all if Sterna could not
read your public key back when you chose it, because your OpenPGP app was
missing or locked at that moment: it tries again every time you open the
composer, and starts announcing once one of those attempts succeeds.

A few things behave differently while a message is set to either encrypting
mode: it cannot be saved as a plaintext draft, and scheduled send is
unavailable (both would put readable content on the server). Closing the
composer therefore offers only Discard or Cancel, and says why: there is
nowhere the message could be kept without handing your text to the server in
the clear. Your own **Sent** copy stays readable, because Sterna also encrypts
it to your own key.

## Step 4: Read encrypted / signed mail

When you open an OpenPGP message, Sterna asks the OpenPGP app to decrypt and
verify it (that app may prompt for your passphrase the first time). Above the
body you will see the result:

- a **padlock** if the message was encrypted, and
- a **signature badge** coloured by trust:
  - 🟢 **green**: valid signature from a confirmed key.
  - 🟡 **yellow**: valid, but from a key you have not confirmed, or the signer
    does not match the sender address.
  - 🔴 **red**: the signature does not verify: the content may have been
    altered, or the key is revoked / expired / insecure.
  - ⚪ **grey**: the signer's public key is not in your OpenPGP app, so the
    signature cannot be checked. Import their key to verify it, if it has not
    arrived on its own already.

Sterna also reads legacy **inline PGP** messages, not just modern PGP/MIME.

Several mail clients attach the sender's public key to every message they send,
the same way Sterna does. That key is handed to your OpenPGP app, and you can
encrypt back to that person without asking them for anything. When it reaches
you depends on the account. On a **JMAP** account the keys of the newest Inbox
messages arrive on their own, as Sterna fetches those messages ahead of you;
opening a message brings its key too, unless it is signed or encrypted, and then
only the first way works. On an **IMAP** account a key comes from any message
whose full text Sterna had to fetch: one you open, reply to, forward or decrypt,
and never from one it has not touched. Nothing arrives on its own from Spam,
Trash or Archive.

A key that is announced is not always taken: Sterna ignores it, and says
nothing, when the date the server put on the message cannot be read, and when
that date runs more than 24 hours ahead of your phone's clock. So a phone whose
own clock is more than a day slow stops taking keys from the mail that has just
arrived. Set the clock right, and the next message from that correspondent
brings the key.

A key that turns up this way is a convenience, not a proof of identity: it says
what the sender's client claimed, and nothing about who is really at the other
end. Confirming a key stays a deliberate act in your OpenPGP app, and the
signature badge keeps telling you whether you have done it.

## Troubleshooting

- **"No OpenPGP app installed"**. Install one: the button in Sterna's settings
  offers OpenKeychain, from
  [F-Droid](https://f-droid.org/packages/org.sufficientlysecure.keychain/).
- **Can't turn on encryption for a recipient.** Their public key isn't in your
  OpenPGP app yet. Their client may attach it to their mail, in which case it
  turns up on its own or when you open a message that is neither signed nor
  encrypted (see above). Failing that, import it (from a file they sent, or a
  key server) and try again.
- **Grey signature badge.** You don't have the sender's public key. Import it to
  turn the check green (or yellow until you confirm it).
- **No passphrase prompt / it stopped asking.** OpenKeychain caches your
  passphrase for a configurable time. Adjust or clear that in OpenKeychain's
  settings.
