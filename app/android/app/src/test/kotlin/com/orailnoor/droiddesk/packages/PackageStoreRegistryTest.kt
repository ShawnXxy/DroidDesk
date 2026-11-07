package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.EnvironmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PackageStoreRegistryTest {

    @Test
    fun oneRegistryInstanceOwnsOneCoordinatorAcrossActivityCalls() {
        val registry = PackageStoreRegistry()
        var creations = 0

        val first = registry.getOrCreate {
            creations++
            manager()
        }
        val second = registry.getOrCreate {
            creations++
            manager()
        }

        assertSame(first, second)
        assertEquals(1, creations)
    }

    private fun manager() = PackageStoreManager(
        adapters = listOf(FakeStore(EnvironmentKind.TERMUX_NATIVE)),
        activeKind = { EnvironmentKind.TERMUX_NATIVE },
        operations = PackageOperationCoordinator(),
    )
}
