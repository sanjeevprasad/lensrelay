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
  "port": 53417
}
```

The desktop keeps its 32-byte Ed25519 private identity in its platform config
directory. On Unix, the file is created with mode `0600`. A QR session expires
after two minutes. Android rejects expired codes, codes more than five minutes
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
expiresAt
phone display name
```

The phone opens a TCP connection to the QR's `host` and `port` (the default
pairing port is `53417`). The proof sent to the desktop contains `algorithm`, `phoneId`, the phone's
base64url SubjectPublicKeyInfo public key, and the base64url DER-encoded ECDSA
signature. The desktop recomputes `phoneId`, verifies the signature and active
session fields, persists the verified phone identity, then atomically consumes
the one-time nonce. Android saves the desktop identity only after receiving a
successful acknowledgement. Both sides therefore retain the pairing across
application restarts.

## Trust boundary

Scanning the QR establishes trust-on-first-use in the desktop identity through
the physical display channel. The signed acknowledgement step proves the
phone's identity to the desktop and consumes the one-time nonce. The initial
pairing continues immediately; the user does not wait for discovery after
scanning a code displayed by an online desktop. Local discovery is used only
for later reconnections, and Android must compare any advertised identity with
the stored public-key-derived receiver ID before connecting. The later secure
signaling handshake must also prove possession of the desktop private key.

WebRTC signaling will be authenticated against the paired identities. The
Wi-Fi-only MVP does not use public signaling, STUN, TURN, or cloud media relay.
