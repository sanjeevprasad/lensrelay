use serde_json::Value;
use tauri::State;

use crate::{
    control_server::ControlStatus,
    model::{PairedDevice, PairingSession, PlatformInfo, ReceiverStatus},
    pairing_server, platform, AppState,
};

#[tauri::command]
pub fn get_receiver_status(state: State<'_, AppState>) -> Result<ReceiverStatus, String> {
    state
        .receiver
        .lock()
        .map(|status| status.clone())
        .map_err(|_| "receiver state is unavailable".to_owned())
}

#[tauri::command]
pub async fn get_paired_devices() -> Result<Vec<PairedDevice>, String> {
    tokio::task::spawn_blocking(pairing_server::paired_devices)
        .await
        .map_err(|error| format!("paired-device task failed: {error}"))?
}

#[tauri::command]
pub async fn forget_paired_device(
    state: State<'_, AppState>,
    phone_id: String,
) -> Result<(), String> {
    let control = state.control.clone();
    let receiver = state.receiver.clone();
    tokio::task::spawn_blocking(move || {
        let connected_phone = control.status()?.phone_id;
        if connected_phone.as_deref() == Some(phone_id.as_str()) {
            if let Err(error) = control.send_command("unpair", serde_json::json!({})) {
                eprintln!(
                    "LensRelay: phone did not confirm mutual unpair; removing locally: {error}"
                );
            }
        }
        pairing_server::forget_phone(&phone_id)?;
        let latest = pairing_server::latest_paired_phone_name()?;
        let mut status = receiver
            .lock()
            .map_err(|_| "receiver state is unavailable".to_owned())?;
        status.connection_state = if latest.is_some() {
            crate::model::ConnectionState::Paired
        } else {
            crate::model::ConnectionState::Idle
        };
        status.device_name = latest;
        Ok(())
    })
    .await
    .map_err(|error| format!("forget-device task failed: {error}"))?
}

#[tauri::command]
pub fn report_frontend_error(message: String) {
    eprintln!("LensRelay WebView error: {message}");
    #[cfg(debug_assertions)]
    if let Err(error) = std::fs::write("/tmp/lensrelay-last-webview-error.txt", &message) {
        eprintln!("LensRelay: could not save WebView diagnostic: {error}");
    }
}

#[tauri::command]
pub fn get_platform_info() -> PlatformInfo {
    platform::current().info()
}

#[tauri::command]
pub async fn create_pairing_session(state: State<'_, AppState>) -> Result<PairingSession, String> {
    let identity = state.identity.clone();
    let pairing_host = state.pairing_host.clone();
    let pairing_port = state.pairing_port;
    let control_port = state.control_port;
    let certificate_fingerprint = state.media_certificate_fingerprint.clone();
    let pairing_session = state.pairing_session.clone();
    tokio::task::spawn_blocking(move || {
        let session = identity.create_pairing_session(
            &pairing_host,
            pairing_port,
            control_port,
            &certificate_fingerprint,
        )?;
        let mut active_session = pairing_session
            .lock()
            .map_err(|_| "pairing session state is unavailable".to_owned())?;
        *active_session = Some(session.clone());
        Ok(session)
    })
    .await
    .map_err(|error| format!("pairing-session task failed: {error}"))?
}

#[tauri::command]
pub fn get_media_endpoint(state: State<'_, AppState>) -> String {
    state.media_endpoint.clone()
}

#[tauri::command]
pub fn get_control_status(state: State<'_, AppState>) -> Result<ControlStatus, String> {
    state.control.status()
}

#[tauri::command]
pub async fn send_control_command(
    state: State<'_, AppState>,
    command: String,
    parameters: Value,
) -> Result<Value, String> {
    let control = state.control.clone();
    tokio::task::spawn_blocking(move || control.send_command(&command, parameters))
        .await
        .map_err(|error| format!("control command task failed: {error}"))?
}
