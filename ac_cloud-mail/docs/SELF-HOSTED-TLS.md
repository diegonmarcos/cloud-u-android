# Self-hosted server: getting its certificate accepted

Sterna checks that the certificate really belongs to the server you asked for, and it
offers no way to accept one that does not match. That is deliberate: an "accept anyway"
button is how interception gets waved through.

So if you run your own mail server, two things have to be true for sign-in to work: the
certificate must name the host you connect to, and whoever signed it must be trusted by
the phone. There are two ways to get there. Taking a free certificate from a public
authority satisfies both at once and is by far the shorter path; signing your own works
too, at the price of a second certificate to create and one to install on the phone. Both
are below, easiest first.

## The shortest route: a free certificate from Let's Encrypt

If you own a domain name, this is both the easiest route and the one with the fewest
consequences: the certificate chains to an authority Android already trusts, so there is
nothing to install on the phone, and no permanent warning. It costs nothing.

You need a name, not just an address: decide the exact host name you will type into
Sterna, say `mail.example.org`, and make it resolve to the server.

**If the server answers on port 80 from the internet**, one command issues the
certificate, with [certbot](https://certbot.eff.org) installed from your distribution's
packages:

```
sudo certbot certonly --standalone -d mail.example.org
```

**If it does not** (the machine sits on your LAN, or port 80 is closed), prove that you
own the domain through a DNS record instead of through an inbound connection. This is the
DNS-01 challenge:

```
sudo certbot certonly --manual --preferred-challenges dns -d mail.example.org
```

certbot prints one `TXT` record to create at your domain registrar, named
`_acme-challenge.mail.example.org`, and waits until you have added it. The name itself can
then point wherever you like, including a private address that only your own network
resolves.

Either way the files land in `/etc/letsencrypt/live/mail.example.org/`. Point Dovecot at
them, in `/etc/dovecot/conf.d/10-ssl.conf`:

```
ssl_cert = </etc/letsencrypt/live/mail.example.org/fullchain.pem
ssl_key = </etc/letsencrypt/live/mail.example.org/privkey.pem
```

and restart it. The server you send through needs the same pair, under
`smtpd_tls_cert_file` and `smtpd_tls_key_file` if that is Postfix.

Certificates last 90 days. The `--standalone` route renews itself through the timer
certbot installs; the manual DNS route does not, so either repeat it every couple of
months or move to the DNS plugin for your provider, which certbot lists under
`certbot-dns-`.

A certificate you signed yourself cannot be countersigned by Let's Encrypt, so this is a
fresh request rather than an upgrade. You may keep your existing private key if you want
to.

**Everything below is for staying self-signed**, without a domain name or without
outside access.

## 1. Name the host in the certificate

Most self-signed certificates predate this rule and only carry a Common Name, so sign-in
fails with:

```
No subjectAltNames on the certificate match
```

The fix is on the server, not in the app: regenerate the certificate with a
**subjectAltName** covering the exact host name or IP address you connect to. With
`keytool`, add the extension when you generate the key pair:

```
keytool -genkeypair -keyalg RSA -alias myserver -validity 365 \
  -dname "CN=mail.example.org, O=Example, C=FR" \
  -ext "SAN=DNS:mail.example.org,IP:192.168.1.10"
```

With `openssl`, put `subjectAltName = @alt_names` in the `v3_req` section of your
config and list every name and address under `[alt_names]` (`DNS.1 =`, `IP.1 =`).

## 2. Trust the authority on the phone

A correctly named certificate still has to be trusted, which is the other half of the job,
and it is where most attempts stop. Android will not let you install just any certificate
as a trust anchor: it takes an **authority**, a certificate marked as being allowed to
sign others, and the file most tutorials produce is a plain **server certificate**, marked
as not being allowed to. So the usual single self-signed file, however well named, is
refused by the phone.

Two certificates are therefore needed, not one: a small authority of your own, which you
create once and keep for years, and the server certificate from step 1, signed by that
authority instead of by itself. The guide KaKeBr pointed to,
[creating your own certificate authority with openssl](https://arminreiter.com/2022/01/create-your-own-certificate-authority-ca-using-openssl/),
walks through it command by command; keep the `subjectAltName` from step 1 in the server
certificate while you follow it.

Install the authority, and only the authority, on the phone: **Settings → Security →
Encryption & credentials → Install a certificate → CA certificate**. Android asks you to
set a screen lock first if you have none. Sterna accepts authorities you installed
yourself alongside the system ones, so sign-in then works, with the chain and the host
name still checked.

Android keeps a permanent warning while such a certificate is installed, saying a third
party may be able to monitor the network. That warning is expected: the decision to trust
an authority belongs to the system, where you can review and remove it, rather than to a
button inside a mail app. The reasoning behind that choice, and what it costs, is spelled
out in [SECURITY.md](../SECURITY.md#transport-jmap--imap--smtp).

Thanks to [KaKeBr](https://codeberg.org/KaKeBr) for the diagnosis and the recipes
(Codeberg issue #71).
