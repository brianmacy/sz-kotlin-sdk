package io.github.brianmacy.sz.core

import io.github.brianmacy.sz.SzDiagnostic
import io.github.brianmacy.sz.SzProduct
import io.github.brianmacy.sz.internal.EnvironmentState
import io.github.brianmacy.sz.internal.Native

/** The [SzProduct] over the native engine. Obtain it from [SzCoreEnvironment.getProduct]. */
public class SzCoreProduct internal constructor(
    internal val state: EnvironmentState,
) : SzProduct {
    override fun getLicense(): String = state.execute { Native.getLicense() }

    override fun getVersion(): String = state.execute { Native.getVersion() }
}

/** The [SzDiagnostic] over the native engine. Obtain it from [SzCoreEnvironment.getDiagnostic]. */
public class SzCoreDiagnostic internal constructor(
    internal val state: EnvironmentState,
) : SzDiagnostic {
    override fun getRepositoryInfo(): String = state.execute { Native.getRepositoryInfo() }

    override fun checkRepositoryPerformance(secondsToRun: Int): String =
        state.execute { Native.checkRepositoryPerformance(secondsToRun.toLong()) }

    override fun purgeRepository(): Unit = state.execute { Native.purgeRepository() }

    override fun getFeature(featureId: Long): String = state.execute { Native.getFeature(featureId) }
}
