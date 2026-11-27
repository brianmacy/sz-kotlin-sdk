#!/bin/sh
# Cargo runner for test binaries: puts the Senzing runtime's lib directory ($SENZING_DIR/lib) on
# the loader path. Set here, not in the caller, because macOS strips DYLD_* variables when a
# protected binary (such as /bin/sh or /usr/bin/env) sits between the caller and the test.
set -eu
: "${SENZING_DIR:?set SENZING_DIR to the Senzing runtime er directory}"
DYLD_LIBRARY_PATH="$SENZING_DIR/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
LD_LIBRARY_PATH="$SENZING_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export DYLD_LIBRARY_PATH LD_LIBRARY_PATH
exec "$@"
