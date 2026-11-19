//! KayX native core. Every JNI entry point returns null on failure so the Kotlin
//! side can transparently fall back to its pure-JVM implementation.
pub mod axml;
pub mod hash;
pub mod hexdump;
pub mod scan;
pub mod verify;

use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::ptr::null_mut;

fn guard<T>(f: impl FnOnce() -> Option<T>) -> Option<T> {
    catch_unwind(AssertUnwindSafe(f)).ok().flatten()
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|j| j.into())
}

fn out_string(env: &mut JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(s) => env.new_string(s).map(|j| j.into_raw()).unwrap_or(null_mut()),
        None => null_mut(),
    }
}

fn build_array(env: &mut JNIEnv, v: &[String]) -> jni::errors::Result<jobjectArray> {
    let arr = env.new_object_array(v.len() as i32, "java/lang/String", JObject::null())?;
    for (i, s) in v.iter().enumerate() {
        let js = env.new_string(s)?;
        env.set_object_array_element(&arr, i as i32, &js)?;
        env.delete_local_ref(js)?;
    }
    Ok(arr.into_raw())
}

fn out_array(env: &mut JNIEnv, v: Option<Vec<String>>) -> jobjectArray {
    match v {
        Some(v) => build_array(env, &v).unwrap_or(null_mut()),
        None => null_mut(),
    }
}

/// "md5|sha1|sha256|crc32"
#[no_mangle]
pub extern "system" fn Java_com_kayx_manager_core_NativeCore_hashFile<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, path: JString<'l>,
) -> jstring {
    let r = jstr(&mut env, &path).and_then(|p| guard(|| {
        let h = hash::hash_file(Path::new(&p)).ok()?;
        Some(format!("{}|{}|{}|{}", h.md5, h.sha1, h.sha256, h.crc32))
    }));
    out_string(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_kayx_manager_core_NativeCore_decodeAxml<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, data: JByteArray<'l>,
) -> jstring {
    let size = env.get_array_length(&data).unwrap_or(-1);
    if size < 0 || size as usize > 64 * 1024 * 1024 { return null_mut(); }
    let r = env.convert_byte_array(&data).ok().and_then(|b| guard(|| axml::decode(&b).ok()));
    out_string(&mut env, r)
}

/// Lines of "size\tmtimeMs\tpath".
#[no_mangle]
pub extern "system" fn Java_com_kayx_manager_core_NativeCore_scanTree<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, root: JString<'l>,
) -> jobjectArray {
    let r = jstr(&mut env, &root).and_then(|p| guard(|| {
        let root = Path::new(&p);
        if !root.is_dir() { return None; }
        Some(scan::scan_tree(root).into_iter()
            .map(|r| format!("{}\t{}\t{}", r.size, r.mtime_ms, r.path)).collect::<Vec<_>>())
    }));
    out_array(&mut env, r)
}

/// Each element = one group of identical files, paths joined by U+001F.
#[no_mangle]
pub extern "system" fn Java_com_kayx_manager_core_NativeCore_findDuplicates<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, paths: JObjectArray<'l>,
) -> jobjectArray {
    let mut list: Vec<String> = Vec::new();
    let n = env.get_array_length(&paths).unwrap_or(-1);
    if n < 0 || n as usize > 200_000 { return null_mut(); }
    let mut total_path_bytes = 0usize;
    for i in 0..n {
        let Ok(o) = env.get_object_array_element(&paths, i) else { continue };
        let js = JString::from(o);
        if let Some(s) = jstr(&mut env, &js) {
            total_path_bytes = match total_path_bytes.checked_add(s.len()) {
                Some(total) if total <= 16 * 1024 * 1024 => total,
                _ => return null_mut(),
            };
            if s.len() > 4096 || s.chars().any(|ch| matches!(ch, '\\u{1f}' | '\\t' | '\\n' | '\\r')) { return null_mut(); }
            list.push(s);
        }
        let _ = env.delete_local_ref(js);
    }
    let r = guard(|| Some(scan::find_duplicate_groups(list, 10 * 1024)
        .into_iter().map(|g| g.join("\u{1f}")).collect::<Vec<_>>()));
    out_array(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_kayx_manager_core_NativeCore_hexDump<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, path: JString<'l>, offset: jlong, length: jint, per_row: jint,
) -> jstring {
    let r = jstr(&mut env, &path).and_then(|p| guard(|| {
        hexdump::dump(Path::new(&p), offset.max(0) as u64, length.clamp(0, 4 * 1024 * 1024) as usize, per_row.clamp(1, 64) as usize).ok()
    }));
    out_string(&mut env, r)
}
