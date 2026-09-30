use std::sync::Arc;

use crate::model::PlatformInfo;

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "windows")]
mod windows;

pub trait VirtualCameraAdapter: Send + Sync {
    fn ensure_ready(&self) -> Result<(), String>;
    fn info(&self) -> PlatformInfo;
    fn set_test_pattern(&self, running: bool) -> Result<PlatformInfo, String>;
    fn push_jpeg_frame(&self, frame: Vec<u8>) -> Result<(), String>;
}

pub fn current() -> Arc<dyn VirtualCameraAdapter> {
    #[cfg(target_os = "linux")]
    return Arc::new(linux::LinuxAdapter::new());

    #[cfg(target_os = "windows")]
    return Arc::new(windows::WindowsAdapter);

    #[allow(unreachable_code)]
    Arc::new(UnsupportedAdapter)
}

struct UnsupportedAdapter;

impl VirtualCameraAdapter for UnsupportedAdapter {
    fn ensure_ready(&self) -> Result<(), String> {
        Ok(())
    }
    fn info(&self) -> PlatformInfo {
        PlatformInfo {
            operating_system: std::env::consts::OS,
            adapter_name: "Unsupported platform",
            adapter_available: false,
            adapter_running: false,
            detail: "LensRelay currently targets Windows and Linux".to_owned(),
        }
    }

    fn set_test_pattern(&self, _running: bool) -> Result<PlatformInfo, String> {
        Err("Virtual cameras are unsupported on this platform".to_owned())
    }

    fn push_jpeg_frame(&self, _frame: Vec<u8>) -> Result<(), String> {
        Err("Virtual cameras are unsupported on this platform".to_owned())
    }
}
