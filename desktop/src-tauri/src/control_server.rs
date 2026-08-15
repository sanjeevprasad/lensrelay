use std::{
    collections::HashMap,
    fs::File,
    io::{self, BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    path::Path,
    sync::{mpsc, Arc, Mutex},
    thread,
    time::Duration,
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use p256::{
    ecdsa::{signature::Verifier, Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
};
use rustls::{ServerConfig, ServerConnection, StreamOwned};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::{media_auth::MediaAuthorizer, pairing::DesktopIdentity, pairing_server};

pub const CONTROL_PORT: u16 = 53_419;
const CONTROL_DOMAIN: &str = "lensrelay-phone-control-v1";
const MAX_CLOCK_SKEW_SECONDS: u64 = 5 * 60;
const IO_TICK: Duration = Duration::from_millis(250);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(5);
// Remote start can wait for an explicit decision on the phone.
const REMOTE_START_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_MESSAGE_BYTES: u64 = 256 * 1024;
const PRESENCE_TIMEOUT_SECONDS: u64 = 8;

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlStatus {
    pub connected: bool,
    pub phone_id: Option<String>,
    pub phone_name: Option<String>,
    pub last_seen: Option<u64>,
    pub capabilities: Option<Value>,
    pub state: Option<Value>,
}

struct ActiveConnection {
    session_id: String,
    sender: mpsc::SyncSender<Outbound>,
}

struct Outbound {
    id: String,
    message: Value,
    response: mpsc::SyncSender<Result<Value, String>>,
}

pub struct ControlHub {
    status: Mutex<ControlStatus>,
    active: Mutex<Option<ActiveConnection>>,
}

impl ControlHub {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            status: Mutex::new(ControlStatus::default()),
            active: Mutex::new(None),
        })
    }

    pub fn status(&self) -> Result<ControlStatus, String> {
        let mut snapshot = self
            .status
            .lock()
            .map(|status| status.clone())
            .map_err(|_| "control status is unavailable".to_owned())?;
        if snapshot.connected {
            if let (Some(last_seen), Ok(now)) = (snapshot.last_seen, crate::pairing::unix_time()) {
                if now.saturating_sub(last_seen) > PRESENCE_TIMEOUT_SECONDS {
                    snapshot.connected = false;
                }
            }
        }
        Ok(snapshot)
    }

    pub fn send_command(&self, command: &str, parameters: Value) -> Result<Value, String> {
        let id = random_id()?;
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        let message = json!({
            "type": "command",
            "id": id,
            "command": command,
            "parameters": parameters,
        });
        let active = self
            .active
            .lock()
            .map_err(|_| "control connection is unavailable".to_owned())?;
        let connection = active
            .as_ref()
            .ok_or_else(|| "paired phone is offline".to_owned())?;
        connection
            .sender
            .send(Outbound {
                id,
                message,
                response: response_tx,
            })
            .map_err(|_| "control connection closed".to_owned())?;
        drop(active);
        let timeout = if command == "start" {
            REMOTE_START_TIMEOUT
        } else {
            COMMAND_TIMEOUT
        };
        response_rx
            .recv_timeout(timeout)
            .map_err(|_| format!("Phone did not respond to the {command} command in time"))?
    }

    fn connect(
        &self,
        session_id: String,
        phone_id: String,
        phone_name: String,
        sender: mpsc::SyncSender<Outbound>,
    ) -> Result<(), String> {
        let now = crate::pairing::unix_time()?;
        *self
            .active
            .lock()
            .map_err(|_| "control connection is unavailable".to_owned())? =
            Some(ActiveConnection { session_id, sender });
        *self
            .status
            .lock()
            .map_err(|_| "control status is unavailable".to_owned())? = ControlStatus {
            connected: true,
            phone_id: Some(phone_id),
            phone_name: Some(phone_name),
            last_seen: Some(now),
            capabilities: None,
            state: None,
        };
        Ok(())
    }

    fn update(&self, message_type: &str, payload: Value) {
        if let Ok(mut status) = self.status.lock() {
            status.last_seen = crate::pairing::unix_time().ok();
            match message_type {
                "capabilities" => status.capabilities = Some(payload),
                "state" => status.state = Some(payload),
                _ => {}
            }
        }
    }

    fn disconnect(&self, session_id: &str) {
        let should_clear = self
            .active
            .lock()
            .ok()
            .and_then(|mut active| {
                let matches = active
                    .as_ref()
                    .is_some_and(|connection| connection.session_id == session_id);
                if matches {
                    *active = None;
                }
                Some(matches)
            })
            .unwrap_or(false);
        if should_clear {
            if let Ok(mut status) = self.status.lock() {
                status.connected = false;
                status.last_seen = crate::pairing::unix_time().ok();
            }
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Hello {
    #[serde(rename = "type")]
    message_type: String,
    version: u8,
    receiver_id: String,
    algorithm: String,
    phone_id: String,
    issued_at: u64,
    nonce: String,
    signature: String,
}

pub fn start(
    host: &str,
    tls: Arc<ServerConfig>,
    identity: Arc<DesktopIdentity>,
    media_auth: Arc<MediaAuthorizer>,
    hub: Arc<ControlHub>,
) -> Result<(), String> {
    let listener = TcpListener::bind((host, CONTROL_PORT))
        .map_err(|error| format!("could not listen on control port {CONTROL_PORT}: {error}"))?;
    thread::Builder::new()
        .name("lensrelay-control-listener".to_owned())
        .spawn(move || {
            for incoming in listener.incoming() {
                match incoming {
                    Ok(stream) => {
                        let tls = tls.clone();
                        let identity = identity.clone();
                        let media_auth = media_auth.clone();
                        let hub = hub.clone();
                        let _ = thread::Builder::new()
                            .name("lensrelay-control-connection".to_owned())
                            .spawn(move || {
                                if let Err(error) =
                                    handle_connection(stream, tls, identity, media_auth, hub)
                                {
                                    eprintln!("LensRelay control connection ended: {error}");
                                }
                            });
                    }
                    Err(error) => eprintln!("LensRelay control listener error: {error}"),
                }
            }
        })
        .map_err(|error| format!("could not start control listener: {error}"))?;
    Ok(())
}

fn handle_connection(
    stream: TcpStream,
    tls_config: Arc<ServerConfig>,
    identity: Arc<DesktopIdentity>,
    media_auth: Arc<MediaAuthorizer>,
    hub: Arc<ControlHub>,
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(IO_TICK))
        .map_err(|error| format!("could not configure control socket: {error}"))?;
    stream
        .set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|error| format!("could not configure control socket: {error}"))?;
    let connection = ServerConnection::new(tls_config)
        .map_err(|error| format!("could not create TLS control session: {error}"))?;
    let mut reader = BufReader::new(StreamOwned::new(connection, stream));

    let hello_line = read_required_line(&mut reader)?;
    let hello: Hello = serde_json::from_str(&hello_line)
        .map_err(|error| format!("invalid control hello: {error}"))?;
    let phone_name = verify_hello(&hello, &identity)?;
    let media_token = media_auth.publisher_token(&hello.receiver_id)?;
    write_message(
        reader.get_mut(),
        &json!({
            "type": "helloAck",
            "version": 1,
            "receiverId": identity.receiver_id(),
            "mediaToken": media_token,
        }),
    )?;

    let session_id = random_id()?;
    let (outbound_tx, outbound_rx) = mpsc::sync_channel::<Outbound>(64);
    hub.connect(
        session_id.clone(),
        hello.phone_id.clone(),
        phone_name,
        outbound_tx,
    )?;
    let mut pending = HashMap::<String, mpsc::SyncSender<Result<Value, String>>>::new();

    let result = 'connection: loop {
        while let Ok(outbound) = outbound_rx.try_recv() {
            if let Err(error) = write_message(reader.get_mut(), &outbound.message) {
                let _ = outbound.response.send(Err(error.clone()));
                break 'connection Err(error);
            }
            pending.insert(outbound.id, outbound.response);
        }

        let mut line = String::new();
        match (&mut reader)
            .take(MAX_MESSAGE_BYTES + 1)
            .read_line(&mut line)
        {
            Ok(0) => break Ok(()),
            Ok(_) if line.len() as u64 > MAX_MESSAGE_BYTES => {
                break Err("control message is too large".to_owned());
            }
            Ok(_) => {
                let message: Value = serde_json::from_str(&line)
                    .map_err(|error| format!("invalid control message: {error}"))?;
                let message_type = message["type"].as_str().unwrap_or_default();
                match message_type {
                    "heartbeat" => hub.update("heartbeat", Value::Null),
                    "capabilities" | "state" => {
                        hub.update(message_type, message["payload"].clone())
                    }
                    "response" => {
                        if let Some(id) = message["id"].as_str() {
                            if let Some(response) = pending.remove(id) {
                                let result = if message["ok"].as_bool() == Some(true) {
                                    Ok(message["payload"].clone())
                                } else {
                                    Err(message["error"]
                                        .as_str()
                                        .unwrap_or("phone rejected the command")
                                        .to_owned())
                                };
                                let _ = response.send(result);
                            }
                        }
                    }
                    _ => {}
                }
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => break Err(format!("could not read control message: {error}")),
        }
    };

    for (_, response) in pending {
        let _ = response.send(Err("control connection closed".to_owned()));
    }
    hub.disconnect(&session_id);
    result
}

fn verify_hello(hello: &Hello, identity: &DesktopIdentity) -> Result<String, String> {
    if hello.message_type != "hello"
        || hello.version != 1
        || hello.receiver_id != identity.receiver_id()
        || hello.algorithm != "ES256"
    {
        return Err("control hello identity does not match".to_owned());
    }
    let now = crate::pairing::unix_time()?;
    if now.abs_diff(hello.issued_at) > MAX_CLOCK_SKEW_SECONDS {
        return Err("control hello timestamp is outside the allowed window".to_owned());
    }
    let nonce = URL_SAFE_NO_PAD
        .decode(&hello.nonce)
        .map_err(|_| "invalid control nonce".to_owned())?;
    if nonce.len() < 16 {
        return Err("control nonce is too short".to_owned());
    }

    let phone = pairing_server::load_phones()?
        .into_iter()
        .find(|phone| phone.phone_id == hello.phone_id)
        .ok_or_else(|| "phone is not paired".to_owned())?;
    if phone.algorithm != hello.algorithm {
        return Err("phone identity algorithm does not match pairing".to_owned());
    }
    let public_key = URL_SAFE_NO_PAD
        .decode(&phone.public_key)
        .map_err(|_| "stored phone public key is invalid".to_owned())?;
    let digest = Sha256::digest(&public_key);
    if hello.phone_id != URL_SAFE_NO_PAD.encode(&digest[..16]) {
        return Err("phone identity does not match pairing".to_owned());
    }
    let verifying_key = VerifyingKey::from_public_key_der(&public_key)
        .map_err(|_| "stored phone public key is invalid".to_owned())?;
    let signature_bytes = URL_SAFE_NO_PAD
        .decode(&hello.signature)
        .map_err(|_| "invalid control signature".to_owned())?;
    let signature = Signature::from_der(&signature_bytes)
        .map_err(|_| "invalid control signature encoding".to_owned())?;
    verifying_key
        .verify(
            &control_challenge(
                &hello.receiver_id,
                &hello.phone_id,
                &hello.nonce,
                hello.issued_at,
            ),
            &signature,
        )
        .map_err(|_| "phone control signature is invalid".to_owned())?;
    Ok(phone.phone_name)
}

fn control_challenge(receiver_id: &str, phone_id: &str, nonce: &str, issued_at: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    for field in [CONTROL_DOMAIN, receiver_id, phone_id, nonce] {
        let encoded = field.as_bytes();
        bytes.extend_from_slice(&(encoded.len() as u32).to_be_bytes());
        bytes.extend_from_slice(encoded);
    }
    bytes.extend_from_slice(&issued_at.to_be_bytes());
    bytes
}

pub(crate) fn load_tls_config(
    certificate_path: &Path,
    private_key_path: &Path,
) -> Result<ServerConfig, String> {
    let mut certificate_reader = BufReader::new(
        File::open(certificate_path)
            .map_err(|error| format!("could not open control certificate: {error}"))?,
    );
    let certificates = rustls_pemfile::certs(&mut certificate_reader)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| format!("could not read control certificate: {error}"))?;
    let mut key_reader = BufReader::new(
        File::open(private_key_path)
            .map_err(|error| format!("could not open control private key: {error}"))?,
    );
    let private_key = rustls_pemfile::private_key(&mut key_reader)
        .map_err(|error| format!("could not read control private key: {error}"))?
        .ok_or_else(|| "control private key is missing".to_owned())?;
    ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certificates, private_key)
        .map_err(|error| format!("could not configure control TLS: {error}"))
}

fn read_required_line<R: BufRead>(reader: &mut R) -> Result<String, String> {
    let mut line = String::new();
    loop {
        match (&mut *reader)
            .take(MAX_MESSAGE_BYTES + 1)
            .read_line(&mut line)
        {
            Ok(0) => return Err("control connection closed before authentication".to_owned()),
            Ok(_) if line.len() as u64 > MAX_MESSAGE_BYTES => {
                return Err("control hello is too large".to_owned())
            }
            Ok(_) => return Ok(line),
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(format!("could not read control hello: {error}")),
        }
    }
}

fn write_message<W: Write>(writer: &mut W, message: &Value) -> Result<(), String> {
    serde_json::to_writer(&mut *writer, message)
        .map_err(|error| format!("could not encode control message: {error}"))?;
    writer
        .write_all(b"\n")
        .and_then(|_| writer.flush())
        .map_err(|error| format!("could not write control message: {error}"))
}

fn random_id() -> Result<String, String> {
    let mut bytes = [0_u8; 16];
    getrandom::fill(&mut bytes)
        .map_err(|error| format!("could not generate control message id: {error}"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn control_challenge_encoding_is_stable() {
        let digest = Sha256::digest(control_challenge("rid", "pid", "nonce", 1_100));
        let encoded = digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        assert_eq!(
            encoded,
            "b21a5a90d556da636f53e6f7238078d8e9710e240057c7a0783339acc0d56a8d"
        );
    }
}
