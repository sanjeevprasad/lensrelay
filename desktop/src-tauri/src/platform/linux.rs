use std::{fs, path::PathBuf, sync::Mutex, thread, time::Duration};

use gst::prelude::*;
use gstreamer as gst;
use gstreamer_app as gst_app;

use crate::{model::PlatformInfo, platform::VirtualCameraAdapter};

pub struct LinuxAdapter {
    pipeline: Mutex<Option<gst::Pipeline>>,
    appsrc: Mutex<Option<gst_app::AppSrc>>,
}

impl LinuxAdapter {
    pub fn new() -> Self {
        Self {
            pipeline: Mutex::new(None),
            appsrc: Mutex::new(None),
        }
    }

    fn device() -> Option<PathBuf> {
        fs::read_dir("/sys/class/video4linux")
            .ok()?
            .flatten()
            .find_map(|entry| {
                let name = fs::read_to_string(entry.path().join("name")).ok()?;
                if name.trim() != "LensRelay Camera" {
                    return None;
                }
                let device = PathBuf::from("/dev").join(entry.file_name());
                // sysfs can retain a video4linux entry briefly while udev has
                // not created the corresponding device node (notably after a
                // reboot). Do not report the adapter as usable in that state.
                device.exists().then_some(device)
            })
    }

    fn is_running(&self) -> bool {
        self.pipeline
            .lock()
            .ok()
            .and_then(|pipeline| pipeline.as_ref().map(|pipeline| pipeline.current_state()))
            .is_some_and(|state| matches!(state, gst::State::Paused | gst::State::Playing))
    }

    fn start_test_pattern(&self, device: &PathBuf) -> Result<(), String> {
        gst::init().map_err(|error| format!("could not initialize GStreamer: {error}"))?;

        let source = gst::ElementFactory::make("videotestsrc")
            .property("is-live", true)
            .property_from_str("pattern", "smpte")
            .build()
            .map_err(|error| format!("could not create GStreamer test source: {error}"))?;
        let filter = gst::ElementFactory::make("capsfilter")
            .property(
                "caps",
                gst::Caps::builder("video/x-raw")
                    .field("format", "YUY2")
                    .field("width", 1280i32)
                    .field("height", 720i32)
                    .field("framerate", gst::Fraction::new(30, 1))
                    .build(),
            )
            .build()
            .map_err(|error| format!("could not create GStreamer format filter: {error}"))?;
        let convert = gst::ElementFactory::make("videoconvert")
            .build()
            .map_err(|error| format!("could not create GStreamer converter: {error}"))?;
        let sink = gst::ElementFactory::make("v4l2sink")
            .property("device", device.to_string_lossy().as_ref())
            .property("sync", false)
            .build()
            .map_err(|error| format!("could not create GStreamer V4L2 sink: {error}"))?;

        let pipeline = gst::Pipeline::new();
        pipeline
            .add_many([&source, &convert, &filter, &sink])
            .map_err(|error| format!("could not assemble virtual-camera pipeline: {error}"))?;
        gst::Element::link_many([&source, &convert, &filter, &sink])
            .map_err(|error| format!("could not link virtual-camera pipeline: {error}"))?;
        pipeline
            .set_state(gst::State::Playing)
            .map_err(|error| format!("could not start virtual camera: {error}"))?;
        let (_, state, _) = pipeline.state(Some(gst::ClockTime::from_seconds(2)));
        if state != gst::State::Playing {
            let _ = pipeline.set_state(gst::State::Null);
            return Err(format!(
                "virtual camera did not enter the playing state (state: {state:?})"
            ));
        }

        *self
            .pipeline
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())? = Some(pipeline);
        Ok(())
    }

    fn start_jpeg_pipeline(&self, device: &PathBuf) -> Result<(), String> {
        gst::init().map_err(|error| format!("could not initialize GStreamer: {error}"))?;
        self.stop()?;

        let source = gst::ElementFactory::make("appsrc")
            .property("is-live", true)
            .property("format", gst::Format::Time)
            .property("do-timestamp", true)
            .property(
                "caps",
                gst::Caps::builder("image/jpeg")
                    .field("framerate", gst::Fraction::new(30, 1))
                    .build(),
            )
            .build()
            .map_err(|error| format!("could not create GStreamer frame source: {error}"))?;
        let appsrc = source
            .clone()
            .dynamic_cast::<gst_app::AppSrc>()
            .map_err(|_| "GStreamer appsrc has an unexpected type".to_owned())?;
        let jpegdec = gst::ElementFactory::make("jpegdec")
            .build()
            .map_err(|error| format!("could not create GStreamer JPEG decoder: {error}"))?;
        let convert = gst::ElementFactory::make("videoconvert")
            .build()
            .map_err(|error| format!("could not create GStreamer converter: {error}"))?;
        let scale = gst::ElementFactory::make("videoscale")
            .build()
            .map_err(|error| format!("could not create GStreamer scaler: {error}"))?;
        let filter = gst::ElementFactory::make("capsfilter")
            .property(
                "caps",
                gst::Caps::builder("video/x-raw")
                    .field("format", "YUY2")
                    .field("width", 1280i32)
                    .field("height", 720i32)
                    .field("framerate", gst::Fraction::new(30, 1))
                    .build(),
            )
            .build()
            .map_err(|error| format!("could not create GStreamer format filter: {error}"))?;
        let sink = gst::ElementFactory::make("v4l2sink")
            .property("device", device.to_string_lossy().as_ref())
            .property("sync", false)
            .build()
            .map_err(|error| format!("could not create GStreamer V4L2 sink: {error}"))?;

        let pipeline = gst::Pipeline::new();
        pipeline
            .add_many([&source, &jpegdec, &convert, &scale, &filter, &sink])
            .map_err(|error| format!("could not assemble video pipeline: {error}"))?;
        gst::Element::link_many([&source, &jpegdec, &convert, &scale, &filter, &sink])
            .map_err(|error| format!("could not link video pipeline: {error}"))?;
        // v4l2loopback with `exclusive_caps=1` changes from capture to output
        // mode when the previous producer closes. That transition is
        // asynchronous, so allow it to settle before giving up.
        let mut start_error = None;
        for _ in 0..10 {
            match pipeline.set_state(gst::State::Playing) {
                Ok(_) => {
                    start_error = None;
                    break;
                }
                Err(error) => {
                    start_error = Some(error.to_string());
                    let _ = pipeline.set_state(gst::State::Null);
                    thread::sleep(Duration::from_millis(50));
                }
            }
        }
        if let Some(error) = start_error {
            return Err(format!("could not start video pipeline: {error}"));
        }
        let (_, state, _) = pipeline.state(Some(gst::ClockTime::from_seconds(2)));
        if !matches!(state, gst::State::Paused | gst::State::Playing) {
            let _ = pipeline.set_state(gst::State::Null);
            return Err(format!("video pipeline did not start (state: {state:?})"));
        }

        *self
            .appsrc
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())? = Some(appsrc);
        *self
            .pipeline
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())? = Some(pipeline);
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        let _ = self
            .appsrc
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())?
            .take();
        if let Some(pipeline) = self
            .pipeline
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())?
            .take()
        {
            pipeline
                .set_state(gst::State::Null)
                .map_err(|error| format!("could not stop virtual camera: {error}"))?;
        }
        Ok(())
    }

    fn push_jpeg_frame(&self, frame: Vec<u8>) -> Result<(), String> {
        if self
            .appsrc
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())?
            .is_none()
        {
            let device = Self::device().ok_or_else(|| {
                "LensRelay Camera is unavailable; load v4l2loopback first".to_owned()
            })?;
            self.start_jpeg_pipeline(&device)?;
        }
        let appsrc = self
            .appsrc
            .lock()
            .map_err(|_| "virtual-camera state is unavailable".to_owned())?
            .clone()
            .ok_or_else(|| "video pipeline is not ready".to_owned())?;
        appsrc
            .push_buffer(gst::Buffer::from_mut_slice(frame))
            .map(|_| ())
            .map_err(|error| format!("could not publish video frame: {error}"))
    }
}

impl VirtualCameraAdapter for LinuxAdapter {
    fn info(&self) -> PlatformInfo {
        let device = Self::device();
        let running = self.is_running();
        PlatformInfo {
            operating_system: "Linux",
            adapter_name: "v4l2loopback",
            adapter_available: device.is_some(),
            adapter_running: running,
            detail: device.map_or_else(
                || "LensRelay Camera loopback device is not loaded".to_owned(),
                |path| {
                    if running {
                        format!("Publishing video frames to {}", path.display())
                    } else {
                        format!("Ready to publish webcam frames to {}", path.display())
                    }
                },
            ),
        }
    }

    fn set_test_pattern(&self, running: bool) -> Result<PlatformInfo, String> {
        if running {
            if !self.is_running() {
                let device = Self::device().ok_or_else(|| {
                    "LensRelay Camera is unavailable; load the v4l2loopback module first".to_owned()
                })?;
                self.start_test_pattern(&device)?;
            }
        } else {
            self.stop()?;
        }
        Ok(self.info())
    }

    fn push_jpeg_frame(&self, frame: Vec<u8>) -> Result<(), String> {
        Self::push_jpeg_frame(self, frame)
    }
}

impl Drop for LinuxAdapter {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
