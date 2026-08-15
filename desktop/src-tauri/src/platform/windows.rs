use crate::{model::PlatformInfo, platform::VirtualCameraAdapter};

pub struct WindowsAdapter;

impl VirtualCameraAdapter for WindowsAdapter {
    fn info(&self) -> PlatformInfo {
        PlatformInfo {
            operating_system: "Windows",
            adapter_name: "Media Foundation",
            adapter_available: false,
            detail: "Adapter integration is not implemented yet".to_owned(),
        }
    }
}
