use md5::{Digest, Md5};
use sha1::Sha1;
use sha2::Sha256;
use std::{fs::File, io::{self, Read}, path::Path};

pub struct Hashes {
    pub md5: String,
    pub sha1: String,
    pub sha256: String,
    pub crc32: String,
}

pub fn hex(b: &[u8]) -> String {
    const T: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(b.len() * 2);
    for &x in b {
        s.push(T[(x >> 4) as usize] as char);
        s.push(T[(x & 15) as usize] as char);
    }
    s
}

/// One pass over the data computes MD5, SHA-1, SHA-256 and CRC32 together.
pub fn hash_reader<R: Read>(mut r: R) -> io::Result<Hashes> {
    let mut m = Md5::new();
    let mut s1 = Sha1::new();
    let mut s256 = Sha256::new();
    let mut c = crc32fast::Hasher::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = match r.read(&mut buf) {
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        if n == 0 {
            break;
        }
        let d = &buf[..n];
        Digest::update(&mut m, d);
        Digest::update(&mut s1, d);
        Digest::update(&mut s256, d);
        c.update(d);
    }
    Ok(Hashes {
        md5: hex(&m.finalize()),
        sha1: hex(&s1.finalize()),
        sha256: hex(&s256.finalize()),
        crc32: format!("{:08x}", c.finalize()),
    })
}

pub fn hash_file(p: &Path) -> io::Result<Hashes> {
    hash_reader(File::open(p)?)
}

/// MD5 of the whole file, or only its first `limit` bytes.
pub fn md5_file(p: &Path, limit: Option<u64>) -> io::Result<String> {
    let f = File::open(p)?;
    let mut r: Box<dyn Read> = match limit {
        Some(n) => Box::new(f.take(n)),
        None => Box::new(f),
    };
    let mut m = Md5::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = match r.read(&mut buf) {
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        };
        if n == 0 {
            break;
        }
        Digest::update(&mut m, &buf[..n]);
    }
    Ok(hex(&m.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn known_vectors_abc() {
        let h = hash_reader(&b"abc"[..]).unwrap();
        assert_eq!(h.md5, "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(h.sha1, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(h.sha256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(h.crc32, "352441c2");
    }
    #[test]
    fn empty_md5() {
        assert_eq!(hash_reader(&b""[..]).unwrap().md5, "d41d8cd98f00b204e9800998ecf8427e");
    }
}
