mod commands;
mod model;
mod pairing;
mod pairing_server;
mod platform;

use std::sync::{Arc, Mutex};

use model::{ConnectionState, ReceiverStatus};
use pairing::DesktopIdentity;

pub struct AppState {
    receiver: Arc<Mutex<ReceiverStatus>>,
    pairing_session: Arc<Mutex<Option<model::PairingSession>>>,
    identity: DesktopIdentity,
    pairing_host: String,
    pairing_port: u16,
}

impl AppState {
    fn new() -> Result<Self, String> {
        let paired_phone = pairing_server::latest_paired_phone_name()
            .map_err(|error| eprintln!("Could not load paired phones: {error}"))
            .ok()
            .flatten();
        let receiver = Arc::new(Mutex::new(ReceiverStatus {
            connection_state: if paired_phone.is_some() {
                ConnectionState::Paired
            } else {
                ConnectionState::Idle
            },
            device_name: paired_phone,
            listen_address: None,
        }));
        let pairing_session = Arc::new(Mutex::new(None));
        let (pairing_host, pairing_port) =
            pairing_server::start(pairing_session.clone(), receiver.clone())?;
        Ok(Self {
            receiver,
            pairing_session,
            identity: DesktopIdentity::load_or_create()?,
            pairing_host,
            pairing_port,
        })
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app_state = AppState::new().expect("failed to initialize LensRelay identity");
    tauri::Builder::default()
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            commands::get_receiver_status,
            commands::get_platform_info,
            commands::create_pairing_session,
        ])
        .run(tauri::generate_context!())
        .expect("failed to run LensRelay desktop");
}
