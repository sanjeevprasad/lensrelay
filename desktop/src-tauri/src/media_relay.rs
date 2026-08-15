use std::{fs, net::SocketAddr, path::PathBuf, sync::mpsc, thread, time::Duration};

use directories::ProjectDirs;
use moq_relay::{Config, Relay};
use rcgen::{CertificateParams, KeyPair};

pub const MEDIA_PORT: u16 = 53_418;

pub struct MediaRelayInfo {
    pub endpoint: String,
    pub certificate_fingerprint: String,
    pub certificate_path: PathBuf,
    pub private_key_path: PathBuf,
    runtime_private_key: bool,
}

pub fn start(host: &str, receiver_id: &str, auth_key: &PathBuf) -> Result<MediaRelayInfo, String> {
    let host = host.to_owned();
    let scope = format!("lensrelay/{receiver_id}");
    // WebKit's WebSocket fallback is plaintext, so expose it on loopback only.
    // Android uses the certificate-pinned QUIC listener on the selected LAN address.
    let endpoint = format!("http://127.0.0.1:{MEDIA_PORT}/{scope}");
    let (certificate, private_key, runtime_private_key) = prepare_certificate(&host)?;
    let relay_certificate = certificate.clone();
    let relay_private_key = private_key.clone();
    let auth_key = auth_key.clone();
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);

    thread::Builder::new()
        .name("lensrelay-media-relay".to_owned())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = ready_tx.send(Err(format!("could not create media runtime: {error}")));
                    return;
                }
            };

            runtime.block_on(async move {
                let mut config = Config::default();
                config.server.bind = Some(format!("{host}:{MEDIA_PORT}"));
                config.server.tls.cert = vec![relay_certificate];
                config.server.tls.key = vec![relay_private_key];
                config.web.http.listen = Some(SocketAddr::from(([127, 0, 0, 1], MEDIA_PORT)));
                config.web.ws = true;
                config.auth.key = Some(auth_key.to_string_lossy().into_owned());

                match Relay::load(config).await {
                    Ok(relay) => {
                        let fingerprint = relay
                            .server
                            .certificates()
                            .fingerprints()
                            .into_iter()
                            .next()
                            .ok_or_else(|| "media relay has no TLS certificate".to_owned());
                        let _ = ready_tx.send(fingerprint);
                        if let Err(error) = relay.run().await {
                            eprintln!("LensRelay media relay stopped: {error}");
                        }
                    }
                    Err(error) => {
                        let _ = ready_tx.send(Err(format!("could not start media relay: {error}")));
                    }
                }
            });
        })
        .map_err(|error| format!("could not start media relay thread: {error}"))?;

    let certificate_fingerprint = ready_rx
        .recv_timeout(Duration::from_secs(10))
        .map_err(|_| "media relay did not start in time".to_owned())??;
    Ok(MediaRelayInfo {
        endpoint,
        certificate_fingerprint,
        certificate_path: certificate,
        private_key_path: private_key,
        runtime_private_key,
    })
}

pub fn cleanup_runtime_key(info: &MediaRelayInfo) {
    if info.runtime_private_key {
        let _ = fs::remove_file(&info.private_key_path);
    }
}

fn media_directory() -> Result<PathBuf, String> {
    let project = ProjectDirs::from("com", "atanx", "LensRelay")
        .ok_or_else(|| "could not determine the desktop config directory".to_owned())?;
    Ok(project.config_dir().join("media"))
}

fn prepare_certificate(host: &str) -> Result<(PathBuf, PathBuf, bool), String> {
    let directory = media_directory()?;
    let certificate = directory.join("certificate.pem");
    #[cfg(windows)]
    let protected_key = directory.join("private-key.dpapi");
    #[cfg(not(windows))]
    let protected_key = directory.join("private-key.pem");

    if !certificate.is_file() || !protected_key.is_file() {
        let legacy_key = directory.join("private-key.pem");
        if cfg!(windows) && certificate.is_file() && legacy_key.is_file() {
            let key = fs::read(&legacy_key)
                .map_err(|error| format!("could not migrate media private key: {error}"))?;
            crate::secure_storage::write(&protected_key, &key)
                .map_err(|error| format!("could not protect media private key: {error}"))?;
            fs::remove_file(&legacy_key)
                .map_err(|error| format!("could not remove legacy media private key: {error}"))?;
        } else {
            generate_certificate(host, &certificate, &protected_key)?;
        }
    }

    #[cfg(unix)]
    {
        let key = fs::read(&protected_key)
            .map_err(|error| format!("could not read media private key: {error}"))?;
        crate::secure_storage::write(&protected_key, &key)
            .map_err(|error| format!("could not restrict media private key: {error}"))?;
    }

    #[cfg(windows)]
    {
        let runtime = directory.join(".private-key.runtime.pem");
        let key = crate::secure_storage::read(&protected_key)
            .map_err(|error| format!("could not unlock media private key: {error}"))?;
        crate::secure_storage::write_runtime_private(&runtime, &key)
            .map_err(|error| format!("could not prepare media private key: {error}"))?;
        return Ok((certificate, runtime, true));
    }
    #[cfg(not(windows))]
    Ok((certificate, protected_key, false))
}

fn generate_certificate(
    host: &str,
    certificate: &PathBuf,
    private_key: &PathBuf,
) -> Result<(), String> {
    let directory = certificate
        .parent()
        .ok_or_else(|| "media certificate path has no parent".to_owned())?;
    fs::create_dir_all(directory)
        .map_err(|error| format!("could not create media certificate directory: {error}"))?;

    let key =
        KeyPair::generate().map_err(|error| format!("could not create media key: {error}"))?;
    let certificate_pem = CertificateParams::new(vec![host.to_owned()])
        .map_err(|error| format!("could not configure media certificate: {error}"))?
        .self_signed(&key)
        .map_err(|error| format!("could not sign media certificate: {error}"))?
        .pem();
    crate::secure_storage::write_public(certificate, certificate_pem.as_bytes())
        .map_err(|error| format!("could not save media certificate: {error}"))?;
    crate::secure_storage::write(private_key, key.serialize_pem().as_bytes())
        .map_err(|error| format!("could not save media private key: {error}"))?;
    Ok(())
}
