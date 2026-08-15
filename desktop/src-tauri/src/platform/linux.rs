use crate::{model::PlatformInfo, platform::VirtualCameraAdapter};

pub struct LinuxAdapter;

impl VirtualCameraAdapter for LinuxAdapter {
    fn info(&self) -> PlatformInfo {
        PlatformInfo {
            operating_system: "Linux",
            adapter_name: "v4l2loopback",
            adapter_available: false,
            detail: "Adapter integration is not implemented yet",
        }
    }
}
