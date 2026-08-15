mod commands;
mod control_server;
mod media_auth;
mod media_relay;
mod model;
mod pairing;
mod pairing_server;
mod platform;
mod secure_storage;

use std::sync::{Arc, Mutex};

use model::{ConnectionState, ReceiverStatus};
use pairing::DesktopIdentity;

pub struct AppState {
    receiver: Arc<Mutex<ReceiverStatus>>,
    pairing_session: Arc<Mutex<Option<model::PairingSession>>>,
    identity: Arc<DesktopIdentity>,
    pairing_host: String,
    pairing_port: u16,
    control_port: u16,
    control: Arc<control_server::ControlHub>,
    media_endpoint: String,
    media_certificate_fingerprint: String,
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
        let identity = Arc::new(DesktopIdentity::load_or_create()?);
        let pairing_host = pairing_server::select_lan_ipv4()?;
        let media_auth = media_auth::MediaAuthorizer::load_or_create()?;
        let receiver_id = identity.receiver_id();
        let media_relay =
            media_relay::start(&pairing_host, &receiver_id, media_auth.public_key_path())?;
        let tls = control_server::load_tls_config(
            &media_relay.certificate_path,
            &media_relay.private_key_path,
        );
        media_relay::cleanup_runtime_key(&media_relay);
        let tls = Arc::new(tls?);
        let (_, pairing_port) = pairing_server::start(
            &pairing_host,
            pairing_session.clone(),
            receiver.clone(),
            identity.clone(),
            tls.clone(),
            media_auth.clone(),
        )?;
        let control = control_server::ControlHub::new();
        control_server::start(
            &pairing_host,
            tls,
            identity.clone(),
            media_auth.clone(),
            control.clone(),
        )?;
        let subscriber_token = media_auth.subscriber_token(&receiver_id)?;
        Ok(Self {
            receiver,
            pairing_session,
            identity,
            pairing_host,
            pairing_port,
            control_port: control_server::CONTROL_PORT,
            control,
            media_endpoint: format!("{}?jwt={subscriber_token}", media_relay.endpoint),
            media_certificate_fingerprint: media_relay.certificate_fingerprint,
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
            commands::get_media_endpoint,
            commands::create_pairing_session,
            commands::report_frontend_error,
            commands::get_paired_devices,
            commands::forget_paired_device,
            commands::get_control_status,
            commands::send_control_command,
        ])
        .run(tauri::generate_context!())
        .expect("failed to run LensRelay desktop");
}
