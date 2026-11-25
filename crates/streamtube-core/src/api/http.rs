use anyhow::{Context, Result, anyhow};
use parking_lot::Mutex;
use serde_json::Value;
use std::io::Read;
use std::time::Duration;
use ureq::config::{ConfigBuilder, IpFamily};
use ureq::typestate::AgentScope;

pub const BROWSER_UA: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

pub(crate) struct NetworkAgent(Mutex<Option<CachedAgent>>);

struct CachedAgent {
    ipv4_only: bool,
    proxy: String,
    agent: ureq::Agent,
}

impl NetworkAgent {
    pub(crate) const fn new() -> Self {
        Self(Mutex::new(None))
    }

    pub(crate) fn get(
        &self,
        settings: &crate::store::Settings,
        configure: impl FnOnce(ConfigBuilder<AgentScope>) -> ConfigBuilder<AgentScope>,
    ) -> ureq::Agent {
        let mut cached = self.0.lock();
        if let Some(current) = cached.as_ref().filter(|current| {
            current.ipv4_only == settings.ipv4_only && current.proxy == settings.proxy
        }) {
            return current.agent.clone();
        }

        let family = if settings.ipv4_only {
            IpFamily::Ipv4Only
        } else {
            IpFamily::Any
        };
        let mut builder = configure(ureq::Agent::config_builder()).ip_family(family);
        if !settings.proxy.trim().is_empty() {
            if let Ok(proxy) = ureq::Proxy::new(&settings.proxy) {
                builder = builder.proxy(Some(proxy));
            }
        }
        let agent = builder.build().new_agent();
        *cached = Some(CachedAgent {
            ipv4_only: settings.ipv4_only,
            proxy: settings.proxy.clone(),
            agent: agent.clone(),
        });
        agent
    }
}

fn agent() -> ureq::Agent {
    static AGENT: NetworkAgent = NetworkAgent::new();
    AGENT.get(&crate::store::settings_snapshot(), |builder| {
        builder
            .timeout_global(Some(Duration::from_secs(30)))
            .http_status_as_error(false)
            .user_agent(BROWSER_UA)
    })
}

#[cfg(test)]
mod tests {
    use super::{IpFamily, NetworkAgent};
    use crate::store::Settings;

    #[test]
    fn changes_address_family_after_the_first_request() {
        let cache = NetworkAgent::new();
        let mut settings = Settings {
            ipv4_only: true,
            ..Settings::default()
        };
        let initial = cache.get(&settings, |builder| builder);
        assert_eq!(initial.config().ip_family(), IpFamily::Ipv4Only);

        settings.ipv4_only = false;
        let updated = cache.get(&settings, |builder| builder);
        assert_eq!(updated.config().ip_family(), IpFamily::Any);
        assert_eq!(initial.config().ip_family(), IpFamily::Ipv4Only);

        settings.ipv4_only = true;
        let restored = cache.get(&settings, |builder| builder);
        assert_eq!(restored.config().ip_family(), IpFamily::Ipv4Only);
    }
}

pub fn get_bytes(url: &str, headers: &[(&str, &str)]) -> Result<Vec<u8>> {
    let mut req = agent().get(url);
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    let mut resp = req.call().with_context(|| format!("GET {url}"))?;
    let status = resp.status().as_u16();
    let mut buf = Vec::new();
    resp.body_mut()
        .with_config()
        .limit(64 * 1024 * 1024)
        .reader()
        .read_to_end(&mut buf)?;
    if status >= 400 {
        return Err(anyhow!("HTTP {status} for {url}"));
    }
    Ok(buf)
}

pub fn get_text(url: &str) -> Result<String> {
    Ok(String::from_utf8_lossy(&get_bytes(url, &[])?).into_owned())
}

pub fn get_json(url: &str) -> Result<Value> {
    let b = get_bytes(url, &[("Accept", "application/json")])?;
    serde_json::from_slice(&b).context("invalid json")
}

/// Returns (status, json)
pub fn get_json_status(url: &str) -> Result<(u16, Value)> {
    let mut resp = agent().get(url).header("Accept", "application/json").call()?;
    let status = resp.status().as_u16();
    let text = resp.body_mut().read_to_string().unwrap_or_default();
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

pub fn post_json(url: &str, body: &Value, headers: &[(&str, &str)]) -> Result<Value> {
    let mut req = agent().post(url).header("Content-Type", "application/json");
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    let mut resp = req
        .send(serde_json::to_vec(body)?)
        .with_context(|| format!("POST {url}"))?;
    let status = resp.status().as_u16();
    let text = resp
        .body_mut()
        .with_config()
        .limit(64 * 1024 * 1024)
        .read_to_string()?;
    if status >= 400 {
        return Err(anyhow!("HTTP {status}: {}", text.chars().take(300).collect::<String>()));
    }
    serde_json::from_str(&text).context("invalid json")
}

/// POST returning the status code and JSON body even for error statuses.
pub fn post_json_status(url: &str, body: &Value, headers: &[(&str, &str)]) -> Result<(u16, Value)> {
    let mut req = agent().post(url).header("Content-Type", "application/json");
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    let mut resp = req.send(serde_json::to_vec(body)?).with_context(|| format!("POST {url}"))?;
    let status = resp.status().as_u16();
    let text = resp.body_mut().read_to_string().unwrap_or_default();
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

/// Streaming download to a writer with progress callback (downloaded, total).
pub fn download_to(
    url: &str,
    out: &mut dyn std::io::Write,
    mut progress: impl FnMut(u64, Option<u64>) -> bool,
) -> Result<()> {
    let mut resp = agent().get(url).call()?;
    if resp.status().as_u16() >= 400 {
        return Err(anyhow!("HTTP {}", resp.status()));
    }
    let total = resp
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok());
    let mut reader = resp.body_mut().with_config().limit(u64::MAX).reader();
    let mut buf = vec![0u8; 256 * 1024];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        done += n as u64;
        if !progress(done, total) {
            return Err(anyhow!("cancelled"));
        }
    }
    Ok(())
}

pub fn urlencode(s: &str) -> String {
    let mut o = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
            _ => o.push_str(&format!("%{b:02X}")),
        }
    }
    o
}

/// Decodes %XX escapes and '+' (query strings).
pub fn urldecode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                match u8::from_str_radix(std::str::from_utf8(&b[i + 1..i + 3]).unwrap_or("zz"), 16) {
                    Ok(v) => {
                        out.push(v);
                        i += 3;
                        continue;
                    }
                    Err(_) => out.push(b'%'),
                }
            }
            b'+' => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}
