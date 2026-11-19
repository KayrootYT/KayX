//! Bounded Android binary XML decoder.

const RADIX: [f32; 4] = [0.00390625, 3.0517578E-5, 1.1920929E-7, 4.656613E-10];
const UNITS: [&str; 6] = ["px", "dip", "sp", "pt", "in", "mm"];
const MAX_INPUT: usize = 64 * 1024 * 1024;
const MAX_OUTPUT: usize = 64 * 1024 * 1024;
const MAX_STRINGS: usize = 200_000;
const MAX_STRING_BYTES: usize = 1024 * 1024;
const MAX_POOL_BYTES: usize = 16 * 1024 * 1024;
const MAX_NAMESPACES: usize = 4096;
const MAX_DEPTH: usize = 256;

pub fn is_axml(bytes: &[u8]) -> bool {
    bytes.len() >= 8 && bytes[0] == 3 && bytes[1] == 0 && bytes[2] == 8 && bytes[3] == 0
}

struct R<'a>(&'a [u8]);
impl<'a> R<'a> {
    fn u8(&self, pos: usize) -> u32 { self.0.get(pos).copied().unwrap_or(0) as u32 }
    fn u16(&self, pos: usize) -> u32 { self.u8(pos) | (self.u8(pos.saturating_add(1)) << 8) }
    fn u32(&self, pos: usize) -> u32 { self.u16(pos) | (self.u16(pos.saturating_add(2)) << 16) }
    fn i32(&self, pos: usize) -> i32 { self.u32(pos) as i32 }
}

fn esc(value: &str) -> String {
    value.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

fn fmt_num(value: f32) -> String {
    if value == value.trunc() && value.abs() < 2.0e9 {
        format!("{}", value as i64)
    } else {
        format!("{:.2}", value).trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

fn checked_range(start: usize, count: usize, width: usize, end: usize) -> Option<std::ops::Range<usize>> {
    let bytes = count.checked_mul(width)?;
    let finish = start.checked_add(bytes)?;
    (finish <= end).then_some(start..finish)
}

fn push_out(output: &mut String, value: &str) -> Result<(), String> {
    let new_len = output.len().checked_add(value.len()).ok_or("Decoded XML size overflow")?;
    if new_len > MAX_OUTPUT { return Err("Decoded XML exceeds 64 MiB output limit".into()); }
    output.push_str(value);
    Ok(())
}

fn push_char(output: &mut String, value: char) -> Result<(), String> {
    if output.len().checked_add(value.len_utf8()).filter(|n| *n <= MAX_OUTPUT).is_none() {
        return Err("Decoded XML exceeds 64 MiB output limit".into());
    }
    output.push(value);
    Ok(())
}

fn len8(bytes: &[u8], pos: &mut usize, end: usize) -> Option<usize> {
    if *pos >= end { return None; }
    let first = *bytes.get(*pos)?;
    *pos = pos.checked_add(1)?;
    if first & 0x80 == 0 { return Some(first as usize); }
    if *pos >= end { return None; }
    let second = *bytes.get(*pos)?;
    *pos = pos.checked_add(1)?;
    if *pos > end { return None; }
    Some((((first & 0x7f) as usize) << 8) | second as usize)
}

fn len16(reader: &R, pos: &mut usize, end: usize) -> Option<usize> {
    if pos.checked_add(2)? > end { return None; }
    let first = reader.u16(*pos) as usize;
    *pos += 2;
    if first & 0x8000 == 0 { return Some(first); }
    if pos.checked_add(2)? > end { return None; }
    let second = reader.u16(*pos) as usize;
    *pos += 2;
    Some(((first & 0x7fff) << 16) | second)
}

fn read_pool(reader: &R, pos: usize, end: usize) -> Result<Vec<String>, String> {
    if pos.checked_add(28).filter(|v| *v <= end).is_none() {
        return Err("Truncated string-pool header".into());
    }
    let header_size = reader.u16(pos + 2) as usize;
    if header_size < 28 || pos.checked_add(header_size).filter(|v| *v <= end).is_none() {
        return Err("Invalid string-pool header size".into());
    }
    let count = reader.u32(pos + 8) as usize;
    if count > MAX_STRINGS { return Err("String pool exceeds item limit".into()); }
    let offsets_start = pos.checked_add(header_size).ok_or("String-pool offset overflow")?;
    let offsets_end = checked_range(offsets_start, count, 4, end)
        .ok_or("String-pool offsets exceed chunk")?.end;
    let strings_start = pos.checked_add(reader.u32(pos + 20) as usize).ok_or("String data offset overflow")?;
    if strings_start > end || offsets_end > end || strings_start < offsets_end {
        return Err("Invalid string-pool bounds".into());
    }
    let utf8 = reader.u32(pos + 16) & 0x100 != 0;
    let mut strings = Vec::with_capacity(count);
    let mut total_string_bytes = 0usize;

    for index in 0..count {
        let offset_pos = offsets_start.checked_add(index.checked_mul(4).ok_or("Offset overflow")?)
            .ok_or("Offset overflow")?;
        let mut cursor = strings_start.checked_add(reader.u32(offset_pos) as usize)
            .ok_or("String offset overflow")?;
        if cursor >= end { return Err("String offset outside chunk".into()); }
        let decoded = if utf8 {
            let _utf16_len = len8(reader.0, &mut cursor, end).ok_or("Invalid UTF-8 string length")?;
            let byte_len = len8(reader.0, &mut cursor, end).ok_or("Invalid UTF-8 byte length")?;
            if byte_len > MAX_STRING_BYTES { return Err("UTF-8 string exceeds per-string limit".into()); }
            let finish = cursor.checked_add(byte_len).ok_or("String length overflow")?;
            let data = reader.0.get(cursor..finish).filter(|_| finish <= end)
                .ok_or("UTF-8 string exceeds chunk")?;
            String::from_utf8_lossy(data).into_owned()
        } else {
            let unit_len = len16(reader, &mut cursor, end).ok_or("Invalid UTF-16 string length")?;
            let byte_len = unit_len.checked_mul(2).ok_or("UTF-16 length overflow")?;
            if byte_len > MAX_STRING_BYTES { return Err("UTF-16 string exceeds per-string limit".into()); }
            let finish = cursor.checked_add(byte_len).ok_or("UTF-16 offset overflow")?;
            if finish > end { return Err("UTF-16 string exceeds chunk".into()); }
            let units: Vec<u16> = (0..unit_len)
                .map(|i| reader.u16(cursor + i * 2) as u16).collect();
            String::from_utf16_lossy(&units)
        };
        total_string_bytes = total_string_bytes.checked_add(decoded.len()).ok_or("String-pool size overflow")?;
        if total_string_bytes > MAX_POOL_BYTES { return Err("Decoded string pool exceeds 16 MiB".into()); }
        strings.push(decoded);
    }
    Ok(strings)
}

pub fn decode(data: &[u8]) -> Result<String, String> {
    if !is_axml(data) { return Err("Not a binary XML file".into()); }
    if data.len() > MAX_INPUT { return Err("Binary XML exceeds 64 MiB input limit".into()); }
    let reader = R(data);
    let declared_size = reader.u32(4) as usize;
    if declared_size < 8 || declared_size > data.len() { return Err("Invalid binary XML file size".into()); }

    let mut strings = Vec::<String>::new();
    let mut namespaces: Vec<(String, String)> = Vec::new();
    let mut pending: Vec<(String, String)> = Vec::new();
    let mut output = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    let mut depth = 0usize;
    let mut pos = 8usize;

    macro_rules! st {
        ($index:expr) => {{
            let index: i32 = $index;
            if index < 0 { "" } else { strings.get(index as usize).map(|s| s.as_str()).unwrap_or("") }
        }};
    }

    while pos < declared_size {
        if pos.checked_add(8).filter(|end| *end <= declared_size).is_none() {
            return Err("Truncated binary XML chunk header".into());
        }
        let kind = reader.u16(pos);
        let chunk_size = reader.u32(pos + 4) as usize;
        if chunk_size < 8 { return Err("Invalid binary XML chunk size".into()); }
        let end = pos.checked_add(chunk_size).filter(|end| *end <= declared_size)
            .ok_or("Binary XML chunk exceeds declared file size")?;

        match kind {
            0x0001 => strings = read_pool(&reader, pos, end)?,
            0x0100 => {
                if chunk_size < 24 { return Err("Truncated namespace chunk".into()); }
                if pending.len() >= MAX_NAMESPACES { return Err("Too many pending XML namespaces".into()); }
                let prefix = st!(reader.i32(pos + 16)).to_string();
                let uri = st!(reader.i32(pos + 20)).to_string();
                namespaces.retain(|(key, _)| *key != uri);
                if namespaces.len() >= MAX_NAMESPACES { return Err("Too many XML namespaces".into()); }
                namespaces.push((uri.clone(), prefix.clone()));
                pending.push((prefix, uri));
            }
            0x0101 => {
                if chunk_size < 24 { return Err("Truncated namespace-end chunk".into()); }
                let uri = st!(reader.i32(pos + 20)).to_string();
                namespaces.retain(|(key, _)| *key != uri);
            }
            0x0102 => {
                if chunk_size < 36 { return Err("Truncated start-element chunk".into()); }
                if depth >= MAX_DEPTH { return Err("Binary XML nesting exceeds 256 levels".into()); }
                let qualified_name = |uri_index: i32, name_index: i32| -> String {
                    let name = st!(name_index);
                    let name = if name.is_empty() { format!("attr_0x{:x}", name_index) } else { name.to_string() };
                    let uri = st!(uri_index);
                    match if uri.is_empty() { None } else { namespaces.iter().find(|(key, _)| key == uri).map(|(_, prefix)| prefix.as_str()) } {
                        Some(prefix) if !prefix.is_empty() => format!("{}:{}", prefix, name),
                        _ => name,
                    }
                };
                let indent = "    ".repeat(depth);
                push_out(&mut output, &indent)?;
                push_char(&mut output, '<')?;
                push_out(&mut output, &qualified_name(reader.i32(pos + 16), reader.i32(pos + 20)))?;
                for (prefix, uri) in pending.drain(..) {
                    push_out(&mut output, &format!(" xmlns:{}=\"{}\"", prefix, esc(&uri)))?;
                }
                let attr_start = reader.u16(pos + 24) as usize;
                let attr_size = (reader.u16(pos + 26) as usize).max(20);
                let attr_count = reader.u16(pos + 28) as usize;
                let attrs = pos.checked_add(16).and_then(|v| v.checked_add(attr_start))
                    .ok_or("Attribute table offset overflow")?;
                checked_range(attrs, attr_count, attr_size, end).ok_or("Attribute table exceeds chunk")?;
                for i in 0..attr_count {
                    let attr = attrs.checked_add(i.checked_mul(attr_size).ok_or("Attribute offset overflow")?)
                        .ok_or("Attribute offset overflow")?;
                    let value = value(&strings, reader.i32(attr + 8), reader.u8(attr + 15), reader.i32(attr + 16));
                    push_char(&mut output, '\n')?;
                    push_out(&mut output, &indent)?;
                    push_out(&mut output, "    ")?;
                    push_out(&mut output, &format!("{}=\"{}\"", qualified_name(reader.i32(attr), reader.i32(attr + 4)), esc(&value)))?;
                }
                push_out(&mut output, ">\n")?;
                depth += 1;
            }
            0x0103 => {
                if chunk_size < 24 { return Err("Truncated end-element chunk".into()); }
                if depth == 0 { return Err("Unbalanced end-element chunk".into()); }
                depth -= 1;
                let name_index = reader.i32(pos + 20);
                let name = st!(name_index);
                let name = if name.is_empty() { format!("attr_0x{:x}", name_index) } else { name.to_string() };
                let uri = st!(reader.i32(pos + 16));
                let prefix = if uri.is_empty() { None } else { namespaces.iter().find(|(key, _)| key == uri).map(|(_, prefix)| prefix.clone()) };
                push_out(&mut output, &"    ".repeat(depth))?;
                match prefix {
                    Some(prefix) if !prefix.is_empty() => push_out(&mut output, &format!("</{}:{}>\n", prefix, name))?,
                    _ => push_out(&mut output, &format!("</{}>\n", name))?,
                }
            }
            0x0104 => {
                if chunk_size < 28 { return Err("Truncated CDATA chunk".into()); }
                push_out(&mut output, &"    ".repeat(depth))?;
                push_out(&mut output, &esc(st!(reader.i32(pos + 16))))?;
                push_char(&mut output, '\n')?;
            }
            _ => {}
        }
        pos = end;
    }
    Ok(output)
}

fn value(strings: &[String], raw: i32, kind: u32, data: i32) -> String {
    let string_at = |index: i32| -> String {
        if index < 0 { String::new() } else { strings.get(index as usize).cloned().unwrap_or_default() }
    };
    if raw != -1 { return string_at(raw); }
    match kind {
        0x00 => String::new(),
        0x01 => if ((data as u32) >> 24) == 1 { format!("@android:0x{:08x}", data as u32) } else { format!("@0x{:08x}", data as u32) },
        0x02 => format!("?0x{:08x}", data as u32),
        0x03 => string_at(data),
        0x04 => format!("{:?}", f32::from_bits(data as u32)),
        0x05 => {
            let number = (data >> 8) as f32 * RADIX[((data >> 4) & 3) as usize];
            format!("{}{}", fmt_num(number), UNITS.get((data & 0xF) as usize).copied().unwrap_or(""))
        }
        0x06 => {
            let number = (data >> 8) as f32 * RADIX[((data >> 4) & 3) as usize] * 100.0;
            format!("{}{}", fmt_num(number), if data & 0xF == 1 { "%p" } else { "%" })
        }
        0x10 => data.to_string(),
        0x11 => format!("0x{:x}", data as u32),
        0x12 => (if data != 0 { "true" } else { "false" }).to_string(),
        0x1c..=0x1f => format!("#{:08x}", data as u32),
        _ => format!("0x{:x}", data as u32),
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

    #[test]
    fn rejects_chunk_size_overflow_and_out_of_bounds() {
        let mut bytes = vec![0u8; 16];
        bytes[0..4].copy_from_slice(&[3, 0, 8, 0]);
        bytes[4..8].copy_from_slice(&16u32.to_le_bytes());
        bytes[8..10].copy_from_slice(&1u16.to_le_bytes());
        bytes[12..16].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode(&bytes).is_err());
    }

    #[test]
    fn rejects_invalid_string_pool_header_size() {
        let mut bytes = vec![0u8; 36];
        bytes[0..4].copy_from_slice(&[3, 0, 8, 0]);
        bytes[4..8].copy_from_slice(&36u32.to_le_bytes());
        bytes[8..10].copy_from_slice(&1u16.to_le_bytes());
        bytes[10..12].copy_from_slice(&8u16.to_le_bytes());
        bytes[12..16].copy_from_slice(&28u32.to_le_bytes());
        assert!(decode(&bytes).is_err());
    }

    #[test]
    fn rejects_oversized_declared_file() {
        let mut bytes = vec![3, 0, 8, 0, 0, 0, 0, 0];
        bytes[4..8].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode(&bytes).is_err());
    }
}
