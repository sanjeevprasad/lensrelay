use std::{
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime},
};

use directories::ProjectDirs;
use moq_token::{Algorithm, Claims, Key, KeyId};

use crate::secure_storage;

const TOKEN_LIFETIME: Duration = Duration::from_secs(24 * 60 * 60);

pub struct MediaAuthorizer {
    signing_key: Key,
    public_key_path: PathBuf,
}

impl MediaAuthorizer {
    pub fn load_or_create() -> Result<Arc<Self>, String> {
        let directory = config_directory()?.join("media");
        let private_path = directory.join("authorization-key.jwk");
        let public_key_path = directory.join("authorization-public.jwk");
        let signing_key = match secure_storage::read(&private_path) {
            Ok(bytes) => Key::from_str(
                std::str::from_utf8(&bytes)
                    .map_err(|_| "media authorization key is not UTF-8".to_owned())?,
            )
            .map_err(|error| format!("could not parse media authorization key: {error}"))?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                let key =
                    Key::generate(Algorithm::ES256, Some(KeyId::random())).map_err(|error| {
                        format!("could not generate media authorization key: {error}")
                    })?;
                secure_storage::write(
                    &private_path,
                    key.to_str()
                        .map_err(|error| {
                            format!("could not encode media authorization key: {error}")
                        })?
                        .as_bytes(),
                )
                .map_err(|error| format!("could not protect media authorization key: {error}"))?;
                key
            }
            Err(error) => return Err(format!("could not read media authorization key: {error}")),
        };
        let public = signing_key
            .to_public()
            .and_then(|key| key.to_str())
            .map_err(|error| format!("could not encode media verification key: {error}"))?;
        secure_storage::write_public(&public_key_path, public.as_bytes())
            .map_err(|error| format!("could not save media verification key: {error}"))?;
        Ok(Arc::new(Self {
            signing_key,
            public_key_path,
        }))
    }

    pub fn public_key_path(&self) -> &PathBuf {
        &self.public_key_path
    }

    pub fn publisher_token(&self, receiver_id: &str) -> Result<String, String> {
        self.token(receiver_id, true)
    }

    pub fn subscriber_token(&self, receiver_id: &str) -> Result<String, String> {
        self.token(receiver_id, false)
    }

    fn token(&self, receiver_id: &str, publish: bool) -> Result<String, String> {
        let root = format!("lensrelay/{receiver_id}");
        let claims = if publish {
            Claims::default().with_root(root).with_publish([""])
        } else {
            Claims::default().with_root(root).with_subscribe([""])
        }
        .with_issued(SystemTime::now())
        .with_expires(SystemTime::now() + TOKEN_LIFETIME);
        self.signing_key
            .sign(&claims)
            .map_err(|error| format!("could not issue media authorization: {error}"))
    }
}

fn config_directory() -> Result<PathBuf, String> {
    ProjectDirs::from("com", "atanx", "LensRelay")
        .map(|project| project.config_dir().to_owned())
        .ok_or_else(|| "could not determine the desktop config directory".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn publisher_and_subscriber_tokens_are_role_scoped() {
        let signing_key = Key::generate(Algorithm::ES256, Some(KeyId::random())).unwrap();
        let authorizer = MediaAuthorizer {
            signing_key: signing_key.clone(),
            public_key_path: PathBuf::new(),
        };

        let publisher = signing_key
            .verify(&authorizer.publisher_token("receiver").unwrap())
            .unwrap();
        assert_eq!(publisher.root, "lensrelay/receiver");
        assert_eq!(publisher.publish, vec![""]);
        assert!(publisher.subscribe.is_empty());

        let subscriber = signing_key
            .verify(&authorizer.subscriber_token("receiver").unwrap())
            .unwrap();
        assert_eq!(subscriber.root, "lensrelay/receiver");
        assert_eq!(subscriber.subscribe, vec![""]);
        assert!(subscriber.publish.is_empty());
    }
}
