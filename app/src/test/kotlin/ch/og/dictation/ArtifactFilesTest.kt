package ch.og.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactFilesTest {
    @Test fun runtimeInventoryMatchesFrozenPythonIdentity() {
        // Independent fixture: Python json.dumps(sort_keys=True, separators=(',', ':')).
        assertEquals("99d4d9ed446a0c93f7a8752e1d0727511fed0bc4071629a2144adbc677b48a6c",
            ArtifactFiles.inventoryHash(mapOf("lib/arm64-v8a/runtime.so" to "abc")))
    }
    @Test fun inventoryUsesFrozenAsciiEscapesAndSortedKeys() {
        assertEquals("cddd31868f0f33a4d6d24195b30da497d4733f9f8dccf3a03b035f3da536fc52",
            ArtifactFiles.inventoryHash(linkedMapOf("z/é" to "quote\"\n\\", "a" to "\t")))
    }
}
