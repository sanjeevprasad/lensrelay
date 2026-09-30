use crate::{model::PlatformInfo, platform::VirtualCameraAdapter};

pub struct WindowsAdapter;

impl VirtualCameraAdapter for WindowsAdapter {
    fn ensure_ready(&self) -> Result<(), String> {
        Ok(())
    }
    fn info(&self) -> PlatformInfo {
        PlatformInfo {
            operating_system: "Windows",
            adapter_name: "Media Foundation",
            adapter_available: false,
            adapter_running: false,
            detail: "Adapter integration is not implemented yet".to_owned(),
        }
    }

    fn set_test_pattern(&self, _running: bool) -> Result<PlatformInfo, String> {
        Err("The Windows virtual-camera adapter is not implemented yet".to_owned())
    }

    fn push_jpeg_frame(&self, _frame: Vec<u8>) -> Result<(), String> {
        Err("The Windows virtual-camera adapter is not implemented yet".to_owned())
    }
}
