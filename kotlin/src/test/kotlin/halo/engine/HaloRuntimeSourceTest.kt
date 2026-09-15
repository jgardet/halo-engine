package halo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HaloRuntimeSourceTest {

    @Test
    fun versionOfParsesRuntimeVersionConstant() {
        val source = "local RUNTIME_VERSION = '3.1'\nprint('x')"
        assertEquals("3.1", HaloRuntimeSource.versionOf(source))
    }

    @Test
    fun versionOfAcceptsDoubleQuotesAndLooseSpacing() {
        assertEquals("9.9-beta", HaloRuntimeSource.versionOf("local RUNTIME_VERSION=\"9.9-beta\""))
    }

    @Test
    fun versionOfReturnsNullWhenAbsent() {
        assertNull(HaloRuntimeSource.versionOf("print('no version here')"))
    }
}
