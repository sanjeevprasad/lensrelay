use std::{
    fs::{self, OpenOptions},
    io::{self, Write},
    path::Path,
};

#[cfg(windows)]
const WINDOWS_HEADER: &[u8] = b"LRDPAPI1";

pub fn read(path: &Path) -> io::Result<Vec<u8>> {
    let bytes = fs::read(path)?;
    #[cfg(windows)]
    {
        if let Some(protected) = bytes.strip_prefix(WINDOWS_HEADER) {
            return windows::unprotect(protected);
        }
        // Migrate legacy plaintext on the next successful read.
        write(path, &bytes)?;
    }
    Ok(bytes)
}

pub fn write(path: &Path, value: &[u8]) -> io::Result<()> {
    #[cfg(windows)]
    let encoded = {
        let mut encoded = WINDOWS_HEADER.to_vec();
        encoded.extend_from_slice(&windows::protect(value)?);
        encoded
    };
    #[cfg(not(windows))]
    let encoded = value.to_vec();
    atomic_private_write(path, &encoded)
}

pub fn write_public(path: &Path, value: &[u8]) -> io::Result<()> {
    atomic_write(path, value, false)
}

#[cfg(windows)]
pub fn write_runtime_private(path: &Path, value: &[u8]) -> io::Result<()> {
    atomic_private_write(path, value)
}

fn atomic_private_write(path: &Path, value: &[u8]) -> io::Result<()> {
    atomic_write(path, value, true)
}

fn atomic_write(path: &Path, value: &[u8], private: bool) -> io::Result<()> {
    #[cfg(windows)]
    let _ = private;
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "path has no parent"))?;
    fs::create_dir_all(parent)?;
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("lensrelay");
    let mut nonce = [0_u8; 8];
    getrandom::fill(&mut nonce).map_err(io::Error::other)?;
    let temporary = parent.join(format!(
        ".{file_name}.{}.tmp",
        nonce
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    ));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    if private {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(&temporary)?;
        file.write_all(value)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)?;
        #[cfg(unix)]
        if private {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

#[cfg(windows)]
mod windows {
    use super::*;
    use std::{ptr, slice};
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };

    pub fn protect(value: &[u8]) -> io::Result<Vec<u8>> {
        crypt(value, true)
    }

    pub fn unprotect(value: &[u8]) -> io::Result<Vec<u8>> {
        crypt(value, false)
    }

    fn crypt(value: &[u8], protect: bool) -> io::Result<Vec<u8>> {
        let input = CRYPT_INTEGER_BLOB {
            cbData: value
                .len()
                .try_into()
                .map_err(|_| io::Error::other("secret is too large"))?,
            pbData: value.as_ptr() as *mut u8,
        };
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: ptr::null_mut(),
        };
        let ok = unsafe {
            if protect {
                CryptProtectData(
                    &input,
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output,
                )
            } else {
                CryptUnprotectData(
                    &input,
                    ptr::null_mut(),
                    ptr::null(),
                    ptr::null(),
                    ptr::null(),
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut output,
                )
            }
        };
        if ok == 0 {
            return Err(io::Error::last_os_error());
        }
        let result =
            unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
        unsafe { LocalFree(output.pbData.cast()) };
        Ok(result)
    }
}
