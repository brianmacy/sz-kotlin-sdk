package io.github.brianmacy.sz.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The response of `SzProduct.getVersion`.
 *
 * @property productName The product name.
 * @property version The version, for example `4.5.0`.
 * @property buildVersion The full build version.
 * @property buildDate The build date.
 * @property buildNumber The build number.
 * @property compatibilityVersion The configuration version the build is compatible with.
 * @property schemaVersion The database schema versions the build supports.
 */
@Serializable
public data class SzVersionInfo(
    @SerialName("PRODUCT_NAME") public val productName: String,
    @SerialName("VERSION") public val version: String,
    @SerialName("BUILD_VERSION") public val buildVersion: String,
    @SerialName("BUILD_DATE") public val buildDate: String,
    @SerialName("BUILD_NUMBER") public val buildNumber: String,
    @SerialName("COMPATIBILITY_VERSION") public val compatibilityVersion: CompatibilityVersion? = null,
    @SerialName("SCHEMA_VERSION") public val schemaVersion: SchemaVersion? = null,
) {
    /**
     * The configuration version a build is compatible with.
     *
     * @property configVersion The configuration version.
     */
    @Serializable
    public data class CompatibilityVersion(
        @SerialName("CONFIG_VERSION") public val configVersion: String,
    )

    /**
     * The database schema versions a build supports.
     *
     * @property engineSchemaVersion The schema version of the engine.
     * @property minimumRequiredSchemaVersion The oldest schema version the engine accepts.
     * @property maximumRequiredSchemaVersion The newest schema version the engine accepts.
     */
    @Serializable
    public data class SchemaVersion(
        @SerialName("ENGINE_SCHEMA_VERSION") public val engineSchemaVersion: String,
        @SerialName("MINIMUM_REQUIRED_SCHEMA_VERSION") public val minimumRequiredSchemaVersion: String,
        @SerialName("MAXIMUM_REQUIRED_SCHEMA_VERSION") public val maximumRequiredSchemaVersion: String,
    )
}

/**
 * The response of `SzProduct.getLicense`. Unlike the other engine documents, its keys are
 * camelCase.
 *
 * @property customer The licensed customer.
 * @property contract The contract.
 * @property issueDate When the license was issued.
 * @property licenseType The license type.
 * @property licenseLevel The license level.
 * @property billing The billing period.
 * @property expireDate When the license expires.
 * @property recordLimit The maximum number of records the license allows.
 */
@Serializable
public data class SzLicenseInfo(
    @SerialName("customer") public val customer: String? = null,
    @SerialName("contract") public val contract: String? = null,
    @SerialName("issueDate") public val issueDate: String? = null,
    @SerialName("licenseType") public val licenseType: String? = null,
    @SerialName("licenseLevel") public val licenseLevel: String? = null,
    @SerialName("billing") public val billing: String? = null,
    @SerialName("expireDate") public val expireDate: String? = null,
    @SerialName("recordLimit") public val recordLimit: Long? = null,
)

/**
 * The response of `SzConfigManager.getConfigRegistry`.
 *
 * @property configs The registered configurations.
 */
@Serializable
public data class SzConfigRegistry(
    @SerialName("CONFIGS") public val configs: List<Entry>,
) {
    /**
     * A registered configuration.
     *
     * @property configId The configuration ID.
     * @property configComments The comments registered with the configuration.
     * @property createdDate When the configuration was registered.
     */
    @Serializable
    public data class Entry(
        @SerialName("CONFIG_ID") public val configId: Long,
        @SerialName("CONFIG_COMMENTS") public val configComments: String? = null,
        @SerialName("SYS_CREATE_DT") public val createdDate: String? = null,
    )
}

/**
 * The response of `SzConfig.getDataSourceRegistry`.
 *
 * @property dataSources The registered data sources.
 */
@Serializable
public data class SzDataSourceRegistry(
    @SerialName("DATA_SOURCES") public val dataSources: List<Entry>,
) {
    /** The registered data source codes. */
    public val dataSourceCodes: List<String>
        get() = dataSources.map { it.dataSourceCode }

    /**
     * A registered data source.
     *
     * @property dataSourceId The data source ID.
     * @property dataSourceCode The data source code.
     */
    @Serializable
    public data class Entry(
        @SerialName("DSRC_ID") public val dataSourceId: Long,
        @SerialName("DSRC_CODE") public val dataSourceCode: String,
    )
}
