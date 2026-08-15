use std::{
    fs, io,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use directories::ProjectDirs;
use ed25519_dalek::{Signer, SigningKey};
use qrcode::{render::svg, QrCode};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::model::PairingSession;

const PAIRING_LIFETIME_SECONDS: u64 = 30;
const PAYLOAD_PREFIX: &str = "lensrelay:pair:";

pub struct DesktopIdentity {
    signing_key: SigningKey,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PairingPayload {
    pub(crate) version: u8,
    pub(crate) receiver_id: String,
    pub(crate) receiver_name: String,
    pub(crate) public_key: String,
    pub(crate) nonce: String,
    pub(crate) expires_at: u64,
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) control_port: u16,
    pub(crate) media_certificate_fingerprint: String,
}

impl DesktopIdentity {
    pub fn load_or_create() -> Result<Self, String> {
        let path = identity_path()?;
        let key_bytes = match fs::read(&path) {
            Ok(bytes) => bytes
                .try_into()
                .map_err(|_| "desktop identity has an invalid length".to_owned())?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let mut bytes = [0_u8; 32];
                getrandom::fill(&mut bytes)
                    .map_err(|error| format!("could not generate desktop identity: {error}"))?;
                persist_identity(&path, &bytes)?;
                bytes
            }
            Err(error) => return Err(format!("could not read desktop identity: {error}")),
        };

        Ok(Self {
            signing_key: SigningKey::from_bytes(&key_bytes),
        })
    }

    pub fn create_pairing_session(
        &self,
        host: &str,
        port: u16,
        control_port: u16,
        media_certificate_fingerprint: &str,
    ) -> Result<PairingSession, String> {
        let public_key = self.signing_key.verifying_key().to_bytes();
        let digest = Sha256::digest(public_key);
        let receiver_id = URL_SAFE_NO_PAD.encode(&digest[..16]);
        let fingerprint = digest.chunks(4).map(hex).collect::<Vec<_>>().join(" ");
        let receiver_name = receiver_name();

        let mut nonce = [0_u8; 24];
        getrandom::fill(&mut nonce)
            .map_err(|error| format!("could not generate pairing nonce: {error}"))?;
        let expires_at = unix_time()?.saturating_add(PAIRING_LIFETIME_SECONDS);

        let payload_json = serde_json::to_vec(&PairingPayload {
            version: 1,
            receiver_id: receiver_id.clone(),
            receiver_name: receiver_name.clone(),
            public_key: URL_SAFE_NO_PAD.encode(public_key),
            nonce: URL_SAFE_NO_PAD.encode(nonce),
            expires_at,
            host: host.to_owned(),
            port,
            control_port,
            media_certificate_fingerprint: media_certificate_fingerprint.to_owned(),
        })
        .map_err(|error| format!("could not encode pairing payload: {error}"))?;
        let payload = format!("{PAYLOAD_PREFIX}{}", URL_SAFE_NO_PAD.encode(payload_json));
        let qr_svg = render_qr(&payload)?;

        Ok(PairingSession {
            payload,
            qr_svg,
            receiver_id,
            receiver_name,
            fingerprint,
            expires_at,
        })
    }

    pub fn receiver_id(&self) -> String {
        let digest = Sha256::digest(self.signing_key.verifying_key().to_bytes());
        URL_SAFE_NO_PAD.encode(&digest[..16])
    }

    pub(crate) fn sign(&self, message: &[u8]) -> String {
        URL_SAFE_NO_PAD.encode(self.signing_key.sign(message).to_bytes())
    }
}

pub(crate) fn decode_payload(session: &PairingSession) -> Result<PairingPayload, String> {
    let encoded = session
        .payload
        .strip_prefix(PAYLOAD_PREFIX)
        .ok_or_else(|| "active pairing payload has an invalid prefix".to_owned())?;
    let json = URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|error| format!("could not decode active pairing payload: {error}"))?;
    serde_json::from_slice(&json)
        .map_err(|error| format!("could not parse active pairing payload: {error}"))
}

fn identity_path() -> Result<PathBuf, String> {
    let project = ProjectDirs::from("com", "atanx", "LensRelay")
        .ok_or_else(|| "could not determine the desktop config directory".to_owned())?;
    Ok(project.config_dir().join("identity.key"))
}

fn persist_identity(path: &PathBuf, bytes: &[u8; 32]) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "desktop identity path has no parent".to_owned())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("could not create desktop config directory: {error}"))?;
    fs::write(path, bytes).map_err(|error| format!("could not save desktop identity: {error}"))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .map_err(|error| format!("could not protect desktop identity: {error}"))?;
    }

    Ok(())
}

fn render_qr(payload: &str) -> Result<String, String> {
    let code = QrCode::new(payload.as_bytes())
        .map_err(|error| format!("could not generate pairing QR code: {error}"))?;
    Ok(code
        .render::<svg::Color<'_>>()
        .min_dimensions(320, 320)
        .dark_color(svg::Color("#08100d"))
        .light_color(svg::Color("#f5fffc"))
        .build())
}

fn receiver_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "LensRelay Desktop".to_owned())
}

pub(crate) fn unix_time() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|error| format!("system clock is before Unix epoch: {error}"))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_payload_is_versioned_and_url_safe() {
        let identity = DesktopIdentity {
            signing_key: SigningKey::from_bytes(&[7_u8; 32]),
        };
        let session = identity
            .create_pairing_session("192.168.1.20", 53_417, 53_419, &"ab".repeat(32))
            .expect("session should be generated");

        let encoded = session
            .payload
            .strip_prefix(PAYLOAD_PREFIX)
            .expect("payload prefix");
        let json = URL_SAFE_NO_PAD.decode(encoded).expect("base64 payload");
        let value: serde_json::Value = serde_json::from_slice(&json).expect("JSON payload");

        assert_eq!(value["version"], 1);
        assert_eq!(value["receiverId"], session.receiver_id);
        assert!(value["nonce"]
            .as_str()
            .is_some_and(|value| value.len() >= 32));
        assert!(session.qr_svg.contains("<svg"));
        assert_eq!(value["host"], "192.168.1.20");
        assert_eq!(value["port"], 53_417);
        assert_eq!(value["controlPort"], 53_419);
        assert_eq!(value["mediaCertificateFingerprint"], "ab".repeat(32));
    }
}
