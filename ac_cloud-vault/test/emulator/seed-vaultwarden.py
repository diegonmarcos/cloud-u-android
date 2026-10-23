#!/usr/bin/env python3
"""Registers a throwaway account on a CI Vaultwarden and seeds one login item (client-side crypto, like a Bitwarden
client). Usage: seed-vaultwarden.py BASE_URL EMAIL MASTER_PASSWORD ITEM_NAME. Prints nothing secret."""
import base64, hashlib, hmac, json, os, sys, urllib.error, urllib.request, urllib.parse, uuid
from cryptography.hazmat.primitives import hashes, padding, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

base, email, pw, item = sys.argv[1].rstrip("/"), sys.argv[2].lower(), sys.argv[3], sys.argv[4]
b64 = lambda b: base64.b64encode(b).decode()
ITER = 600000

def enc(data, ek, mk):
    iv = os.urandom(16)
    p = padding.PKCS7(128).padder(); d = p.update(data) + p.finalize()
    e = Cipher(algorithms.AES(ek), modes.CBC(iv)).encryptor(); ct = e.update(d) + e.finalize()
    return "2.%s|%s|%s" % (b64(iv), b64(ct), b64(hmac.new(mk, iv + ct, hashlib.sha256).digest()))

def expand(prk, info):  # HKDF-Expand, one block
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()

def post(path, body, headers=None, form=False):
    data = urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()
    h = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json", **(headers or {})}
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, data, h)) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as ex:
        print("HTTP %s on %s: %s" % (ex.code, path, ex.read().decode(errors="replace")[:600]), file=sys.stderr)  # server message, no secrets
        raise

mk = hashlib.pbkdf2_hmac("sha256", pw.encode(), email.encode(), ITER, 32)
pw_hash = b64(hashlib.pbkdf2_hmac("sha256", mk, pw.encode(), 1, 32))
sk_enc, sk_mac = expand(mk, b"enc"), expand(mk, b"mac")
user_key = os.urandom(64)
rsa_key = rsa.generate_private_key(65537, 2048)
priv = rsa_key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
pub = rsa_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
mkkey, keys = enc(user_key, sk_enc, sk_mac), {"publicKey": b64(pub), "encryptedPrivateKey": enc(priv, user_key[:32], user_key[32:])}
try:  # current Vaultwarden: verification token (returned in the body while mail is off), then register/finish
    req = urllib.request.Request(base + "/identity/accounts/register/send-verification-email",
                                 json.dumps({"email": email, "name": "CI Seed", "receiveMarketingEmails": False}).encode(),
                                 {"Content-Type": "application/json"})
    raw = urllib.request.urlopen(req).read().decode().strip()
    try:
        vtoken = json.loads(raw)
    except ValueError:
        vtoken = raw
    if not vtoken:
        raise SystemExit("no verification token returned (mail enabled?)")
    post("/identity/accounts/register/finish", {
        "email": email, "emailVerificationToken": vtoken, "masterPasswordHash": pw_hash, "masterPasswordHint": None,
        "userSymmetricKey": mkkey, "userAsymmetricKeys": keys, "kdf": 0, "kdfIterations": ITER,
    })
except urllib.error.HTTPError as ex:
    if ex.code != 404:
        raise
    post("/identity/accounts/register", {  # older Vaultwarden
        "email": email, "name": "CI Seed", "masterPasswordHash": pw_hash, "masterPasswordHint": None,
        "key": mkkey, "kdf": 0, "kdfIterations": ITER, "keys": keys,
    })
tok = post("/identity/connect/token", {
    "grant_type": "password", "username": email, "password": pw_hash, "scope": "api offline_access",
    "client_id": "web", "deviceType": "9", "deviceName": "ci-seed", "deviceIdentifier": str(uuid.uuid4()),
}, form=True)
auth = {"Authorization": "Bearer " + tok["access_token"]}
uid = json.loads(urllib.request.urlopen(urllib.request.Request(base + "/api/accounts/profile", headers=auth)).read())["id"]
e = lambda s: enc(s.encode(), user_key[:32], user_key[32:])
post("/api/ciphers", {
    "type": 1, "name": e(item), "notes": None, "favorite": False, "organizationId": None, "folderId": None,
    "reprompt": 0, "encryptedFor": uid, "login": {"username": e("ci-user"), "password": e("ci-seed-secret"), "uris": []},
}, {"Authorization": "Bearer " + tok["access_token"]})
print("seeded 1 item for", email)
