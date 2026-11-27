package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.models.SzConfigRegistry
import io.github.brianmacy.sz.models.SzDataSourceRegistry
import io.github.brianmacy.sz.models.SzLicenseInfo
import io.github.brianmacy.sz.models.SzVersionInfo
import io.github.brianmacy.sz.testsupport.TestRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SzConfigTest {
    @Test
    fun `test_register, persist and verify a data source`() {
        EngineFixture(dataSources = emptyList()).use { fixture ->
            val configManager = fixture.environment.getConfigManager()

            // Step 1: register.
            val config = configManager.createConfig()
            val registered = config.registerDataSource("CONTACTS")
            assertTrue(registered.contains("DSRC_ID"), registered)
            // Step 2: persist.
            val configId = configManager.setDefaultConfig(config.export())
            assertEquals(configId, configManager.getDefaultConfigId())
            // Step 3: verify persistence.
            val reloaded = configManager.createConfig(configId)
            val registry = reloaded.getDataSourceRegistry(SzDataSourceRegistry.serializer())
            assertTrue("CONTACTS" in registry.dataSourceCodes, registry.toString())
            // Step 4: verify the engine can use it.
            fixture.environment.reinitialize(configId)
            fixture.engine.addRecord("CONTACTS", "1", person("Ann Lee"))
            assertTrue(fixture.engine.getRecord("CONTACTS", "1").contains("CONTACTS"))
        }
    }

    @Test
    fun `test_unregister a data source`() {
        EngineFixture().use { fixture ->
            val configManager = fixture.environment.getConfigManager()
            val config = configManager.createConfig(fixture.repository.defaultConfigId)
            assertTrue(config.getDataSourceRegistry().contains("WATCHLIST"))
            config.unregisterDataSource("WATCHLIST")
            assertFalse(config.getDataSourceRegistry().contains("WATCHLIST"))
            // Unregistering an unknown data source is accepted by the engine.
            config.unregisterDataSource("NOPE")
            // The definition round-trips through createConfig(configDefinition).
            val copy = configManager.createConfig(config.export())
            assertEquals(config.getDataSourceRegistry(), copy.getDataSourceRegistry())
        }
    }

    @Test
    fun `test_template configuration has the default data sources`() {
        EngineFixture().use { fixture ->
            val config = fixture.environment.getConfigManager().createConfig()
            val registry = config.getDataSourceRegistry(SzDataSourceRegistry.serializer())
            assertEquals(setOf("TEST", "SEARCH"), registry.dataSourceCodes.toSet())
            assertTrue(registry.dataSources.all { it.dataSourceId > 0 }, registry.toString())
        }
    }

    @Test
    fun `test_configuration registry, comments and default ID`() {
        EngineFixture().use { fixture ->
            val configManager = fixture.environment.getConfigManager()
            val config = configManager.createConfig()
            config.registerDataSource("ALPHA")
            val commented = configManager.registerConfig(config.export(), "mine")
            // The engine returns the existing ID for an identical definition.
            assertEquals(commented, configManager.registerConfig(config.export()))
            config.registerDataSource("BETA")
            val generated = configManager.registerConfig(config.export())
            val registry = configManager.getConfigRegistry(SzConfigRegistry.serializer())
            val comments = registry.configs.associate { it.configId to it.configComments }
            assertEquals("mine", comments[commented])
            assertEquals("Data Sources: ALPHA, BETA", comments[generated])
            assertTrue(registry.configs.all { it.createdDate != null }, registry.toString())
            assertTrue(configManager.getConfigRegistry().contains("CONFIGS"))

            configManager.setDefaultConfigId(commented)
            assertEquals(commented, configManager.getDefaultConfigId())
            configManager.replaceDefaultConfigId(commented, generated)
            assertEquals(generated, configManager.getDefaultConfigId())
            config.registerDataSource("GAMMA")
            val explicit = configManager.setDefaultConfig(config.export(), "explicit")
            assertEquals(explicit, configManager.getDefaultConfigId())
        }
    }

    @Test
    fun `test_generated comments describe the data sources`() {
        EngineFixture().use { fixture ->
            val configManager = fixture.environment.getConfigManager()
            val config = configManager.createConfig()

            fun registeredComment(): String? {
                val configId = configManager.registerConfig(config.export())
                return configManager
                    .getConfigRegistry(SzConfigRegistry.serializer())
                    .configs
                    .single { it.configId == configId }
                    .configComments
            }
            assertEquals("Data Sources: [ ONLY DEFAULT ]", registeredComment())
            config.unregisterDataSource("SEARCH")
            assertEquals("Data Sources: [ SOME DEFAULT (TEST) ]", registeredComment())
            config.unregisterDataSource("TEST")
            assertEquals("Data Sources: [ NONE ]", registeredComment())
            // A definition that is not a configuration gets no comment and is rejected by the engine.
            assertFailsWith<SzException> { configManager.registerConfig("not a configuration") }
        }
    }

    @Test
    fun `test_configuration errors map to their classes`() {
        EngineFixture().use { fixture ->
            val configManager = fixture.environment.getConfigManager()
            val conflict = assertFailsWith<SzReplaceConflictException> { configManager.replaceDefaultConfigId(12_345L, 1L) }
            assertEquals(7245L, conflict.errorCode, conflict.toString())
            assertFailsWith<SzConfigurationException> { configManager.createConfig(4242L) }
            assertFailsWith<SzConfigurationException> { configManager.createConfig("{}") }
            assertFailsWith<SzException> { configManager.setDefaultConfigId(4242L) }
        }
    }

    @Test
    fun `test_a new repository's default configuration is the one it registered`() {
        val repository = TestRepository(dataSources = emptyList())
        try {
            repository.makeEnvironment().use { environment ->
                assertEquals(repository.defaultConfigId, environment.getConfigManager().getDefaultConfigId())
            }
        } finally {
            repository.remove()
        }
    }

    @Test
    fun `test_version and license decode`() {
        EngineFixture().use { fixture ->
            val product = fixture.environment.getProduct()
            val version = product.getVersion(SzVersionInfo.serializer())
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(version.version), version.toString())
            assertTrue(version.productName.isNotEmpty())
            assertTrue(version.compatibilityVersion?.configVersion?.isNotEmpty() == true, version.toString())
            assertTrue(version.schemaVersion?.engineSchemaVersion?.isNotEmpty() == true, version.toString())
            assertTrue(product.getVersion().contains(version.buildVersion))
            val license = product.getLicense(SzLicenseInfo.serializer())
            assertTrue((license.recordLimit ?: 0) > 0, license.toString())
            assertTrue(product.getLicense().contains("licenseType"))
        }
    }

    @Test
    fun `test_repository info, performance, feature and purge`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val diagnostic = fixture.environment.getDiagnostic()
            val info = diagnostic.getRepositoryInfo()
            assertTrue(info.contains("sqlite3"), info)
            val performance = diagnostic.checkRepositoryPerformance(1)
            assertTrue(performance.contains("numRecordsInserted"), performance)
            // Feature 1 is the first record's name (keyed LIB_FEAT_ID or LIB_FEAT_REF, by runtime).
            val feature = diagnostic.getFeature(1L)
            assertTrue(Regex("\"LIB_FEAT_(ID|REF)\":1\\b").containsMatchIn(feature), feature)
            assertTrue(feature.contains("\"FTYPE_CODE\":\"NAME\""), feature)
            assertFailsWith<SzException> { diagnostic.getFeature(-5L) }
            diagnostic.purgeRepository()
            assertFailsWith<SzNotFoundException> { fixture.engine.getEntity("CUSTOMERS", "1001") }
        }
    }

    @Test
    fun `test_diagnostic initializes with a pinned configuration`() {
        val repository = TestRepository()
        try {
            SzCoreEnvironment(settings = repository.settings, configId = repository.defaultConfigId).use { environment ->
                val info = environment.getDiagnostic().getRepositoryInfo()
                assertTrue(info.contains("dataStores"), info)
            }
        } finally {
            repository.remove()
        }
    }
}
