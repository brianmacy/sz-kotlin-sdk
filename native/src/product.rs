//! `SzProduct`, `SzDiagnostic` and error-taxonomy native methods.

use crate::bridge::{Outcome, new_string, run};
use crate::environment::current;
use crate::errors::classify;
use jni::objects::{JClass, JString};
use jni::sys::jlong;
use jni::{Env, EnvUnowned};
use sz_rust_sdk::prelude::*;

fn text<'local>(
    env: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> Outcome<String>,
) -> JString<'local> {
    run(env, |env| {
        let result = body(env)?;
        new_string(env, &result)
    })
}

fn diagnostic() -> Outcome<Box<dyn SzDiagnostic>> {
    Ok(current()?.get_diagnostic()?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getLicense<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_| Ok(current()?.get_product()?.get_license()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getVersion<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_| Ok(current()?.get_product()?.get_version()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getRepositoryInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_| Ok(diagnostic()?.get_repository_info()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_checkRepositoryPerformance<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    seconds_to_run: jlong,
) -> JString<'local> {
    text(&mut env, |_| {
        Ok(diagnostic()?.check_repository_performance(seconds_to_run)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_purgeRepository<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    run(&mut env, |_| Ok(diagnostic()?.purge_repository()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getFeature<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    feature_id: jlong,
) -> JString<'local> {
    text(&mut env, |_| Ok(diagnostic()?.get_feature(feature_id)?))
}

/// The `szerrors.json` class of a native error code (`SzError` for unknown codes).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_classify<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    code: jlong,
) -> JString<'local> {
    text(&mut env, |_| {
        Ok(i32::try_from(code)
            .map(|code| classify(code).as_class_str())
            .unwrap_or("SzError")
            .to_string())
    })
}
