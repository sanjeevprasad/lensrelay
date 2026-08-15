use tauri::State;

use crate::{
    model::{PairingSession, PlatformInfo, ReceiverStatus},
    platform, AppState,
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
pub fn get_platform_info() -> PlatformInfo {
    platform::current().info()
}

#[tauri::command]
pub fn create_pairing_session(state: State<'_, AppState>) -> Result<PairingSession, String> {
    let session = state
        .identity
        .create_pairing_session(&state.pairing_host, state.pairing_port)?;
    let mut active_session = state
        .pairing_session
        .lock()
        .map_err(|_| "pairing session state is unavailable".to_owned())?;
    *active_session = Some(session.clone());
    Ok(session)
}
