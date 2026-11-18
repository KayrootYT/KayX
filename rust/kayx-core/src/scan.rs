//! Parallel folder walk + duplicate finder.
use crate::hash::md5_file;
use rayon::prelude::*;
use std::{collections::HashMap, fs, path::{Path, PathBuf}, sync::Mutex, time::UNIX_EPOCH};

pub struct Rec {
    pub path: String,
    pub size: u64,
    pub mtime_ms: i64,
}

fn dir_ok(name: &str, parent_is_root: bool) -> bool {
    name != ".Trash" && name != ".trashed" && !name.starts_with(".thumb") && !(parent_is_root && name == "Android")
}

fn walk(dir: &Path, root: &Path, depth: u32, out: &Mutex<Vec<Rec>>) {
    if depth > 20 {
        return;
    }
    let rd = match fs::read_dir(dir) { Ok(r) => r, Err(_) => return };
    let mut local = Vec::new();
    let mut subs: Vec<PathBuf> = Vec::new();
    let at_root = dir == root;
    for e in rd.flatten() {
        let ft = match e.file_type() { Ok(t) => t, Err(_) => continue };
        if ft.is_file() {
            if let Ok(m) = e.metadata() {
                let mtime_ms = m.modified().ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as i64).unwrap_or(0);
                local.push(Rec { path: e.path().to_string_lossy().into_owned(), size: m.len(), mtime_ms });
            }
        } else if ft.is_dir() {
            if dir_ok(&e.file_name().to_string_lossy(), at_root) {
                subs.push(e.path());
            }
        }
    }
    out.lock().unwrap_or_else(|p| p.into_inner()).extend(local);
    subs.par_iter().for_each(|s| walk(s, root, depth + 1, out));
}

pub fn scan_tree(root: &Path) -> Vec<Rec> {
    let out = Mutex::new(Vec::with_capacity(20_000));
    match rayon::ThreadPoolBuilder::new().num_threads(4).build() {
        Ok(pool) => pool.install(|| walk(root, root, 0, &out)),
        Err(_) => walk(root, root, 0, &out),
    }
    out.into_inner().unwrap_or_else(|p| p.into_inner())
}

const PARTIAL: u64 = 64 * 1024;

/// Groups of files with identical content. Size -> first-64KB hash -> full hash.
pub fn find_duplicate_groups(paths: Vec<String>, min_size: u64) -> Vec<Vec<String>> {
    let mut by_size: HashMap<u64, Vec<String>> = HashMap::new();
    for p in paths {
        if let Ok(m) = fs::metadata(&p) {
            if m.is_file() && m.len() >= min_size {
                by_size.entry(m.len()).or_default().push(p);
            }
        }
    }
    let groups: Vec<(u64, Vec<String>)> = by_size.into_iter().filter(|(_, v)| v.len() > 1).collect();
    let work = move || -> Vec<Vec<String>> {
        groups.par_iter().flat_map(|(size, g)| {
            let mut by_part: HashMap<String, Vec<&String>> = HashMap::new();
            for p in g {
                if let Ok(h) = md5_file(Path::new(p), Some(PARTIAL)) {
                    by_part.entry(h).or_default().push(p);
                }
            }
            let mut found: Vec<Vec<String>> = Vec::new();
            for (_, cand) in by_part.into_iter().filter(|(_, v)| v.len() > 1) {
                if *size <= PARTIAL {
                    found.push(cand.into_iter().cloned().collect());
                    continue;
                }
                let mut by_full: HashMap<String, Vec<String>> = HashMap::new();
                for p in cand {
                    if let Ok(h) = md5_file(Path::new(p), None) {
                        by_full.entry(h).or_default().push(p.clone());
                    }
                }
                found.extend(by_full.into_values().filter(|v| v.len() > 1));
            }
            found
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
        let d = std::env::temp_dir().join(format!("kayx_test_{}_{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn finds_duplicates_and_skips_android_dir() {
        let d = tmp("dupes");
        let data = vec![7u8; 20 * 1024];
        for n in ["a.bin", "b.bin"] {
            fs::File::create(d.join(n)).unwrap().write_all(&data).unwrap();
        }
        let mut other = data.clone();
        other[100] = 1;
        fs::File::create(d.join("c.bin")).unwrap().write_all(&other).unwrap();
        fs::create_dir_all(d.join("Android")).unwrap();
        fs::File::create(d.join("Android").join("x.bin")).unwrap().write_all(&data).unwrap();

        let recs = scan_tree(&d);
        assert_eq!(recs.len(), 3);
        let g = find_duplicate_groups(recs.into_iter().map(|r| r.path).collect(), 10 * 1024);
        assert_eq!(g.len(), 1);
        assert_eq!(g[0].len(), 2);
        let _ = fs::remove_dir_all(&d);
    }
}
