package io.github.brianmacy.sz

/** Repository diagnostics, mirroring the C# `SzDiagnostic` interface. */
public interface SzDiagnostic {
    /** Returns details about the repository's data stores as JSON. */
    public fun getRepositoryInfo(): String

    /** Runs a write/read benchmark for [secondsToRun] seconds and returns the results as JSON. */
    public fun checkRepositoryPerformance(secondsToRun: Int): String

    /**
     * Deletes all entity and record data from the repository.
     *
     * Irreversible. Intended for tests and demonstrations.
     */
    public fun purgeRepository()

    /**
     * Returns the details of a feature as JSON (an unsupported, undocumented native function,
     * kept for parity with the C# SDK).
     */
    public fun getFeature(featureId: Long): String
}
