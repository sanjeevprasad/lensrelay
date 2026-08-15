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
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::{
    model::{ConnectionState, PairingSession, ReceiverStatus},
    pairing::{decode_payload, PairingPayload},
};

pub const PAIRING_PORT: u16 = 53_417;
const MAX_REQUEST_BYTES: u64 = 64 * 1024;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const CHALLENGE_DOMAIN: &str = "lensrelay-phone-pairing-v1";

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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairingResponse<'a> {
    ok: bool,
    message: &'a str,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredPhone {
    phone_id: String,
    phone_name: String,
    algorithm: String,
    public_key: String,
    paired_at: u64,
}

pub fn start(
    sessions: Arc<Mutex<Option<PairingSession>>>,
    receiver: Arc<Mutex<ReceiverStatus>>,
) -> Result<(String, u16), String> {
    let listener = TcpListener::bind(("0.0.0.0", PAIRING_PORT))
        .map_err(|error| format!("could not listen on pairing port {PAIRING_PORT}: {error}"))?;
    let host = local_ipv4()?;
    let address = format!("{host}:{PAIRING_PORT}");
    if let Ok(mut status) = receiver.lock() {
        status.listen_address = Some(address);
    }

    thread::Builder::new()
        .name("lensrelay-pairing".to_owned())
        .spawn(move || {
            for connection in listener.incoming() {
                match connection {
                    Ok(stream) => handle_connection(stream, &sessions, &receiver),
                    Err(error) => eprintln!("LensRelay pairing listener error: {error}"),
                }
            }
        })
        .map_err(|error| format!("could not start pairing listener: {error}"))?;

    Ok((host, PAIRING_PORT))
}

fn handle_connection(
    mut stream: TcpStream,
    sessions: &Arc<Mutex<Option<PairingSession>>>,
    receiver: &Arc<Mutex<ReceiverStatus>>,
) {
    let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
    let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
    let result = process_request(&stream, sessions, receiver);
    let response = match &result {
        Ok(()) => PairingResponse {
            ok: true,
            message: "Phone paired",
        },
        Err(error) => PairingResponse {
            ok: false,
            message: error,
        },
    };
    if let Ok(encoded) = serde_json::to_vec(&response) {
        let _ = stream.write_all(&encoded);
        let _ = stream.write_all(b"\n");
    }
    if let Err(error) = result {
        eprintln!("Rejected LensRelay pairing request: {error}");
    }
}

fn process_request(
    stream: &TcpStream,
    sessions: &Arc<Mutex<Option<PairingSession>>>,
    receiver: &Arc<Mutex<ReceiverStatus>>,
) -> Result<(), String> {
    let mut request_line = String::new();
    BufReader::new(stream)
        .take(MAX_REQUEST_BYTES + 1)
        .read_line(&mut request_line)
        .map_err(|error| format!("could not read pairing request: {error}"))?;
    if request_line.len() as u64 > MAX_REQUEST_BYTES {
        return Err("pairing request is too large".to_owned());
    }
    let request: PairingRequest = serde_json::from_str(&request_line)
        .map_err(|error| format!("invalid pairing request: {error}"))?;

    let session = sessions
        .lock()
        .map_err(|_| "pairing session state is unavailable".to_owned())?
        .clone()
        .ok_or_else(|| "there is no active pairing code".to_owned())?;
    let payload = decode_payload(&session)?;
    verify_request(&request, &payload)?;

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
    Ok(())
}

pub fn latest_paired_phone_name() -> Result<Option<String>, String> {
    let phones = load_phones()?;
    Ok(phones
        .into_iter()
        .max_by_key(|phone| phone.paired_at)
        .map(|phone| phone.phone_name))
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

    let path = phones_path()?;
    let parent = path
        .parent()
        .ok_or_else(|| "paired phone path has no parent".to_owned())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("could not create desktop config directory: {error}"))?;
    let encoded = serde_json::to_vec_pretty(&phones)
        .map_err(|error| format!("could not encode paired phones: {error}"))?;
    fs::write(&path, encoded).map_err(|error| format!("could not save paired phone: {error}"))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .map_err(|error| format!("could not protect paired phones: {error}"))?;
    }
    Ok(())
}

fn load_phones() -> Result<Vec<StoredPhone>, String> {
    match fs::read(phones_path()?) {
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

fn decode(value: &str, label: &str) -> Result<Vec<u8>, String> {
    URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| format!("invalid {label}"))
}

fn local_ipv4() -> Result<String, String> {
    let socket = UdpSocket::bind("0.0.0.0:0")
        .map_err(|error| format!("could not inspect the local network: {error}"))?;
    socket
        .connect("1.1.1.1:80")
        .map_err(|error| format!("could not select a local network route: {error}"))?;
    let address = socket
        .local_addr()
        .map_err(|error| format!("could not determine the local address: {error}"))?;
    if address.ip().is_loopback() {
        return Err("no LAN address is available".to_owned());
    }
    Ok(address.ip().to_string())
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
        };

        let digest = Sha256::digest(challenge(&payload, "Test phone"));
        let encoded = digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(
            encoded,
            "c3c6d3896826db822553a8004b286f14c8491333d8608b0acc8186cd7a7c44a1"
        );
    }
}
