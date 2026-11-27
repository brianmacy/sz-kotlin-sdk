//! Shared JNI plumbing: the failure type, the error policy that raises Kotlin exceptions, and
//! argument/result conversion.

use crate::errors::{Raised, describe};
use jni::errors::ErrorPolicy;
use jni::objects::{JObject, JString, JValue};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use sz_rust_sdk::SzError;

/// Why a native method failed.
#[derive(Debug)]
pub enum Failure {
    /// The engine or `sz_rust_sdk` reported an error.
    Sz(SzError),
    /// The bridge itself refused the call (bad argument, no environment).
    Raised(Raised),
    /// A JNI call failed; any Java exception it raised is left pending.
    Jni(jni::errors::Error),
}

impl From<SzError> for Failure {
    fn from(error: SzError) -> Self {
        Self::Sz(error)
    }
}

impl From<jni::errors::Error> for Failure {
    fn from(error: jni::errors::Error) -> Self {
        Self::Jni(error)
    }
}

impl From<Raised> for Failure {
    fn from(raised: Raised) -> Self {
        Self::Raised(raised)
    }
}

/// The result of a bridge operation.
pub type Outcome<T> = Result<T, Failure>;

/// Raises `raised` as the matching Kotlin `SzException`, through `Native.raise`.
fn raise(env: &mut Env<'_>, raised: &Raised) {
    let Ok(class) = env.new_string(raised.class) else {
        return; // An exception (OutOfMemoryError) is already pending.
    };
    let Ok(message) = env.new_string(&raised.message) else {
        return;
    };
    // Native.raise always throws, so the call returns Err(JavaException) with the exception
    // pending: that is the result we want, propagated to the Kotlin caller.
    let _ = env.call_static_method(
        jni_str!("io/github/brianmacy/sz/internal/Native"),
        jni_str!("raise"),
        jni_sig!("(Ljava/lang/String;ZJLjava/lang/String;)V"),
        &[
            JValue::Object(&class),
            JValue::Bool(raised.code.is_some()),
            JValue::Long(raised.code.unwrap_or(0)),
            JValue::Object(&message),
        ],
    );
}

/// The error policy of every native method: Senzing errors become the matching Kotlin
/// `SzException`; a pending Java exception is left as is; a panic becomes an `SzException`.
pub struct RaiseSzException;

impl<T: Default> ErrorPolicy<T, Failure> for RaiseSzException {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        error: Failure,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let raised = match error {
                Failure::Sz(error) => describe(&error),
                Failure::Raised(raised) => raised,
                Failure::Jni(error) => Raised::new("SzError", format!("JNI error: {error}")),
            };
            raise(env, &raised);
        }
        Ok(T::default())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut Self::Captures<'unowned_env_local, 'native_method>,
        payload: Box<dyn std::any::Any + Send + 'static>,
    ) -> jni::errors::Result<T> {
        if !env.exception_check() {
            let text = payload
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            raise(
                env,
                &Raised::new("SzError", format!("panic in sz-kotlin-jni: {text}")),
            );
        }
        Ok(T::default())
    }
}

/// Runs a native method body under [`RaiseSzException`].
pub fn run<'local, T: Default>(
    unowned: &mut EnvUnowned<'local>,
    body: impl FnOnce(&mut Env<'local>) -> Outcome<T>,
) -> T {
    unowned.with_env(body).resolve::<RaiseSzException>()
}

/// A required Kotlin `String` argument.
pub fn string(env: &Env<'_>, value: &JString<'_>) -> Outcome<String> {
    if value.is_null() {
        return Err(Raised::new("SzBadInputError", "unexpected null string argument").into());
    }
    Ok(value.try_to_string(env)?)
}

/// An optional Kotlin `String?` argument.
pub fn optional_string(env: &Env<'_>, value: &JString<'_>) -> Outcome<Option<String>> {
    if value.is_null() {
        return Ok(None);
    }
    Ok(Some(value.try_to_string(env)?))
}

/// A Kotlin `String` result.
pub fn new_string<'local>(env: &mut Env<'local>, value: &str) -> Outcome<JString<'local>> {
    Ok(env.new_string(value)?)
}

/// A JSON argument: Kotlin encodes ID sets and record keys as JSON.
pub fn json<T: serde::de::DeserializeOwned>(text: &str) -> Outcome<T> {
    serde_json::from_str(text).map_err(|e| {
        Failure::Raised(Raised::new(
            "SzBadInputError",
            format!("invalid argument JSON: {e}"),
        ))
    })
}

/// A null Kotlin object result.
pub fn null<'local>() -> JObject<'local> {
    JObject::null()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_json_arguments() {
        let ids: Vec<i64> = json("[1,2]").unwrap();
        assert_eq!(ids, vec![1, 2]);
        match json::<Vec<i64>>("[") {
            Err(Failure::Raised(raised)) => assert_eq!(raised.class, "SzBadInputError"),
            other => panic!("unexpected {other:?}"),
        }
        assert!(null().is_null());
    }

    #[test]
    fn test_failure_conversions() {
        assert!(matches!(
            Failure::from(SzError::bad_input("x")),
            Failure::Sz(_)
        ));
        assert!(matches!(
            Failure::from(Raised::new("SzError", "x")),
            Failure::Raised(_)
        ));
        assert!(matches!(
            Failure::from(jni::errors::Error::NullPtr("x")),
            Failure::Jni(_)
        ));
    }
}
