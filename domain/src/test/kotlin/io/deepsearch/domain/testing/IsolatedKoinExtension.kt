package io.deepsearch.domain.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.core.KoinApplication
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.koinApplication

/**
 * JUnit 5 extension that creates an isolated [KoinApplication] per test method.
 *
 * Unlike [org.koin.test.junit5.KoinTestExtension] this never touches
 * [org.koin.mp.KoinPlatformTools.defaultContext], so multiple tests can
 * run concurrently without container conflicts.
 *
 * The test class must extend [IsolatedKoinTest]; the extension sets
 * [IsolatedKoinTest.testKoin] before each test and tears it down afterwards.
 *
 * ```
 * class MyTest : IsolatedKoinTest() {
 *     @JvmField
 *     @RegisterExtension
 *     val koinExtension = IsolatedKoinExtension.create { modules(myTestModule) }
 *
 *     private val service by inject<IMyService>()
 * }
 * ```
 */
class IsolatedKoinExtension private constructor(
    private val appDeclaration: KoinAppDeclaration,
) : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        val testInstance = context.requiredTestInstance
        if (testInstance is IsolatedKoinTest) {
            val koinApp = koinApplication(appDeclaration)
            koinApp.createEagerInstances()
            testInstance.testKoin = koinApp.koin
            context.getStore(namespace()).put(KOIN_APP_KEY, CloseableKoinApp(koinApp))
        }
    }

    override fun afterEach(context: ExtensionContext) {
        context.getStore(namespace())
            .remove(KOIN_APP_KEY, CloseableKoinApp::class.java)
            ?.close()
    }

    private fun namespace() = ExtensionContext.Namespace.create(this)

    private class CloseableKoinApp(
        private val app: KoinApplication,
    ) : AutoCloseable {
        override fun close() {
            app.close()
        }
    }

    companion object {
        private const val KOIN_APP_KEY = "koinApp"

        fun create(appDeclaration: KoinAppDeclaration): IsolatedKoinExtension {
            return IsolatedKoinExtension(appDeclaration)
        }
    }
}
