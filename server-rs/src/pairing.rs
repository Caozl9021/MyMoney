use std::sync::Mutex;
use std::time::{Duration, Instant};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;

use crate::auth::constant_time_eq;

#[derive(Debug)]
struct PairingInner {
    code: Option<String>,
    expires_at: Option<Instant>,
    attempts: usize,
    permanent: bool,
}

#[derive(Debug)]
pub struct PairingStore {
    configured_code: Option<String>,
    default_ttl_seconds: i64,
    inner: Mutex<PairingInner>,
}

impl PairingStore {
    pub fn new(configured_code: Option<String>, default_ttl_seconds: i64) -> Self {
        Self {
            configured_code,
            default_ttl_seconds,
            inner: Mutex::new(PairingInner {
                code: None,
                expires_at: None,
                attempts: 0,
                permanent: false,
            }),
        }
    }

    pub fn issue(&self, ttl_seconds: Option<i64>) -> String {
        let ttl = ttl_seconds.unwrap_or(self.default_ttl_seconds);
        let code = if let Some(ref fixed) = self.configured_code {
            fixed.clone()
        } else {
            let mut buf = [0u8; 6];
            rand::thread_rng().fill_bytes(&mut buf);
            let raw = URL_SAFE_NO_PAD.encode(buf).replace(['-', '_'], "");
            raw.chars().take(8).collect::<String>().to_uppercase()
        };

        let mut guard = self.inner.lock().expect("pairing lock poisoned");
        guard.code = Some(code.clone());
        guard.permanent = ttl <= 0;
        guard.expires_at = if guard.permanent {
            None
        } else {
            Some(Instant::now() + Duration::from_secs(ttl as u64))
        };
        guard.attempts = 0;
        code
    }

    pub fn verify(&self, candidate: &str) -> bool {
        let normalized = candidate.trim().to_uppercase();
        let mut guard = self.inner.lock().expect("pairing lock poisoned");
        let Some(current) = guard.code.clone() else {
            return false;
        };
        if guard.permanent {
            return constant_time_eq(&current, &normalized);
        }
        if let Some(exp) = guard.expires_at {
            if Instant::now() >= exp {
                guard.code = None;
                return false;
            }
        }
        guard.attempts += 1;
        if guard.attempts > 10 {
            guard.code = None;
            return false;
        }
        if !constant_time_eq(&current, &normalized) {
            return false;
        }
        guard.code = None;
        true
    }
}
