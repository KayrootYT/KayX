//! Collision-resistant file verification used after fast hash bucketing.
use std::{
    fs::File,
    io::{self, BufReader, Read},
    path::Path,
};

/// Compare two regular files byte-for-byte without loading either file into memory.
pub fn files_equal(left: &Path, right: &Path) -> io::Result<bool> {
    let left_meta = std::fs::metadata(left)?;
    let right_meta = std::fs::metadata(right)?;
    if !left_meta.is_file() || !right_meta.is_file() || left_meta.len() != right_meta.len() {
        return Ok(false);
    }

    let mut a = BufReader::new(File::open(left)?);
    let mut b = BufReader::new(File::open(right)?);
    let mut ab = [0u8; 64 * 1024];
    let mut bb = [0u8; 64 * 1024];
    loop {
        let an = read_full_or_eof(&mut a, &mut ab)?;
        let bn = read_full_or_eof(&mut b, &mut bb)?;
        if an != bn || ab[..an] != bb[..bn] {
            return Ok(false);
        }
        if an == 0 {
            return Ok(true);
        }
    }
}

fn read_full_or_eof<R: Read>(reader: &mut R, buffer: &mut [u8]) -> io::Result<usize> {
    let mut used = 0;
    while used < buffer.len() {
        match reader.read(&mut buffer[used..]) {
            Ok(0) => break,
            Ok(n) => used += n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(used)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, io::Write, path::PathBuf};

    fn temp(name: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("kayx_verify_{}_{}", name, std::process::id()));
        let _ = fs::remove_file(&p);
        p
    }

    #[test]
    fn compares_contents_not_just_file_size() {
        let a = temp("a");
        let b = temp("b");
        File::create(&a).unwrap().write_all(b"same-size-a").unwrap();
        File::create(&b).unwrap().write_all(b"same-size-b").unwrap();
        assert!(!files_equal(&a, &b).unwrap());
        fs::write(&b, b"same-size-a").unwrap();
        assert!(files_equal(&a, &b).unwrap());
        fs::remove_file(a).unwrap();
        fs::remove_file(b).unwrap();
    }

    #[test]
    fn empty_files_are_equal() {
        let a = temp("empty-a");
        let b = temp("empty-b");
        fs::write(&a, []).unwrap();
        fs::write(&b, []).unwrap();
        assert!(files_equal(&a, &b).unwrap());
        fs::remove_file(a).unwrap();
        fs::remove_file(b).unwrap();
    }
}
