use std::{
    fs, io,
    io::{BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream, UdpSocket},
    path::PathBuf,
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use directories::ProjectDirs;
use p256::{
    ecdsa::{signature::Verifier, Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    media_auth::MediaAuthorizer,
    model::{ConnectionState, PairedDevice, PairingSession, ReceiverStatus},
    pairing::{decode_payload, DesktopIdentity, PairingPayload},
};

pub const PAIRING_PORT: u16 = 53_417;
const MAX_REQUEST_BYTES: u64 = 64 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const CHALLENGE_DOMAIN: &str = "lensrelay-phone-pairing-v1";
const UNPAIR_CHALLENGE_DOMAIN: &str = "lensrelay-phone-unpair-v1";
const UNPAIR_ACK_DOMAIN: &str = "lensrelay-desktop-unpair-ack-v1";
const MAX_UNPAIR_CLOCK_SKEW_SECONDS: u64 = 5 * 60;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RequestKind {
    #[serde(rename = "type", default)]
    request_type: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairingRequest {
    version: u8,
    receiver_id: String,
    nonce: String,
    expires_at: u64,
    algorithm: String,
    phone_id: String,
    phone_name: String,
    public_key: String,
    signature: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UnpairRequest {
    version: u8,
    receiver_id: String,
    algorithm: String,
    phone_id: String,
    issued_at: u64,
    nonce: String,
    signature: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StoredPhone {
    pub(crate) phone_id: String,
    pub(crate) phone_name: String,
    pub(crate) algorithm: String,
    pub(crate) public_key: String,
    pub(crate) paired_at: u64,
}

pub fn start(
    host: &str,
    sessions: Arc<Mutex<Option<PairingSession>>>,
    receiver: Arc<Mutex<ReceiverStatus>>,
    identity: Arc<DesktopIdentity>,
    tls: Arc<ServerConfig>,
    media_auth: Arc<MediaAuthorizer>,
) -> Result<(String, u16), String> {
    let listener = TcpListener::bind((host, PAIRING_PORT))
        .map_err(|error| format!("could not listen on pairing port {PAIRING_PORT}: {error}"))?;
    let address = format!("{host}:{PAIRING_PORT}");
    if let Ok(mut status) = receiver.lock() {
        status.listen_address = Some(address);
    }

    thread::Builder::new()
        .name("lensrelay-pairing".to_owned())
        .spawn(move || {
            for connection in listener.incoming() {
                match connection {
                    Ok(stream) => handle_connection(
                        stream,
                        &sessions,
                        &receiver,
                        &identity,
                        tls.clone(),
                        &media_auth,
                    ),
                    Err(error) => eprintln!("LensRelay pairing listener error: {error}"),
                }
            }
        })
        .map_err(|error| format!("could not start pairing listener: {error}"))?;

    Ok((host.to_owned(), PAIRING_PORT))
}

fn handle_connection(
    stream: TcpStream,
    sessions: &Arc<Mutex<Option<PairingSession>>>,
    receiver: &Arc<Mutex<ReceiverStatus>>,
    identity: &Arc<DesktopIdentity>,
    tls: Arc<ServerConfig>,
    media_auth: &Arc<MediaAuthorizer>,
) {
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let connection = match ServerConnection::new(tls) {
        Ok(connection) => connection,
        Err(error) => {
            eprintln!("LensRelay pairing TLS error: {error}");
            return;
        }
    };
    let mut stream = StreamOwned::new(connection, stream);
    let result = process_request(&mut stream, sessions, receiver, identity, media_auth);
    if let Ok(encoded) = serde_json::to_vec(
        &result.unwrap_or_else(|error| serde_json::json!({ "ok": false, "message": error })),
    ) {
        let _ = stream.write_all(&encoded);
        let _ = stream.write_all(b"\n");
    }
}

fn process_request<R: Read>(
    stream: &mut R,
    sessions: &Arc<Mutex<Option<PairingSession>>>,
    receiver: &Arc<Mutex<ReceiverStatus>>,
    identity: &DesktopIdentity,
    media_auth: &MediaAuthorizer,
) -> Result<serde_json::Value, String> {
    let mut request_line = String::new();
    BufReader::new(stream)
        .take(MAX_REQUEST_BYTES + 1)
        .read_line(&mut request_line)
        .map_err(|error| format!("could not read pairing request: {error}"))?;
    if request_line.len() as u64 > MAX_REQUEST_BYTES {
        return Err("pairing request is too large".to_owned());
    }
    let kind: RequestKind = serde_json::from_str(&request_line)
        .map_err(|error| format!("invalid pairing request: {error}"))?;

    match kind.request_type.as_str() {
        "" | "pair" => process_pairing_request(&request_line, sessions, receiver, media_auth),
        "unpair" => process_unpair_request(&request_line, receiver, identity),
        _ => return Err("unsupported LensRelay request type".to_owned()),
    }
}

fn process_pairing_request(
    request_line: &str,
    sessions: &Arc<Mutex<Option<PairingSession>>>,
    receiver: &Arc<Mutex<ReceiverStatus>>,
    media_auth: &MediaAuthorizer,
) -> Result<serde_json::Value, String> {
    let request: PairingRequest = serde_json::from_str(&request_line)
        .map_err(|error| format!("invalid pairing request: {error}"))?;

    let session = sessions
        .lock()
        .map_err(|_| "pairing session state is unavailable".to_owned())?
        .clone()
        .ok_or_else(|| "there is no active pairing code".to_owned())?;
    let payload = decode_payload(&session)?;
    verify_request(&request, &payload)?;
    let media_token = media_auth.publisher_token(&payload.receiver_id)?;

    let mut active = sessions
        .lock()
        .map_err(|_| "pairing session state is unavailable".to_owned())?;
    if active.as_ref().map(|value| &value.payload) != Some(&session.payload) {
        return Err("pairing code was already used or replaced".to_owned());
    }
    persist_phone(&request)?;
    *active = None;

    let mut status = receiver
        .lock()
        .map_err(|_| "receiver state is unavailable".to_owned())?;
    status.connection_state = ConnectionState::Paired;
    status.device_name = Some(request.phone_name.clone());
    Ok(serde_json::json!({
        "ok": true,
        "message": "Phone paired",
        "receiverId": request.receiver_id,
        "phoneId": request.phone_id,
        "nonce": request.nonce,
        "mediaToken": media_token,
    }))
}

fn process_unpair_request(
    request_line: &str,
    receiver: &Arc<Mutex<ReceiverStatus>>,
    identity: &DesktopIdentity,
) -> Result<serde_json::Value, String> {
    let request: UnpairRequest = serde_json::from_str(request_line)
        .map_err(|error| format!("invalid unpair request: {error}"))?;
    if request.version != 1 || request.receiver_id != identity.receiver_id() {
        return Err("unpair request is for a different desktop".to_owned());
    }
    if request.algorithm != "ES256" {
        return Err("unsupported phone identity algorithm".to_owned());
    }
    let now = crate::pairing::unix_time()?;
    if now.abs_diff(request.issued_at) > MAX_UNPAIR_CLOCK_SKEW_SECONDS {
        return Err("unpair request timestamp is outside the allowed window".to_owned());
    }
    let nonce = decode(&request.nonce, "unpair nonce")?;
    if nonce.len() < 16 {
        return Err("unpair nonce is too short".to_owned());
    }

    let mut phones = load_phones()?;
    if let Some(phone) = phones
        .iter()
        .find(|phone| phone.phone_id == request.phone_id)
    {
        verify_unpair_request(&request, phone)?;
        phones.retain(|phone| phone.phone_id != request.phone_id);
        save_phones(&phones)?;
    }

    let latest = phones.iter().max_by_key(|phone| phone.paired_at);
    let mut status = receiver
        .lock()
        .map_err(|_| "receiver state is unavailable".to_owned())?;
    status.connection_state = if latest.is_some() {
        ConnectionState::Paired
    } else {
        ConnectionState::Idle
    };
    status.device_name = latest.map(|phone| phone.phone_name.clone());
    drop(status);

    let acknowledgement =
        unpair_ack_challenge(&request.receiver_id, &request.phone_id, &request.nonce);
    Ok(serde_json::json!({
        "ok": true,
        "message": "Phone unpaired",
        "receiverId": request.receiver_id,
        "phoneId": request.phone_id,
        "nonce": request.nonce,
        "signature": identity.sign(&acknowledgement),
    }))
}

fn verify_unpair_request(request: &UnpairRequest, phone: &StoredPhone) -> Result<(), String> {
    if phone.algorithm != request.algorithm {
        return Err("phone identity algorithm does not match pairing".to_owned());
    }
    let public_key = decode(&phone.public_key, "stored phone public key")?;
    let digest = Sha256::digest(&public_key);
    if request.phone_id != URL_SAFE_NO_PAD.encode(&digest[..16]) {
        return Err("phone identity does not match pairing".to_owned());
    }
    let verifying_key = VerifyingKey::from_public_key_der(&public_key)
        .map_err(|_| "stored phone public key is invalid".to_owned())?;
    let signature_bytes = decode(&request.signature, "phone unpair signature")?;
    let signature = Signature::from_der(&signature_bytes)
        .map_err(|_| "invalid phone unpair signature encoding".to_owned())?;
    verifying_key
        .verify(
            &unpair_challenge(
                &request.receiver_id,
                &request.phone_id,
                &request.nonce,
                request.issued_at,
            ),
            &signature,
        )
        .map_err(|_| "phone unpair signature is invalid".to_owned())
}

pub fn latest_paired_phone_name() -> Result<Option<String>, String> {
    let phones = load_phones()?;
    Ok(phones
        .into_iter()
        .max_by_key(|phone| phone.paired_at)
        .map(|phone| phone.phone_name))
}

pub fn paired_devices() -> Result<Vec<PairedDevice>, String> {
    let mut phones = load_phones()?;
    phones.sort_by_key(|phone| std::cmp::Reverse(phone.paired_at));
    Ok(phones
        .into_iter()
        .map(|phone| PairedDevice {
            phone_id: phone.phone_id,
            phone_name: phone.phone_name,
            paired_at: phone.paired_at,
        })
        .collect())
}

pub fn forget_phone(phone_id: &str) -> Result<(), String> {
    let mut phones = load_phones()?;
    let original_len = phones.len();
    phones.retain(|phone| phone.phone_id != phone_id);
    if phones.len() == original_len {
        return Err("paired phone was not found".to_owned());
    }
    save_phones(&phones)
}

fn persist_phone(request: &PairingRequest) -> Result<(), String> {
    let mut phones = load_phones()?;
    phones.retain(|phone| phone.phone_id != request.phone_id);
    phones.push(StoredPhone {
        phone_id: request.phone_id.clone(),
        phone_name: request.phone_name.trim().to_owned(),
        algorithm: request.algorithm.clone(),
        public_key: request.public_key.clone(),
        paired_at: crate::pairing::unix_time()?,
    });

    save_phones(&phones)
}

fn save_phones(phones: &[StoredPhone]) -> Result<(), String> {
    let path = phones_path()?;
    let parent = path
        .parent()
        .ok_or_else(|| "paired phone path has no parent".to_owned())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("could not create desktop config directory: {error}"))?;
    let encoded = serde_json::to_vec_pretty(phones)
        .map_err(|error| format!("could not encode paired phones: {error}"))?;
    crate::secure_storage::write(&path, &encoded)
        .map_err(|error| format!("could not save paired phone: {error}"))
}

pub(crate) fn load_phones() -> Result<Vec<StoredPhone>, String> {
    match crate::secure_storage::read(&phones_path()?) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map_err(|error| format!("could not parse paired phones: {error}")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(error) => Err(format!("could not read paired phones: {error}")),
    }
}

fn phones_path() -> Result<PathBuf, String> {
    let project = ProjectDirs::from("com", "atanx", "LensRelay")
        .ok_or_else(|| "could not determine the desktop config directory".to_owned())?;
    Ok(project.config_dir().join("paired-phones.json"))
}

fn verify_request(request: &PairingRequest, payload: &PairingPayload) -> Result<(), String> {
    if request.version != 1
        || request.receiver_id != payload.receiver_id
        || request.nonce != payload.nonce
        || request.expires_at != payload.expires_at
    {
        return Err("pairing request does not match the active code".to_owned());
    }
    let now = crate::pairing::unix_time()?;
    if payload.expires_at < now {
        return Err("pairing code has expired".to_owned());
    }
    if request.algorithm != "ES256" {
        return Err("unsupported phone identity algorithm".to_owned());
    }
    let phone_name = request.phone_name.trim();
    if phone_name.is_empty() || phone_name.chars().count() > 80 {
        return Err("invalid phone name".to_owned());
    }

    let public_key = decode(&request.public_key, "phone public key")?;
    let digest = Sha256::digest(&public_key);
    if request.phone_id != URL_SAFE_NO_PAD.encode(&digest[..16]) {
        return Err("phone identity does not match its public key".to_owned());
    }
    let verifying_key = VerifyingKey::from_public_key_der(&public_key)
        .map_err(|_| "invalid phone public key".to_owned())?;
    let signature_bytes = decode(&request.signature, "phone signature")?;
    let signature = Signature::from_der(&signature_bytes)
        .map_err(|_| "invalid phone signature encoding".to_owned())?;
    verifying_key
        .verify(&challenge(payload, phone_name), &signature)
        .map_err(|_| "phone pairing signature is invalid".to_owned())
}

fn challenge(payload: &PairingPayload, phone_name: &str) -> Vec<u8> {
    let mut bytes = Vec::new();
    for field in [
        CHALLENGE_DOMAIN,
        &payload.receiver_id,
        &payload.public_key,
        &payload.nonce,
        &payload.media_certificate_fingerprint,
    ] {
        let encoded = field.as_bytes();
        bytes.extend_from_slice(&(encoded.len() as u32).to_be_bytes());
        bytes.extend_from_slice(encoded);
    }
    bytes.extend_from_slice(&payload.expires_at.to_be_bytes());
    let encoded_name = phone_name.as_bytes();
    bytes.extend_from_slice(&(encoded_name.len() as u32).to_be_bytes());
    bytes.extend_from_slice(encoded_name);
    bytes
}

fn unpair_challenge(receiver_id: &str, phone_id: &str, nonce: &str, issued_at: u64) -> Vec<u8> {
    let mut bytes = encoded_fields(&[UNPAIR_CHALLENGE_DOMAIN, receiver_id, phone_id, nonce]);
    bytes.extend_from_slice(&issued_at.to_be_bytes());
    bytes
}

fn unpair_ack_challenge(receiver_id: &str, phone_id: &str, nonce: &str) -> Vec<u8> {
    encoded_fields(&[UNPAIR_ACK_DOMAIN, receiver_id, phone_id, nonce])
}

fn encoded_fields(fields: &[&str]) -> Vec<u8> {
    let mut bytes = Vec::new();
    for field in fields {
        let encoded = field.as_bytes();
        bytes.extend_from_slice(&(encoded.len() as u32).to_be_bytes());
        bytes.extend_from_slice(encoded);
    }
    bytes
}

fn decode(value: &str, label: &str) -> Result<Vec<u8>, String> {
    URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| format!("invalid {label}"))
}

pub fn select_lan_ipv4() -> Result<String, String> {
    let socket = UdpSocket::bind("0.0.0.0:0")
        .map_err(|error| format!("could not inspect the local network: {error}"))?;
    socket
        .connect("1.1.1.1:80")
        .map_err(|error| format!("could not select a local network route: {error}"))?;
    let address = socket
        .local_addr()
        .map_err(|error| format!("could not determine the local address: {error}"))?;
    let ip = match address.ip() {
        std::net::IpAddr::V4(ip) if ip.is_private() || ip.is_link_local() => ip,
        other => {
            return Err(format!(
                "default network interface {other} is not a private LAN interface"
            ))
        }
    };
    Ok(ip.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn challenge_encoding_matches_android() {
        let payload = PairingPayload {
            version: 1,
            receiver_id: "rid".to_owned(),
            receiver_name: "Desk".to_owned(),
            public_key: "key".to_owned(),
            nonce: "nonce".to_owned(),
            expires_at: 1_100,
            host: "192.168.1.20".to_owned(),
            port: PAIRING_PORT,
            control_port: 53_419,
            media_certificate_fingerprint: "ab".repeat(32),
        };

        let digest = Sha256::digest(challenge(&payload, "Test phone"));
        let encoded = digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(
            encoded,
            "4761d4b9a22753187e0f39fcf10b3f3dec4e3e70762d493d39b59ce218479b5c"
        );
    }

    #[test]
    fn unpair_challenges_match_android() {
        let request = Sha256::digest(unpair_challenge("rid", "pid", "nonce", 1_100));
        assert_eq!(
            request
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>(),
            "e38c7a3779a795948d54838a3322986fdf17203645d4551bf7eb2ee5a30c720b"
        );
        let acknowledgement = Sha256::digest(unpair_ack_challenge("rid", "pid", "nonce"));
        assert_eq!(
            acknowledgement
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>(),
            "2fdaeea2a78779ef769abfbf8b0afca7b1e5b14d74c9262471dd89b9b71728dd"
        );
    }
}
