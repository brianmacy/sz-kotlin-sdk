//! Generates the error taxonomy (`SzErrorCategory`, `classify`) from `szerrors.json` with
//! sz-rust-sdk-ffi's generator, and sets the library's runtime search path so `libSz` is found
//! beside this library.

use std::env;
use std::path::PathBuf;

/// The `szerrors.json` to generate from: the one in the runtime this library is built against
/// (`$SENZING_DIR/sdk/szerrors.json`), else the copy committed beside this file.
fn szerrors_json() -> PathBuf {
    if let Ok(dir) = env::var("SENZING_DIR") {
        let installed = PathBuf::from(dir).join("sdk").join("szerrors.json");
        if installed.is_file() {
            return installed;
        }
    }
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR")).join("szerrors.json")
}

fn main() {
    let source = szerrors_json();
    let generated = sz_rust_sdk_ffi::codegen::generate_error_taxonomy(&source)
        .unwrap_or_else(|e| panic!("generating the error taxonomy from {}: {e}", source.display()));
    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("szerrors_generated.rs");
    std::fs::write(&out, generated).unwrap_or_else(|e| panic!("writing {}: {e}", out.display()));

    // libSz is installed beside this library (see the README).
    match env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("macos") | Ok("ios") => println!("cargo:rustc-cdylib-link-arg=-Wl,-rpath,@loader_path"),
        Ok("linux") | Ok("android") => println!("cargo:rustc-cdylib-link-arg=-Wl,-rpath,$ORIGIN"),
        _ => {}
    }
    println!("cargo:rerun-if-changed={}", source.display());
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=SENZING_DIR");
}
