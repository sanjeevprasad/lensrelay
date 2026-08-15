use serde::Serialize;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverStatus {
    pub connection_state: ConnectionState,
    pub device_name: Option<String>,
    pub listen_address: Option<String>,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum ConnectionState {
    Idle,
    Paired,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformInfo {
    pub operating_system: &'static str,
    pub adapter_name: &'static str,
    pub adapter_available: bool,
    pub detail: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingSession {
    pub payload: String,
    pub qr_svg: String,
    pub receiver_id: String,
    pub receiver_name: String,
    pub fingerprint: String,
    pub confirmation_code: String,
    pub expires_at: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub phone_id: String,
    pub phone_name: String,
    pub paired_at: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receiver_status_uses_frontend_field_names() {
        let status = ReceiverStatus {
            connection_state: ConnectionState::Idle,
            device_name: None,
            listen_address: None,
        };

        let value = serde_json::to_value(status).expect("status should serialize");
        assert_eq!(value["connectionState"], "idle");
        assert!(value.get("deviceName").is_some());
        assert!(value.get("listenAddress").is_some());
    }
}
