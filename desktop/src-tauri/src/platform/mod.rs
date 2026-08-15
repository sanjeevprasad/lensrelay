use crate::model::PlatformInfo;

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "windows")]
mod windows;

pub trait VirtualCameraAdapter {
    fn info(&self) -> PlatformInfo;
}

pub fn current() -> Box<dyn VirtualCameraAdapter> {
    #[cfg(target_os = "linux")]
    return Box::new(linux::LinuxAdapter);

    #[cfg(target_os = "windows")]
    return Box::new(windows::WindowsAdapter);

    #[allow(unreachable_code)]
    Box::new(UnsupportedAdapter)
}

struct UnsupportedAdapter;

impl VirtualCameraAdapter for UnsupportedAdapter {
    fn info(&self) -> PlatformInfo {
        PlatformInfo {
            operating_system: std::env::consts::OS,
            adapter_name: "Unsupported platform",
            adapter_available: false,
            detail: "LensRelay currently targets Windows and Linux",
        }
    }
}
