use std::{
    fs,
    net::{IpAddr, SocketAddr},
    path::PathBuf,
    sync::mpsc,
    thread,
    time::Duration,
};

use directories::ProjectDirs;
use moq_relay::{Config, PublicConfig, PublicDetailed, Relay};
use rcgen::{CertificateParams, KeyPair};

pub const MEDIA_PORT: u16 = 53_418;

pub struct MediaRelayInfo {
    pub endpoint: String,
    pub certificate_fingerprint: String,
    pub certificate_path: PathBuf,
    pub private_key_path: PathBuf,
}

pub fn start(host: &str, receiver_id: &str) -> Result<MediaRelayInfo, String> {
    let host = host.to_owned();
    let scope = format!("lensrelay/{receiver_id}");
    let endpoint = format!("http://{}:{MEDIA_PORT}/{scope}", url_host(&host));
    let (certificate, private_key) = certificate_paths()?;
    ensure_certificate(&host, &certificate, &private_key)?;
    let relay_certificate = certificate.clone();
    let relay_private_key = private_key.clone();
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
                config.server.bind = Some(format!("0.0.0.0:{MEDIA_PORT}"));
                config.server.tls.cert = vec![relay_certificate];
                config.server.tls.key = vec![relay_private_key];
                config.web.http.listen = Some(SocketAddr::from(([0, 0, 0, 0], MEDIA_PORT)));
                config.web.ws = true;
                config.auth.public = Some(PublicConfig::Detailed(PublicDetailed {
                    subscribe: vec![scope.clone()],
                    publish: vec![scope],
                    api: None,
                }));

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
    })
}

fn certificate_paths() -> Result<(PathBuf, PathBuf), String> {
    let project = ProjectDirs::from("com", "atanx", "LensRelay")
        .ok_or_else(|| "could not determine the desktop config directory".to_owned())?;
    let directory = project.config_dir().join("media");
    Ok((
        directory.join("certificate.pem"),
        directory.join("private-key.pem"),
    ))
}

fn ensure_certificate(
    host: &str,
    certificate: &PathBuf,
    private_key: &PathBuf,
) -> Result<(), String> {
    if certificate.is_file() && private_key.is_file() {
        return Ok(());
    }
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
    fs::write(certificate, certificate_pem)
        .map_err(|error| format!("could not save media certificate: {error}"))?;
    fs::write(private_key, key.serialize_pem())
        .map_err(|error| format!("could not save media private key: {error}"))?;
    Ok(())
}

fn url_host(host: &str) -> String {
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V6(_)) => format!("[{host}]"),
        _ => host.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_ipv6_for_urls() {
        assert_eq!(url_host("192.168.1.4"), "192.168.1.4");
        assert_eq!(url_host("fe80::1"), "[fe80::1]");
    }
}
