//! The process's one `SzEnvironmentCore` and the environment-level native methods.
//!
//! The Kotlin `SzCoreEnvironment` owns the lifecycle: it allows one active environment, runs
//! every call under its lock, and destroys only when no call is in flight. This module keeps the
//! `sz_rust_sdk` environment those calls use.

use crate::bridge::{Outcome, run, string};
use crate::errors::{ENVIRONMENT_DESTROYED, Raised};
use jni::EnvUnowned;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use std::sync::{Arc, Mutex, MutexGuard};
use sz_rust_sdk::prelude::*;

static ENVIRONMENT: Mutex<Option<Arc<SzEnvironmentCore>>> = Mutex::new(None);

fn slot() -> MutexGuard<'static, Option<Arc<SzEnvironmentCore>>> {
    ENVIRONMENT
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// The active environment.
pub fn current() -> Outcome<Arc<SzEnvironmentCore>> {
    slot().clone().ok_or_else(|| {
        Raised::new(
            ENVIRONMENT_DESTROYED,
            "The Senzing environment has already been destroyed.",
        )
        .into()
    })
}

/// The engine of the active environment (initialized on first use).
pub fn engine() -> Outcome<Box<dyn SzEngine>> {
    Ok(current()?.get_engine()?)
}

/// The configuration manager of the active environment (initialized on first use).
pub fn config_manager() -> Outcome<Box<dyn SzConfigManager>> {
    Ok(current()?.get_config_manager()?)
}

fn create(instance_name: &str, settings: &str, verbose: bool) -> Outcome<()> {
    let mut slot = slot();
    if slot.is_some() {
        return Err(Raised::new(
            "SzError",
            "Only one active SzEnvironment is permitted per process; destroy the existing instance first.",
        )
        .into());
    }
    *slot = Some(SzEnvironmentCore::get_instance(
        instance_name,
        settings,
        verbose,
    )?);
    Ok(())
}

fn destroy() -> Outcome<()> {
    let taken = slot().take();
    match taken {
        Some(environment) => Ok(environment.destroy()?),
        None => Ok(()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envCreate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    instance_name: JString<'local>,
    settings: JString<'local>,
    verbose: jboolean,
) {
    run(&mut env, |env| {
        let instance_name = string(env, &instance_name)?;
        let settings = string(env, &settings)?;
        create(&instance_name, &settings, verbose)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envDestroy<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| destroy())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envInitEngine<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| engine().map(drop))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envInitProduct<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| Ok(current()?.get_product().map(drop)?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envInitConfigManager<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| config_manager().map(drop))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envGetActiveConfigId<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    run(&mut env, |_| Ok(current()?.get_active_config_id()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_envReinitialize<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_id: jlong,
) {
    run(&mut env, |_| Ok(current()?.reinitialize(config_id)?))
}
