# Local pairing protocol

LensRelay pairing is local, explicit, and based on a persistent desktop
identity. It does not require a LensRelay account or internet service.

## Version 1 QR payload

The QR text has this envelope:

```text
lensrelay:pair:<base64url-without-padding(JSON)>
```

The JSON document contains:

```json
{
  "version": 1,
  "receiverId": "base64url(first 16 bytes of SHA-256(publicKey))",
  "receiverName": "Desktop display name",
  "publicKey": "base64url(32-byte Ed25519 public key)",
  "nonce": "base64url(24 random bytes)",
  "expiresAt": 1786777200,
  "host": "192.168.1.20",
  "port": 53417,
  "controlPort": 53419,
  "mediaCertificateFingerprint": "sha256 certificate fingerprint"
}
```

The desktop keeps its 32-byte Ed25519 private identity in its platform config
directory. Windows encrypts it for the current user with DPAPI; Unix writes it
atomically with mode `0600`. A QR session expires after 30 seconds. Android
rejects expired codes, codes more than five minutes
in the future, malformed keys and nonces, and receiver IDs that do not match
the public key.

Android encrypts accepted desktop identities with an AES-256-GCM key held by
Android Keystore. QR images are analyzed in memory and are neither saved nor
uploaded.

## Phone identity and proof

Android creates a non-exportable P-256 ECDSA identity key in Android Keystore
on the first pairing attempt. Its public identity uses algorithm identifier
`ES256`; `phoneId` is base64url of the first 16 bytes of SHA-256 over the
SubjectPublicKeyInfo-encoded public key.

The phone signs a deterministic pairing transcript with `SHA256withECDSA`.
Each string is encoded as a four-byte unsigned big-endian length followed by
its UTF-8 bytes. The expiration is an eight-byte signed big-endian integer:

```text
"lensrelay-phone-pairing-v1"
receiverId
desktop publicKey
pairing nonce
media TLS certificate fingerprint
expiresAt
phone display name
```

The phone opens a certificate-pinned TLS connection to the QR's `host` and
`port` (the default pairing port is `53417`). The proof sent to the desktop
contains `algorithm`, `phoneId`, the phone's
base64url SubjectPublicKeyInfo public key, and the base64url DER-encoded ECDSA
signature. The desktop recomputes `phoneId`, verifies the signature and active
session fields, persists the verified phone identity, then atomically consumes
the one-time nonce. Android saves the desktop identity only after receiving a
successful acknowledgement matching the receiver, phone, and one-time nonce.
The acknowledgement includes a 24-hour publish-only JWT scoped to this
receiver's media namespace. Both sides therefore retain the pairing across
application restarts.

## Trust boundary

Scanning the QR establishes trust in the desktop identity through the physical
display channel. Both screens show the same six-digit security code derived
from the desktop key, pairing nonce, and pinned certificate. The user confirms
that it matches before pairing continues. Pinned TLS authenticates the desktop;
the signed request proves the phone identity and consumes the one-time nonce. The initial
pairing continues immediately; the user does not wait for discovery after
scanning a code displayed by an online desktop. Local discovery is used only
for later reconnections, and Android must compare any advertised identity with
the stored public-key-derived receiver ID before connecting. The later secure
signaling handshake must also prove possession of the desktop private key.

MoQ media and the persistent control channel use the TLS certificate pinned by
the QR. The phone additionally signs the control hello with its paired P-256
identity. A successful control authentication refreshes the short-lived media
publishing token. The relay gives the local desktop preview a separate
subscribe-only token. The Wi-Fi-only MVP does not use cloud signaling or a
cloud relay.

Paired-phone metadata, media authorization keys, and TLS private keys are
written atomically. Windows protects private data with current-user DPAPI;
Unix restricts private files to mode `0600`. Pairing, control, and QUIC media
bind only the selected private LAN address. The desktop WebSocket preview
fallback listens on loopback only.
