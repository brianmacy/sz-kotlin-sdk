package io.github.brianmacy.sz.testsupport

/**
 * The repository an example program runs against.
 *
 * When `SENZING_ENGINE_CONFIGURATION_JSON` is set, examples use that repository (its default
 * configuration must already register the data sources they load). Otherwise they create a
 * throwaway SQLite repository, deleted by [remove].
 */
public class ExampleRepository(
    dataSources: List<String> = Truthset.dataSources,
) {
    private val temporary: TestRepository?

    /** The engine settings JSON. */
    public val settings: String

    init {
        val configured = System.getenv("SENZING_ENGINE_CONFIGURATION_JSON")?.takeIf { it.isNotBlank() }
        if (configured != null) {
            temporary = null
            settings = configured
        } else {
            val repository = TestRepository(dataSources)
            temporary = repository
            settings = repository.settings
        }
    }

    /** Deletes the throwaway repository, if one was created. */
    public fun remove() {
        temporary?.remove()
    }
}

/** An example failure: examples exit non-zero when their result is wrong. */
public class ExampleFailure(
    message: String,
) : Exception(message)
