//! `SzLogging` native methods. Log callbacks call back into Kotlin: each registration keeps a
//! global reference to a Kotlin `NativeLogSink` and calls its `deliver(level, module, message)`
//! on the thread that logged, attaching that thread to the JVM when needed.

use crate::bridge::{Outcome, optional_string, run};
use crate::errors::Raised;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::refs::Global;
use jni::sys::{jint, jlong};
use jni::vm::JavaVM;
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use std::ffi::c_void;
use sz_rust_sdk::prelude::*;

fn logging() -> Outcome<Box<dyn SzLogging>> {
    // Logging is process-wide and needs no initialized engine: sz_rust_sdk hands it out from
    // any environment value, without native calls (an unregistered `new` environment does no
    // native work, on creation or drop). So logging works before the first environment and
    // after `destroy()`.
    Ok(SzEnvironmentCore::new("sz-kotlin-sdk-logging", "{}", false)?.get_logging()?)
}

fn level(value: jint) -> Outcome<SzLogLevel> {
    usize::try_from(value)
        .ok()
        .and_then(|i| SzLogLevel::ALL.get(i).copied())
        .ok_or_else(|| Raised::new("SzBadInputError", format!("unknown log level {value}")).into())
}

fn format(value: jint) -> Outcome<SzLogFormat> {
    usize::try_from(value)
        .ok()
        .and_then(|i| SzLogFormat::ALL.get(i).copied())
        .ok_or_else(|| Raised::new("SzBadInputError", format!("unknown log format {value}")).into())
}

/// A log callback that forwards records to a Kotlin `NativeLogSink`.
fn forwarder(env: &mut Env<'_>, sink: &JObject<'_>) -> Outcome<SzLogCallback> {
    if sink.is_null() {
        return Err(Raised::new("SzBadInputError", "unexpected null log callback").into());
    }
    let vm: JavaVM = env.get_java_vm()?;
    let sink: Global<JObject<'static>> = env.new_global_ref(sink)?;
    Ok(Box::new(move |record: &SzLogRecord<'_>| {
        let level = record.level() as jint;
        let module = record.module();
        let message = record.message();
        // NativeLogSink.deliver never throws (it hands a callback's exception to the thread's
        // uncaught-exception handler), so the only failures here are JNI failures such as an
        // OutOfMemoryError, which the JVM reports itself; a log callback has no caller to
        // return them to.
        let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let module = env.new_string(module.as_ref())?;
            let message = env.new_string(message.as_ref())?;
            env.call_method(
                sink.as_obj(),
                jni_str!("deliver"),
                jni_sig!("(ILjava/lang/String;Ljava/lang/String;)V"),
                &[
                    JValue::Int(level),
                    JValue::Object(&module),
                    JValue::Object(&message),
                ],
            )?;
            Ok(())
        });
    }))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logRegister<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    min_level: jint,
    log_format: jint,
    sink: JObject<'local>,
) -> jlong {
    run(&mut env, |env| {
        let options = SzLogCallbackOptions::new(level(min_level)?).format(format(log_format)?);
        let callback = forwarder(env, &sink)?;
        let registration = logging()?.register_callback(options, callback)?;
        Ok(Box::into_raw(Box::new(registration)) as jlong)
    })
}

/// Takes back a registration handed to Kotlin by `logRegister`.
///
/// # Safety
/// `handle` must come from `logRegister` and not have been passed here before (the Kotlin
/// registration object guarantees both).
unsafe fn take(handle: jlong) -> Box<SzLogRegistration> {
    unsafe { Box::from_raw(handle as *mut c_void as *mut SzLogRegistration) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logUnregister<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    run(&mut env, |_| {
        // SAFETY: see `take`.
        unsafe { take(handle) }.unregister();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logLeak<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    run(&mut env, |_| {
        // SAFETY: see `take`.
        unsafe { take(handle) }.leak();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logSetDefaultCallback<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    min_level: jint,
    log_format: jint,
    sink: JObject<'local>,
) {
    run(&mut env, |env| {
        let options = SzLogCallbackOptions::new(level(min_level)?).format(format(log_format)?);
        let callback = forwarder(env, &sink)?;
        Ok(logging()?.set_default_callback(options, callback)?)
    })
}

/// Output types, as Kotlin's `SzLogOutputType` ordinals.
const OUTPUT_STDERR: jint = 0;
const OUTPUT_STDOUT: jint = 1;
const OUTPUT_FILE: jint = 2;
const OUTPUT_NULL: jint = 3;

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logSetDefaultOutput<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    output_type: jint,
    path: JString<'local>,
) {
    run(&mut env, |env| {
        let output = match (output_type, optional_string(env, &path)?) {
            (OUTPUT_STDERR, _) => SzLogOutput::Stderr,
            (OUTPUT_STDOUT, _) => SzLogOutput::Stdout,
            (OUTPUT_NULL, _) => SzLogOutput::Null,
            (OUTPUT_FILE, Some(path)) => SzLogOutput::file(path),
            (other, _) => {
                return Err(
                    Raised::new("SzBadInputError", format!("invalid log output {other}")).into(),
                );
            }
        };
        Ok(logging()?.set_default_output(output)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logSetDefaultFormat<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    log_format: jint,
) {
    run(&mut env, |_| {
        Ok(logging()?.set_default_format(format(log_format)?)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logSetDefaultLevel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    min_level: jint,
) {
    run(&mut env, |_| {
        Ok(logging()?.set_default_log_level(level(min_level)?)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_brianmacy_sz_internal_Native_logEffectiveLevel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jint {
    run(&mut env, |_| {
        Ok(logging()?.get_effective_log_level()? as jint)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_levels_and_formats_follow_native_values() {
        assert_eq!(level(0).unwrap(), SzLogLevel::Trace);
        assert_eq!(level(6).unwrap(), SzLogLevel::Off);
        assert!(level(7).is_err());
        assert!(level(-1).is_err());
        assert_eq!(format(1).unwrap(), SzLogFormat::Jsonl);
        assert!(format(4).is_err());
    }
}
