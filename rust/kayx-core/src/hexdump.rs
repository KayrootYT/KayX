use std::{fs::File, io::{self, Read, Seek, SeekFrom}, path::Path};

/// Classic hex dump: `OFFSET  HH HH ..  ascii`.
pub fn dump(path: &Path, offset: u64, length: usize, per_row: usize) -> io::Result<String> {
    let per = per_row.clamp(4, 64);
    let mut f = File::open(path)?;
    f.seek(SeekFrom::Start(offset))?;
    let mut buf = vec![0u8; length.min(4 * 1024 * 1024)];
    let mut got = 0;
    while got < buf.len() {
        let n = f.read(&mut buf[got..])?;
        if n == 0 { break; }
        got += n;
    }
    buf.truncate(got);
    let mut out = String::with_capacity(got * 4 + 64);
    for (i, row) in buf.chunks(per).enumerate() {
        out.push_str(&format!("{:08X}  ", offset + (i * per) as u64));
        for k in 0..per {
            match row.get(k) {
                Some(b) => out.push_str(&format!("{:02X} ", b)),
                None => out.push_str("   "),
            }
        }
        out.push(' ');
        for &b in row {
            out.push(if (0x20..0x7f).contains(&b) { b as char } else { '.' });
        }
        out.push('\n');
    }
    Ok(out)
}
