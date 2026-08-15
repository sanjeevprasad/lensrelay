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
pub fn get_paired_devices() -> Result<Vec<PairedDevice>, String> {
    pairing_server::paired_devices()
}

#[tauri::command]
pub fn forget_paired_device(state: State<'_, AppState>, phone_id: String) -> Result<(), String> {
    let connected_phone = state.control.status()?.phone_id;
    if connected_phone.as_deref() == Some(phone_id.as_str()) {
        if let Err(error) = state.control.send_command("unpair", serde_json::json!({})) {
            eprintln!("LensRelay: phone did not confirm mutual unpair; removing locally: {error}");
        }
    }
    pairing_server::forget_phone(&phone_id)?;
    let latest = pairing_server::latest_paired_phone_name()?;
    let mut status = state
        .receiver
        .lock()
        .map_err(|_| "receiver state is unavailable".to_owned())?;
    status.connection_state = if latest.is_some() {
        crate::model::ConnectionState::Paired
    } else {
        crate::model::ConnectionState::Idle
    };
    status.device_name = latest;
    Ok(())
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
pub fn create_pairing_session(state: State<'_, AppState>) -> Result<PairingSession, String> {
    let session = state.identity.create_pairing_session(
        &state.pairing_host,
        state.pairing_port,
        state.control_port,
        &state.media_certificate_fingerprint,
    )?;
    let mut active_session = state
        .pairing_session
        .lock()
        .map_err(|_| "pairing session state is unavailable".to_owned())?;
    *active_session = Some(session.clone());
    Ok(session)
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
pub fn send_control_command(
    state: State<'_, AppState>,
    command: String,
    parameters: Value,
) -> Result<Value, String> {
    state.control.send_command(&command, parameters)
}
