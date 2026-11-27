package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.core.SzCoreLogging
import io.github.brianmacy.sz.testsupport.TestRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SzEnvironmentTest {
    /** Runs [body] with a fresh repository, removed afterwards. */
    private fun withRepository(body: (TestRepository) -> Unit) {
        val repository = TestRepository()
        try {
            body(repository)
        } finally {
            repository.remove()
        }
    }

    @Test
    fun `test_builder sets every property and normalizes blanks`() =
        withRepository { repository ->
            SzCoreEnvironment
                .newBuilder()
                .instanceName("  builder-test  ")
                .settings(repository.settings)
                .verboseLogging(false)
                .configId(repository.defaultConfigId)
                .build()
                .use { environment ->
                    assertEquals("builder-test", environment.instanceName)
                    assertEquals(repository.settings, environment.settings)
                    assertFalse(environment.verboseLogging)
                    assertEquals(repository.defaultConfigId, environment.configId)
                    assertEquals(repository.defaultConfigId, environment.getActiveConfigId())
                }

            SzCoreEnvironment().use { defaults ->
                assertEquals(SzCoreEnvironment.DEFAULT_INSTANCE_NAME, defaults.instanceName)
                assertEquals(SzCoreEnvironment.DEFAULT_SETTINGS, defaults.settings)
                assertFalse(defaults.verboseLogging)
            }
            SzCoreEnvironment(instanceName = " ", settings = "").use { defaults ->
                assertEquals(SzCoreEnvironment.DEFAULT_INSTANCE_NAME, defaults.instanceName)
                assertEquals(SzCoreEnvironment.DEFAULT_SETTINGS, defaults.settings)
                assertNull(defaults.configId)
            }
        }

    @Test
    fun `test_configId option starts the engine with that configuration`() =
        withRepository { repository ->
            val otherConfigId =
                repository.makeEnvironment().use { environment ->
                    val configManager = environment.getConfigManager()
                    val config = configManager.createConfig(repository.defaultConfigId)
                    config.registerDataSource("CONTACTS")
                    configManager.registerConfig(config.export())
                }
            SzCoreEnvironment(settings = repository.settings, configId = otherConfigId).use { environment ->
                assertEquals(otherConfigId, environment.configId)
                val engine = environment.getEngine()
                assertEquals(otherConfigId, environment.getActiveConfigId())
                // The repository's default is unchanged; only this engine uses the other one.
                assertEquals(repository.defaultConfigId, environment.getConfigManager().getDefaultConfigId())
                engine.addRecord("CONTACTS", "1", person("Ann Lee"))
                environment.getDiagnostic().getRepositoryInfo()
            }
            // An unregistered ID is reported when the engine or diagnostic starts.
            SzCoreEnvironment(settings = repository.settings, configId = 4242).use { environment ->
                assertEquals(4242, environment.configId)
                assertFailsWith<SzConfigurationException> { environment.getEngine() }
                assertFailsWith<SzConfigurationException> { environment.getDiagnostic() }
            }
        }

    @Test
    fun `test_only one environment may be active`() =
        withRepository { repository ->
            val first = repository.makeEnvironment()
            assertSame(first, SzCoreEnvironment.getActiveInstance())
            val error = assertFailsWith<SzException> { repository.makeEnvironment() }
            assertEquals(SzException::class, error::class)
            assertNull(error.errorCode)
            first.destroy()
            assertNull(SzCoreEnvironment.getActiveInstance())
            repository.makeEnvironment().use { second ->
                assertSame(second, SzCoreEnvironment.getActiveInstance())
            }
            assertNull(SzCoreEnvironment.getActiveInstance())
        }

    @Test
    fun `test_closing an environment destroys it`() =
        withRepository { repository ->
            val environment = repository.makeEnvironment()
            val engine = environment.use { it.getEngine() }
            assertTrue(environment.isDestroyed)
            assertNull(SzCoreEnvironment.getActiveInstance())
            assertFailsWith<SzEnvironmentDestroyedException> { engine.getStats() }
        }

    @Test
    fun `test_Java static entry points match the companion`() =
        withRepository { repository ->
            // @JvmStatic: Java calls SzCoreEnvironment.newBuilder() and getActiveInstance().
            val type = SzCoreEnvironment::class.java
            val builder = type.getMethod("newBuilder").invoke(null) as SzCoreEnvironment.Builder
            builder.settings(repository.settings).build().use { environment ->
                assertSame(environment, type.getMethod("getActiveInstance").invoke(null))
            }
            assertNull(type.getMethod("getActiveInstance").invoke(null))
        }

    @Test
    fun `test_subsystems are cached and fail after destroy`() {
        EngineFixture().use { fixture ->
            val environment = fixture.environment
            assertSame(environment.getEngine(), environment.getEngine())
            val product = environment.getProduct()
            assertSame(product, environment.getProduct())
            val configManager = environment.getConfigManager()
            assertSame(configManager, environment.getConfigManager())
            val diagnostic = environment.getDiagnostic()
            assertSame(diagnostic, environment.getDiagnostic())
            val config = configManager.createConfig()
            assertFalse(environment.isDestroyed)

            environment.destroy()
            environment.destroy()
            assertTrue(environment.isDestroyed)
            assertFailsWith<SzEnvironmentDestroyedException> { environment.getEngine() }
            assertFailsWith<SzEnvironmentDestroyedException> { environment.getProduct() }
            assertFailsWith<SzEnvironmentDestroyedException> { environment.getConfigManager() }
            assertFailsWith<SzEnvironmentDestroyedException> { environment.getDiagnostic() }
            assertFailsWith<SzEnvironmentDestroyedException> { environment.getActiveConfigId() }
            assertFailsWith<SzEnvironmentDestroyedException> { environment.reinitialize(1) }
            assertFailsWith<SzEnvironmentDestroyedException> { fixture.engine.getStats() }
            assertFailsWith<SzEnvironmentDestroyedException> { product.getVersion() }
            assertFailsWith<SzEnvironmentDestroyedException> { configManager.getDefaultConfigId() }
            assertFailsWith<SzEnvironmentDestroyedException> { diagnostic.getRepositoryInfo() }
            assertFailsWith<SzEnvironmentDestroyedException> { config.export() }
            val destroyed: Throwable = assertFailsWith<SzEnvironmentDestroyedException> { fixture.engine.primeEngine() }
            assertIs<IllegalStateException>(destroyed)
            assertFalse(SzException::class.java.isInstance(destroyed), "an SDK error, not an engine error")
        }
    }

    @Test
    fun `test_logging works before and after destroy`() =
        withRepository { repository ->
            val environment = repository.makeEnvironment()
            assertSame(SzCoreLogging, environment.getLogging())
            environment.destroy()
            val logging = environment.getLogging()
            assertSame(SzCoreLogging, logging)
            logging.setDefaultLogLevel(SzLogLevel.WARN)
            try {
                assertEquals(SzLogLevel.WARN, logging.getEffectiveLogLevel())
            } finally {
                logging.setDefaultLogLevel(SzLogLevel.ERROR)
            }
        }

    @Test
    fun `test_reinitialize switches the active configuration`() {
        EngineFixture().use { fixture ->
            val environment = fixture.environment
            val configManager = environment.getConfigManager()
            assertEquals(fixture.repository.defaultConfigId, environment.getActiveConfigId())

            val config = configManager.createConfig(fixture.repository.defaultConfigId)
            config.registerDataSource("CONTACTS")
            val newConfigId = configManager.registerConfig(config.export())
            environment.getDiagnostic()
            environment.reinitialize(newConfigId)
            assertEquals(newConfigId, environment.getActiveConfigId())
            assertEquals(newConfigId, environment.configId)
            fixture.engine.addRecord("CONTACTS", "1", person("Ann Lee"))

            // A failed reinitialize keeps the previous configuration.
            assertFailsWith<SzConfigurationException> { environment.reinitialize(4242) }
            assertEquals(newConfigId, environment.configId)
            assertEquals(newConfigId, environment.getActiveConfigId())
        }
    }

    @Test
    fun `test_reinitialize before the engine exists starts it and checks the ID`() =
        withRepository { repository ->
            repository.makeEnvironment().use { environment ->
                assertFailsWith<SzConfigurationException> { environment.reinitialize(4242) }
                assertNull(environment.configId)
                environment.reinitialize(repository.defaultConfigId)
                assertEquals(repository.defaultConfigId, environment.configId)
                assertEquals(repository.defaultConfigId, environment.getActiveConfigId())
                environment.getDiagnostic().getRepositoryInfo()
            }
        }

    @Test
    fun `test_invalid settings fail with the engine's error`() {
        SzCoreEnvironment(settings = """{"PIPELINE":{}}""").use { environment ->
            val error = assertFailsWith<SzException> { environment.getEngine() }
            assertNotNull(error.errorCode, error.toString())
            assertFailsWith<SzException> { environment.getConfigManager() }
            assertFailsWith<SzException> { environment.getDiagnostic() }
        }
    }
}
