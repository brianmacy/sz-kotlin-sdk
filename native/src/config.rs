//! `SzConfigManager` and `SzConfig` native methods.
//!
//! A Kotlin `SzConfig` holds its configuration definition, as the Swift and C# SDKs do; each
//! `SzConfig` operation loads the definition into a native configuration, applies the
//! operation, and returns the result together with the definition it leaves.

use crate::bridge::{Outcome, new_string, null, run, string};
use crate::environment::config_manager;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::jlong;
use jni::{Env, EnvUnowned, jni_str};

/// A native method returning text.
fn text<'local>(
    env: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> Outcome<String>,
) -> JString<'local> {
    run(env, |env| {
        let result = body(env)?;
        new_string(env, &result)
    })
}

/// Applies `operation` to a native configuration loaded from `definition`; returns the
/// operation's result and the configuration's definition afterwards.
fn edit<'local>(
    env: &mut Env<'local>,
    definition: &JString<'local>,
    operation: impl FnOnce(&dyn sz_rust_sdk::SzConfig) -> Outcome<String>,
) -> Outcome<JObject<'local>> {
    let definition = string(env, definition)?;
    let config = config_manager()?.create_config_from_definition(&definition)?;
    let result = operation(config.as_ref())?;
    let updated = config.export()?;
    let pair: JObjectArray<'local> =
        env.new_object_array(2, jni_str!("java/lang/String"), null())?;
    let result = new_string(env, &result)?;
    let updated = new_string(env, &updated)?;
    pair.set_element(env, 0, &result)?;
    pair.set_element(env, 1, &updated)?;
    Ok(pair.into())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configTemplate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_| {
        Ok(config_manager()?.create_config()?.export()?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configFromId<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_id: jlong,
) -> JString<'local> {
    text(&mut env, |_| {
        Ok(config_manager()?
            .create_config_from_id(config_id)?
            .export()?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configNormalize<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    definition: JString<'local>,
) -> JString<'local> {
    text(&mut env, |env| {
        let definition = string(env, &definition)?;
        Ok(config_manager()?
            .create_config_from_definition(&definition)?
            .export()?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configDataSourceRegistry<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    definition: JString<'local>,
) -> JString<'local> {
    text(&mut env, |env| {
        let definition = string(env, &definition)?;
        Ok(config_manager()?
            .create_config_from_definition(&definition)?
            .get_data_source_registry()?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configRegisterDataSource<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    definition: JString<'local>,
    data_source_code: JString<'local>,
) -> JObject<'local> {
    run(&mut env, |env| {
        let code = string(env, &data_source_code)?;
        edit(env, &definition, |config| {
            Ok(config.register_data_source(&code)?)
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_configUnregisterDataSource<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    definition: JString<'local>,
    data_source_code: JString<'local>,
) -> JObject<'local> {
    run(&mut env, |env| {
        let code = string(env, &data_source_code)?;
        edit(env, &definition, |config| {
            config.unregister_data_source(&code)?;
            Ok(String::new())
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_registerConfig<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    definition: JString<'local>,
    comment: JString<'local>,
) -> jlong {
    run(&mut env, |env| {
        let definition = string(env, &definition)?;
        let comment = string(env, &comment)?;
        Ok(config_manager()?.register_config(&definition, Some(&comment))?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getConfigRegistry<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    text(&mut env, |_| Ok(config_manager()?.get_config_registry()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_getDefaultConfigId<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    run(&mut env, |_| Ok(config_manager()?.get_default_config_id()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_replaceDefaultConfigId<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    current_default_config_id: jlong,
    new_default_config_id: jlong,
) {
    run(&mut env, |_| {
        Ok(config_manager()?
            .replace_default_config_id(current_default_config_id, new_default_config_id)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_setDefaultConfigId<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_id: jlong,
) {
    run(&mut env, |_| {
        Ok(config_manager()?.set_default_config_id(config_id)?)
    })
}
