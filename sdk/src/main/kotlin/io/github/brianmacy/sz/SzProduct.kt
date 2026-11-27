package io.github.brianmacy.sz

/** Version and license information, mirroring the C# `SzProduct` interface. */
public interface SzProduct {
    /** Returns the license details as JSON. */
    public fun getLicense(): String

    /** Returns the version and build details as JSON. */
    public fun getVersion(): String
}
