use crate::{model::PlatformInfo, platform::VirtualCameraAdapter};
use std::fs;

pub struct LinuxAdapter;

impl VirtualCameraAdapter for LinuxAdapter {
    fn info(&self) -> PlatformInfo {
        let device = fs::read_dir("/sys/class/video4linux")
            .ok()
            .and_then(|entries| {
                entries.flatten().find_map(|entry| {
                    let name = fs::read_to_string(entry.path().join("name")).ok()?;
                    (name.trim() == "LensRelay Camera")
                        .then(|| format!("/dev/{}", entry.file_name().to_string_lossy()))
                })
            });
        PlatformInfo {
            operating_system: "Linux",
            adapter_name: "v4l2loopback",
            adapter_available: device.is_some(),
            detail: device.map_or_else(
                || "LensRelay Camera loopback device is not loaded".to_owned(),
                |path| format!("Publishing webcam frames to {path}"),
            ),
        }
    }
}
