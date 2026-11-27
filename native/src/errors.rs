//! Maps `sz_rust_sdk` errors to the `szerrors.json` class names the Kotlin side raises.

use sz_rust_sdk::SzError;

#[allow(dead_code)] // CODES/ALL are part of the generated taxonomy; the bridge uses classify.
mod generated {
    include!(concat!(env!("OUT_DIR"), "/szerrors_generated.rs"));
}

pub use generated::{SzErrorCategory, classify};

/// The class name the SDK raises for "the environment was destroyed" (not in `szerrors.json`).
pub const ENVIRONMENT_DESTROYED: &str = "SzEnvironmentDestroyedError";

/// A failure to report to Kotlin: a `szerrors.json` class name, the native error code if any,
/// and the message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Raised {
    pub class: &'static str,
    pub code: Option<i64>,
    pub message: String,
}

impl Raised {
    pub fn new(class: &'static str, message: impl Into<String>) -> Self {
        Self {
            class,
            code: None,
            message: message.into(),
        }
    }
}

/// The native error code of an error. `sz_rust_sdk` keeps it in the error, except for a failed
/// subsystem initialization, which it reports as text ending in `(code: N)`.
fn native_code(error: &SzError) -> Option<i64> {
    error.error_code().or_else(|| wrapped_code(error.message()))
}

/// Parses the trailing `(code: N)` that `sz_rust_sdk` appends to an error's text.
fn wrapped_code(message: &str) -> Option<i64> {
    let start = message.rfind("(code: ")? + "(code: ".len();
    let rest = &message[start..];
    let end = rest.find(')')?;
    rest[..end].trim().parse().ok()
}

/// The class for an error without a native code, from the `sz_rust_sdk` variant.
fn class_of_variant(error: &SzError) -> &'static str {
    match error {
        SzError::BadInput(_) | SzError::StringConversion(_) => "SzBadInputError",
        SzError::Configuration(_) => "SzConfigurationError",
        SzError::Database(_) => "SzDatabaseError",
        SzError::License(_) => "SzLicenseError",
        SzError::NotFound(_) => "SzNotFoundError",
        SzError::Retryable(_) => "SzRetryableError",
        SzError::Unrecoverable(_) => "SzUnrecoverableError",
        SzError::NotInitialized(_) => "SzNotInitializedError",
        SzError::DatabaseConnectionLost(_) => "SzDatabaseConnectionLostError",
        SzError::DatabaseTransient(_) => "SzDatabaseTransientError",
        SzError::ReplaceConflict(_) => "SzReplaceConflictError",
        SzError::RetryTimeoutExceeded(_) => "SzRetryTimeoutExceededError",
        SzError::Unhandled(_) => "SzUnhandledError",
        SzError::UnknownDataSource(_) => "SzUnknownDataSourceError",
        SzError::EnvironmentDestroyed(_) => ENVIRONMENT_DESTROYED,
        _ => "SzError",
    }
}

/// Describes an `sz_rust_sdk` error for Kotlin. A native code decides the class through the
/// generated `szerrors.json` table, as in every Senzing SDK.
pub fn describe(error: &SzError) -> Raised {
    let code = native_code(error);
    let class = match code.and_then(|c| i32::try_from(c).ok()) {
        Some(code) => classify(code).as_class_str(),
        None => class_of_variant(error),
    };
    let message = match error {
        SzError::Json(_) | SzError::StringConversion(_) => error.to_string(),
        _ => error.message().to_string(),
    };
    Raised {
        class,
        code,
        message,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_wrapped_code_parses_the_trailing_code() {
        assert_eq!(
            wrapped_code("Sz_init failed: Configuration error: bad (code: 2027)"),
            Some(2027)
        );
        assert_eq!(wrapped_code("no code here"), None);
        assert_eq!(wrapped_code("(code: abc)"), None);
        assert_eq!(wrapped_code("(code: 12"), None);
    }

    #[test]
    fn test_coded_errors_classify_by_table() {
        let raised = describe(&SzError::from_code_with_message(
            33,
            sz_rust_sdk::SzComponent::Engine,
        ));
        assert_eq!(raised.class, "SzNotFoundError");
        assert_eq!(raised.code, Some(33));
        let wrapped = describe(&SzError::unrecoverable(
            "Sz_init failed: Not found: gone (code: 33)",
        ));
        assert_eq!(wrapped.class, "SzNotFoundError");
        assert_eq!(wrapped.code, Some(33));
    }

    #[test]
    fn test_uncoded_errors_classify_by_variant() {
        let cases = [
            (SzError::bad_input("x"), "SzBadInputError"),
            (SzError::configuration("x"), "SzConfigurationError"),
            (SzError::database("x"), "SzDatabaseError"),
            (SzError::license("x"), "SzLicenseError"),
            (SzError::not_found("x"), "SzNotFoundError"),
            (SzError::retryable("x"), "SzRetryableError"),
            (SzError::unrecoverable("x"), "SzUnrecoverableError"),
            (SzError::not_initialized("x"), "SzNotInitializedError"),
            (
                SzError::database_connection_lost("x"),
                "SzDatabaseConnectionLostError",
            ),
            (SzError::database_transient("x"), "SzDatabaseTransientError"),
            (SzError::replace_conflict("x"), "SzReplaceConflictError"),
            (
                SzError::retry_timeout_exceeded("x"),
                "SzRetryTimeoutExceededError",
            ),
            (SzError::unhandled("x"), "SzUnhandledError"),
            (
                SzError::unknown_data_source("x"),
                "SzUnknownDataSourceError",
            ),
            (SzError::environment_destroyed("x"), ENVIRONMENT_DESTROYED),
            (SzError::unknown("x"), "SzError"),
            (SzError::ffi("x"), "SzError"),
        ];
        for (error, class) in cases {
            let raised = describe(&error);
            assert_eq!(raised.class, class, "{error}");
            assert_eq!(raised.code, None);
            assert_eq!(raised.message, "x");
        }
    }

    #[test]
    fn test_conversion_errors_keep_their_text() {
        let nul = std::ffi::CString::new("a\0b").unwrap_err();
        let raised = describe(&SzError::from(nul));
        assert_eq!(raised.class, "SzBadInputError");
        assert!(raised.message.contains("String conversion error"));
        let json = serde_json::from_str::<serde_json::Value>("{").unwrap_err();
        let raised = describe(&SzError::from(json));
        assert_eq!(raised.class, "SzError");
        assert!(raised.message.starts_with("JSON error"));
        assert_eq!(Raised::new("SzError", "m").message, "m");
    }
}
