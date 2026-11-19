//! Bounded parallel folder walk + collision-checked duplicate finder.
use crate::{hash::md5_file, verify::files_equal};
use rayon::prelude::*;
use std::{collections::HashMap, fs, path::{Path, PathBuf}, sync::Mutex, time::UNIX_EPOCH};

pub struct Rec {
    pub path: String,
    pub size: u64,
    pub mtime_ms: i64,
}

const MAX_SCAN_FILES: usize = 200_000;
const MAX_DEPTH: u32 = 32;
const PARTIAL: u64 = 64 * 1024;

fn dir_ok(name: &str, parent_is_root: bool) -> bool {
    name != ".Trash" && name != ".trashed" && name != ".KayXRecycleBin" &&
        !name.starts_with(".thumb") && !(parent_is_root && name == "Android")
}

fn walk(dir: &Path, root: &Path, depth: u32, out: &Mutex<Vec<Rec>>) {
    if depth > MAX_DEPTH || out.lock().map(|v| v.len() >= MAX_SCAN_FILES).unwrap_or(true) {
        return;
    }
    let rd = match fs::read_dir(dir) { Ok(r) => r, Err(_) => return };
    let mut local = Vec::new();
    let mut subs: Vec<PathBuf> = Vec::new();
    let at_root = dir == root;
    for entry in rd.flatten() {
        if local.len() >= MAX_SCAN_FILES { break; }
        let ft = match entry.file_type() { Ok(t) => t, Err(_) => continue };
        if ft.is_file() {
            if let Ok(metadata) = entry.metadata() {
                let mtime_ms = metadata.modified().ok()
                    .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
                    .and_then(|duration| i64::try_from(duration.as_millis()).ok())
                    .unwrap_or(0);
                local.push(Rec {
                    path: entry.path().to_string_lossy().into_owned(),
                    size: metadata.len(),
                    mtime_ms,
                });
            }
        } else if ft.is_dir() && subs.len() < MAX_SCAN_FILES && dir_ok(&entry.file_name().to_string_lossy(), at_root) {
            subs.push(entry.path());
        }
    }

    {
        let mut all = out.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let remaining = MAX_SCAN_FILES.saturating_sub(all.len());
        all.extend(local.into_iter().take(remaining));
    }
    if out.lock().map(|v| v.len() >= MAX_SCAN_FILES).unwrap_or(true) { return; }
    subs.par_iter().for_each(|sub| walk(sub, root, depth + 1, out));
}

pub fn scan_tree(root: &Path) -> Vec<Rec> {
    if !root.is_dir() { return Vec::new(); }
    let out = Mutex::new(Vec::with_capacity(20_000));
    match rayon::ThreadPoolBuilder::new().num_threads(4).build() {
        Ok(pool) => pool.install(|| walk(root, root, 0, &out)),
        Err(_) => walk(root, root, 0, &out),
    }
    out.into_inner().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Size -> partial MD5 -> full MD5 -> byte-for-byte verification.
pub fn find_duplicate_groups(paths: Vec<String>, min_size: u64) -> Vec<Vec<String>> {
    let mut by_size: HashMap<u64, Vec<String>> = HashMap::new();
    for path in paths {
        if let Ok(metadata) = fs::metadata(&path) {
            if metadata.is_file() && metadata.len() >= min_size {
                by_size.entry(metadata.len()).or_default().push(path);
            }
        }
    }

    let candidates: Vec<(u64, Vec<String>)> = by_size.into_iter().filter(|(_, paths)| paths.len() > 1).collect();
    let work = move || -> Vec<Vec<String>> {
        candidates.par_iter().flat_map(|(size, group)| {
            let mut partial: HashMap<String, Vec<String>> = HashMap::new();
            for path in group {
                if let Ok(hash) = md5_file(Path::new(path), Some(PARTIAL)) {
                    partial.entry(hash).or_default().push(path.clone());
                }
            }

            let mut verified = Vec::new();
            for same_prefix in partial.into_values().filter(|paths| paths.len() > 1) {
                let mut full: HashMap<String, Vec<String>> = HashMap::new();
                for path in same_prefix {
                    if let Ok(hash) = md5_file(Path::new(&path), None) {
                        full.entry(hash).or_default().push(path);
                    }
                }

                for same_hash in full.into_values().filter(|paths| paths.len() > 1) {
                    // Hashes are only a prefilter. Never report duplicates until bytes match.
                    let mut exact_groups: Vec<Vec<String>> = Vec::new();
                    for path in same_hash {
                        let mut matched = false;
                        for exact in exact_groups.iter_mut() {
                            if files_equal(Path::new(&path), Path::new(&exact[0])).unwrap_or(false) {
                                exact.push(path.clone());
                                matched = true;
                                break;
                            }
                        }
                        if !matched { exact_groups.push(vec![path]); }
                    }
                    verified.extend(exact_groups.into_iter().filter(|paths| paths.len() > 1));
                }
            }
            let _ = size;
            verified
        }).collect()
    };

    match rayon::ThreadPoolBuilder::new().num_threads(4).build() {
        Ok(pool) => pool.install(work),
        Err(_) => work(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn tmp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("kayx_test_{}_{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn finds_duplicates_skips_android_and_recycle_bin() {
        let dir = tmp("dupes");
        let data = vec![7u8; 20 * 1024];
        for name in ["a.bin", "b.bin"] {
            fs::File::create(dir.join(name)).unwrap().write_all(&data).unwrap();
        }
        let mut other = data.clone();
        other[100] = 1;
        fs::File::create(dir.join("c.bin")).unwrap().write_all(&other).unwrap();
        for folder in ["Android", ".KayXRecycleBin"] {
            fs::create_dir_all(dir.join(folder)).unwrap();
            fs::File::create(dir.join(folder).join("hidden.bin")).unwrap().write_all(&data).unwrap();
        }

        let records = scan_tree(&dir);
        assert_eq!(records.len(), 3);
        let groups = find_duplicate_groups(records.into_iter().map(|record| record.path).collect(), 10 * 1024);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].len(), 2);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn scan_does_not_follow_symlink_directories() {
        let dir = tmp("symlink");
        let outside = tmp("outside");
        fs::write(outside.join("secret.bin"), b"secret").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, dir.join("linked")).unwrap();
        let records = scan_tree(&dir);
        assert!(records.is_empty());
        fs::remove_dir_all(dir).unwrap();
        fs::remove_dir_all(outside).unwrap();
    }
}
