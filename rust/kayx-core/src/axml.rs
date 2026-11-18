//! Android binary XML (AndroidManifest.xml, res/layout/*.xml) -> readable text XML.

const RADIX: [f32; 4] = [0.00390625, 3.0517578E-5, 1.1920929E-7, 4.656613E-10];
const UNITS: [&str; 6] = ["px", "dip", "sp", "pt", "in", "mm"];

pub fn is_axml(b: &[u8]) -> bool {
    b.len() > 8 && b[0] == 3 && b[1] == 0 && b[2] == 8 && b[3] == 0
}

struct R<'a>(&'a [u8]);
impl<'a> R<'a> {
    fn u8(&self, p: usize) -> u32 { self.0.get(p).copied().unwrap_or(0) as u32 }
    fn u16(&self, p: usize) -> u32 { self.u8(p) | (self.u8(p + 1) << 8) }
    fn u32(&self, p: usize) -> u32 { self.u16(p) | (self.u16(p + 2) << 16) }
    fn i32(&self, p: usize) -> i32 { self.u32(p) as i32 }
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn fmt_num(f: f32) -> String {
    if f == f.trunc() && f.abs() < 2.0e9 {
        format!("{}", f as i64)
    } else {
        let s = format!("{:.2}", f);
        s.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

fn read_pool(r: &R, pos: usize) -> Vec<String> {
    let count = r.i32(pos + 8).max(0) as usize;
    if count > r.0.len() / 4 {
        return Vec::new();
    }
    let flags = r.u32(pos + 16);
    let str_start = r.u32(pos + 20) as usize;
    let utf8 = flags & 0x100 != 0;
    let off_base = pos + r.u16(pos + 2) as usize;
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let mut p = pos + str_start + r.u32(off_base + i * 4) as usize;
        let s = if utf8 {
            p += if r.u8(p) & 0x80 != 0 { 2 } else { 1 };
            let mut bl = r.u8(p) as usize;
            p += 1;
            if bl & 0x80 != 0 {
                bl = ((bl & 0x7F) << 8) | r.u8(p) as usize;
                p += 1;
            }
            r.0.get(p..p + bl).map(|b| String::from_utf8_lossy(b).into_owned())
        } else {
            let mut len = r.u16(p) as usize;
            p += 2;
            if len & 0x8000 != 0 {
                len = ((len & 0x7FFF) << 16) | r.u16(p) as usize;
                p += 2;
            }
            if p + len * 2 <= r.0.len() {
                let units: Vec<u16> = (0..len).map(|k| r.u16(p + k * 2) as u16).collect();
                Some(String::from_utf16_lossy(&units))
            } else {
                None
            }
        };
        out.push(s.unwrap_or_default());
    }
    out
}

pub fn decode(data: &[u8]) -> Result<String, String> {
    if !is_axml(data) {
        return Err("Not a binary XML file".into());
    }
    let r = R(data);
    let mut strings: Vec<String> = Vec::new();
    let mut ns: Vec<(String, String)> = Vec::new(); // (uri, prefix)
    let mut pending: Vec<(String, String)> = Vec::new(); // (prefix, uri)
    let mut sb = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    let mut depth = 0usize;
    let mut pos = 8usize;

    macro_rules! st {
        ($i:expr) => {{
            let i: i32 = $i;
            if i < 0 { "" } else { strings.get(i as usize).map(|s| s.as_str()).unwrap_or("") }
        }};
    }

    while pos + 8 <= data.len() {
        let typ = r.u16(pos);
        let size = r.u32(pos + 4) as usize;
        if size == 0 || pos + size > data.len() {
            break;
        }
        match typ {
            0x0001 => strings = read_pool(&r, pos),
            0x0100 => {
                let p = st!(r.i32(pos + 16)).to_string();
                let u = st!(r.i32(pos + 20)).to_string();
                ns.retain(|(k, _)| *k != u);
                ns.push((u.clone(), p.clone()));
                pending.push((p, u));
            }
            0x0101 => {
                let u = st!(r.i32(pos + 20)).to_string();
                ns.retain(|(k, _)| *k != u);
            }
            0x0102 => {
                let qname = |uri: i32, name: i32| -> String {
                    let n = st!(name);
                    let n = if n.is_empty() { format!("attr_0x{:x}", name) } else { n.to_string() };
                    let u = st!(uri);
                    let p = if u.is_empty() { None } else { ns.iter().find(|(k, _)| k == u).map(|(_, v)| v.as_str()) };
                    match p {
                        Some(p) if !p.is_empty() => format!("{}:{}", p, n),
                        _ => n,
                    }
                };
                let ind = "    ".repeat(depth);
                sb.push_str(&ind);
                sb.push('<');
                sb.push_str(&qname(r.i32(pos + 16), r.i32(pos + 20)));
                for (p, u) in pending.drain(..) {
                    sb.push_str(&format!(" xmlns:{}=\"{}\"", p, esc(&u)));
                }
                let attr_start = r.u16(pos + 24) as usize;
                let mut attr_size = r.u16(pos + 26) as usize;
                if attr_size == 0 { attr_size = 20; }
                let count = r.u16(pos + 28) as usize;
                for i in 0..count {
                    let a = pos + 16 + attr_start + i * attr_size;
                    let t = r.u8(a + 15);
                    sb.push('\n');
                    sb.push_str(&ind);
                    sb.push_str("    ");
                    let val = value(&strings, r.i32(a + 8), t, r.i32(a + 16));
                    sb.push_str(&format!("{}=\"{}\"", qname(r.i32(a), r.i32(a + 4)), esc(&val)));
                }
                sb.push_str(">\n");
                depth += 1;
            }
            0x0103 => {
                depth = depth.saturating_sub(1);
                let n = st!(r.i32(pos + 20));
                let n = if n.is_empty() { format!("attr_0x{:x}", r.i32(pos + 20)) } else { n.to_string() };
                let u = st!(r.i32(pos + 16));
                let p = if u.is_empty() { None } else { ns.iter().find(|(k, _)| k == u).map(|(_, v)| v.clone()) };
                sb.push_str(&"    ".repeat(depth));
                match p {
                    Some(p) if !p.is_empty() => sb.push_str(&format!("</{}:{}>\n", p, n)),
                    _ => sb.push_str(&format!("</{}>\n", n)),
                }
            }
            0x0104 => {
                sb.push_str(&"    ".repeat(depth));
                sb.push_str(&esc(st!(r.i32(pos + 16))));
                sb.push('\n');
            }
            _ => {}
        }
        pos += size;
    }
    Ok(sb)
}

fn value(strings: &[String], raw: i32, typ: u32, d: i32) -> String {
    let st = |i: i32| -> String {
        if i < 0 { String::new() } else { strings.get(i as usize).cloned().unwrap_or_default() }
    };
    if raw != -1 {
        return st(raw);
    }
    match typ {
        0x00 => String::new(),
        0x01 => if ((d as u32) >> 24) == 1 { format!("@android:0x{:08x}", d as u32) } else { format!("@0x{:08x}", d as u32) },
        0x02 => format!("?0x{:08x}", d as u32),
        0x03 => st(d),
        0x04 => format!("{:?}", f32::from_bits(d as u32)),
        0x05 => {
            let m = (d >> 8) as f32 * RADIX[((d >> 4) & 3) as usize];
            format!("{}{}", fmt_num(m), UNITS.get((d & 0xF) as usize).copied().unwrap_or(""))
        }
        0x06 => {
            let m = (d >> 8) as f32 * RADIX[((d >> 4) & 3) as usize] * 100.0;
            format!("{}{}", fmt_num(m), if d & 0xF == 1 { "%p" } else { "%" })
        }
        0x10 => d.to_string(),
        0x11 => format!("0x{:x}", d as u32),
        0x12 => (if d != 0 { "true" } else { "false" }).to_string(),
        0x1c..=0x1f => format!("#{:08x}", d as u32),
        _ => format!("0x{:x}", d as u32),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_non_axml() {
        assert!(!is_axml(b"<?xml version"));
        assert!(decode(b"hello world, not axml").is_err());
    }
    #[test]
    fn number_format() {
        assert_eq!(fmt_num(16.0), "16");
        assert_eq!(fmt_num(1.5), "1.5");
        assert_eq!(fmt_num(0.25), "0.25");
    }
}
