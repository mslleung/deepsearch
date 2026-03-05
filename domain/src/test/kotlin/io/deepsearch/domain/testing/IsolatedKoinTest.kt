package io.deepsearch.domain.testing

import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.qualifier.Qualifier

/**
 * Drop-in replacement for [org.koin.test.KoinTest] that resolves dependencies from
 * an isolated [Koin] instance instead of [org.koin.mp.KoinPlatformTools.defaultContext].
 *
 * This enables safe parallel test execution because each test owns its own
 * Koin container with no shared global state.
 *
 * Pair with [IsolatedKoinExtension] for automatic per-test lifecycle, or set
 * [testKoin] manually in `@BeforeAll` for [org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS] tests.
 */
abstract class IsolatedKoinTest : KoinComponent {

    lateinit var testKoin: Koin

    override fun getKoin(): Koin = testKoin

    inline fun <reified T : Any> inject(
        qualifier: Qualifier? = null,
        mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
    ): Lazy<T> = lazy(mode) { getKoin().get(qualifier) }
}
