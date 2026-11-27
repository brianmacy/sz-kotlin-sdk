//! JNI bridge between the Kotlin SDK (`io.github.brianmacy.sz`) and the Senzing engine.
//!
//! Every native method of the Kotlin `io.github.brianmacy.sz.internal.Native` object is
//! implemented here, over [`sz_rust_sdk`], which wraps libSz's public C API. The bridge holds
//! no SDK policy: the Kotlin side owns the environment lifecycle, locking, flag defaults and
//! result shaping. This crate only converts arguments, calls `sz_rust_sdk`, and turns its
//! errors into the Kotlin exception hierarchy (by `szerrors.json` class name).

mod bridge;
mod config;
mod engine;
mod environment;
mod errors;
mod logging;
mod product;

pub use errors::{SzErrorCategory, classify};
