package ch.og.dictation

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DictionaryStoreTest {
    private fun fixture(test: (DictionaryStore, File, File) -> Unit) {
        val root = Files.createTempDirectory("dictionary-test").toFile()
        try {
            val source = File(root, "accepted.txt").apply { writeText("Zürich HB\nMatterhorn\n") }
            test(DictionaryStore(File(root, "user"), source), source, root)
        } finally { root.deleteRecursively() }
    }

    @Test fun addEditDeleteAndRestartDoNotChangeTheAcceptedSource() = fixture { store, source, root ->
        val original = store.read()
        val added = store.prepare(original.entries + "Grüezi mitenand")
        assertEquals(original, store.read()) // preparation cannot publish
        store.commit(added, original)
        val reopened = DictionaryStore(File(root, "user"), source)
        assertEquals(added, reopened.read())
        val edited = reopened.prepare(added.entries.map { if (it == "Grüezi mitenand") "Bärlauch" else it })
        reopened.commit(edited, added)
        val removed = reopened.prepare(edited.entries.filter { it != "Bärlauch" })
        reopened.commit(removed, edited)
        assertEquals(original.entries, reopened.read().entries)
        assertEquals("Zürich HB\nMatterhorn\n", source.readText())
        added.verify() // a take pinned to an older revision still has its native input
    }

    @Test fun emptyIsAnExplicitPersistedRevisionRatherThanReseedingDefaults() = fixture { store, _, _ ->
        val empty = store.prepare(emptyList())
        store.commit(empty, store.read())
        assertTrue(store.read().edited)
        assertTrue(store.read().entries.isEmpty())
        assertEquals("", store.read().file.readText())
    }

    @Test fun failedValidationConflictsAndCorruptionPreservePreviousUsableState() = fixture { store, _, _ ->
        val original = store.read()
        for (bad in listOf(listOf(""), listOf("Zürich", "zürich"), listOf("one\ntwo"), listOf("boost:10"), listOf("# comment"))) {
            assertThrows(IllegalArgumentException::class.java) { store.prepare(bad) }
            assertEquals(original, store.read())
        }
        val saved = store.prepare(listOf("Bärlauch"))
        store.commit(saved, original)
        assertThrows(IllegalArgumentException::class.java) { store.commit(store.prepare(listOf("Other")), original) }
        assertEquals(saved, store.read())
        val damaged = store.prepare(listOf("Next"))
        damaged.file.writeText("tampered\n")
        assertThrows(IllegalArgumentException::class.java) { store.commit(damaged, saved) }
        assertEquals(saved, store.read())
    }

    @Test fun normalisesUnicodeAndSpacingWithoutChoosingALanguage() {
        assertEquals(listOf("Zürich HB", "English phrase", "Bärlauch"),
            DictionaryStore.validate(listOf(" Zu\u0308rich   HB ", "English phrase", "Bärlauch")))
        assertThrows(IllegalArgumentException::class.java) { DictionaryStore.validate(listOf("Zürich", "Zu\u0308rich")) }
    }
}
