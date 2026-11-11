package ch.og.dictation

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HistoryStoreTest {
    @Test fun savedCountIgnoresTemporaryFilesAndTombstones() {
        val root = Files.createTempDirectory("history-count").toFile()
        val store = HistoryStore(root, HistoryOwner())
        assertEquals(0, store.savedCount())
        val a = "b3de7a72-84ce-4721-8412-497b90c09315"
        val b = "9a67d378-1c8c-405e-bdb1-c113df823988"
        store.save(HistoryEntry(a, 1, "one"))
        store.save(HistoryEntry(b, 2, "two"))
        java.io.File(root, "076b1f86-154f-4307-9e3b-5d8935dc6e7e.tmp").writeText("partial")
        assertEquals(2, store.savedCount())
        store.delete(a)
        assertEquals(1, store.savedCount())
    }

    @Test fun clearSnapshotFreezesKnownAndActiveIdsAtAcceptance() {
        val owner = HistoryOwner()
        val saved = "b3de7a72-84ce-4721-8412-497b90c09315"
        val emptyActive = "9a67d378-1c8c-405e-bdb1-c113df823988"
        val startedAfterAcceptance = "076b1f86-154f-4307-9e3b-5d8935dc6e7e"
        owner.remember(setOf(saved))
        owner.register(emptyActive)

        val accepted = owner.clearSnapshot()
        owner.register(startedAfterAcceptance)

        assertEquals(setOf(saved, emptyActive), accepted)
        assertEquals(setOf(saved, emptyActive, startedAfterAcceptance), owner.clearSnapshot())
    }

    @Test fun clearIncludesTakeFinishedAfterInventoryReadBeforeItsFirstCheckpoint() {
        val root = Files.createTempDirectory("history-inventory-race").toFile()
        val owner = HistoryOwner()
        val saved = "b3de7a72-84ce-4721-8412-497b90c09315"
        val finishing = "9a67d378-1c8c-405e-bdb1-c113df823988"
        val store = HistoryStore(root, owner)
        try {
            store.save(HistoryEntry(saved, 1, "earlier"))
            owner.register(finishing)
            owner.remember(store.clearableIds())
            store.save(HistoryEntry(finishing, 2, "finished"))
            owner.remove(finishing)

            store.clear(owner.clearSnapshot())
            assertTrue(store.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun clearIncludesTakeFinishedAfterInventoryWasReadBeforeItWasRemembered() {
        val owner = HistoryOwner()
        val earlier = "b3de7a72-84ce-4721-8412-497b90c09315"
        val finished = "9a67d378-1c8c-405e-bdb1-c113df823988"
        owner.register(finished)
        val inventoryReadBeforeCheckpoint = setOf(earlier)
        owner.remove(finished)
        owner.remember(inventoryReadBeforeCheckpoint)
        assertEquals(setOf(earlier, finished), owner.clearSnapshot())
        owner.remember(setOf(earlier, finished))
        owner.remember(setOf(earlier))
        assertEquals(setOf(earlier), owner.clearSnapshot())
    }

    @Test fun persistedIdInventoryRefreshDropsCopiesThatWereFullyCleared() {
        val owner = HistoryOwner()
        val old = java.util.UUID.randomUUID().toString()
        val current = java.util.UUID.randomUUID().toString()
        owner.remember(setOf(old))
        owner.remember(setOf(current))
        assertEquals(setOf(current), owner.clearSnapshot())
    }

    @Test fun historyIoRetryKeepsTheAcceptedIdsPerRoot() {
        val root = Files.createTempDirectory("history-clear-operation").toFile()
        val otherRoot = Files.createTempDirectory("history-clear-operation-other").toFile()
        try {
            val accepted = setOf("b3de7a72-84ce-4721-8412-497b90c09315", "9a67d378-1c8c-405e-bdb1-c113df823988")
            val startedLater = setOf("076b1f86-154f-4307-9e3b-5d8935dc6e7e")
            val callerSet = accepted.toMutableSet()

            val first = HistoryIo.acceptClear(root, callerSet)!!
            callerSet.addAll(startedLater)
            val duplicate = HistoryIo.acceptClear(root, startedLater)
            HistoryIo.finishClear(root, first.token, succeeded = false)
            assertNull(duplicate)
            assertNull(HistoryIo.acceptClear(root, startedLater))
            val retry = HistoryIo.retryClear(root)!!

            assertEquals(accepted, first.ids)
            try {
                @Suppress("UNCHECKED_CAST")
                (first.ids as MutableSet<String>).addAll(startedLater)
                fail("accepted target set must be immutable")
            } catch (_: UnsupportedOperationException) { }
            assertEquals(accepted, retry.ids)
            assertEquals(first.token, retry.token)
            assertEquals(HistoryClearStatus.RUNNING, retry.status)
            assertNull(HistoryIo.retryClear(root))
            HistoryIo.finishClear(root, retry.token, succeeded = true)
            val nextOperation = HistoryIo.acceptClear(root, startedLater)!!
            assertEquals(startedLater, nextOperation.ids)
            assertNotEquals(first.token, nextOperation.token)
            val otherRootOperation = HistoryIo.acceptClear(otherRoot, startedLater)!!
            assertEquals(startedLater, otherRootOperation.ids)
            HistoryIo.finishClear(root, nextOperation.token, succeeded = false)
            assertEquals(HistoryClearStatus.FAILED, HistoryIo.currentClear(root)?.status)
            assertEquals(HistoryClearStatus.RUNNING, HistoryIo.currentClear(otherRoot)?.status)
            HistoryIo.finishClear(otherRoot, otherRootOperation.token, succeeded = true)
        } finally { root.deleteRecursively(); otherRoot.deleteRecursively() }
    }

    @Test fun acceptedClearExcludesTakeRegisteredWhileItsIoTaskIsBlocked() {
        val root = Files.createTempDirectory("history-clear-boundary").toFile()
        val owner = HistoryOwner()
        val saved = "b3de7a72-84ce-4721-8412-497b90c09315"
        val active = "9a67d378-1c8c-405e-bdb1-c113df823988"
        val later = "076b1f86-154f-4307-9e3b-5d8935dc6e7e"
        val store = HistoryStore(root, owner)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        try {
            store.save(HistoryEntry(saved, 1, "saved before acceptance"))
            owner.remember(store.clearableIds())
            owner.register(active)

            HistoryIo.executor.execute { started.countDown(); check(unblock.await(10, TimeUnit.SECONDS)) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val operation = HistoryIo.acceptClear(root, owner.clearSnapshot())!!
            owner.register(later)
            HistoryIo.executor.execute {
                val succeeded = runCatching { store.clear(operation.ids) }.isSuccess
                HistoryIo.finishClear(root, operation.token, succeeded)
                cleared.countDown()
            }
            unblock.countDown()
            assertTrue(cleared.await(10, TimeUnit.SECONDS))

            assertEquals(setOf(saved, active), operation.ids)
            assertNull(store.get(saved))
            assertFalse(store.checkpoint(HistoryEntry(active, 2, "late checkpoint", revision = 1)))
            store.save(HistoryEntry(later, 3, "take begun after acceptance"))
            assertEquals(listOf(HistoryEntry(later, 3, "take begun after acceptance")), store.list())
            assertEquals(HistoryCompletion.RECORDING, owner.state(active))
            assertEquals(HistoryClearStatus.SUCCEEDED, HistoryIo.currentClear(root)?.status)
        } finally {
            unblock.countDown()
            owner.remove(active); owner.remove(later)
            root.deleteRecursively()
        }
    }

    @Test fun earlierRejectedRecordRemainsReadable() {
        val root = Files.createTempDirectory("history-v1").toFile()
        try {
            val id = "b3de7a72-84ce-4721-8412-497b90c09315"
            java.io.DataOutputStream(root.resolve("$id.history").outputStream()).use {
                it.writeInt(1); it.writeLong(123); it.writeInt(4); it.write("text".toByteArray())
            }
            assertEquals(HistoryEntry(id, 123, "text", HistoryDelivery.REJECTED), HistoryStore(root).get(id))
        } finally { root.deleteRecursively() }
    }
    @Test fun failedStartupCleanupDoesNotPreventRetryOrChangeStoredData() {
        val root = Files.createTempDirectory("history-startup-failure").toFile()
        try {
            val entry = HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 123, "synthetic existing data")
            HistoryStore(root).save(entry)
            val obstruction = root.resolve("abandoned.tmp").apply { mkdir(); resolve("partial").writeText("synthetic") }
            HistoryIo.start(root).get(10, TimeUnit.SECONDS)
            try {
                HistoryIo.executor.submit<HistoryStore> { HistoryIo.store(root) }.get(10, TimeUnit.SECONDS)
                fail("expected read error")
            } catch (expected: java.util.concurrent.ExecutionException) {
                assertTrue(expected.cause is IllegalStateException)
            }
            assertEquals(entry, HistoryStore(root).get(entry.id))
            obstruction.deleteRecursively()
            val reopened = HistoryIo.executor.submit<HistoryStore> { HistoryIo.store(root) }.get(10, TimeUnit.SECONDS)
            assertEquals(entry, reopened.get(entry.id))
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectedTextSurvivesReopenOnlyOnceAndCanBeDeleted() {
        val root = Files.createTempDirectory("history-test").toFile()
        try {
            val entry = HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 123L, "Synthetic full text\nZweite Zeile")
            HistoryStore(root).save(entry)
            HistoryStore(root).save(entry)
            val reopened = HistoryStore(root)
            assertEquals(listOf(entry), reopened.list())
            reopened.delete(entry.id)
            assertTrue(HistoryStore(root).list().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun clearSuppressesSavedAndUncheckpointedTakesButKeepsLaterTake() {
        val root = Files.createTempDirectory("history-clear").toFile()
        try {
            val saved = "b3de7a72-84ce-4721-8412-497b90c09315"
            val activeWithoutCheckpoint = "9a67d378-1c8c-405e-bdb1-c113df823988"
            val later = "076b1f86-154f-4307-9e3b-5d8935dc6e7e"
            val store = HistoryStore(root, HistoryOwner())
            store.save(HistoryEntry(saved, 10, "already saved"))

            store.clear(setOf(saved, activeWithoutCheckpoint))
            val reopened = HistoryStore(root, HistoryOwner())
            assertFalse(reopened.checkpoint(HistoryEntry(activeWithoutCheckpoint, 11, "late checkpoint", revision = 1)))
            reopened.save(HistoryEntry(activeWithoutCheckpoint, 12, "late final save"))
            reopened.save(HistoryEntry(later, 13, "new take"))

            assertEquals(listOf(HistoryEntry(later, 13, "new take")), reopened.list())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedClearSuppressesEveryAcceptedTakeAndRetriesTheOriginalSet() {
        val root = Files.createTempDirectory("history-clear-retry").toFile()
        try {
            val alreadySaved = "076b1f86-154f-4307-9e3b-5d8935dc6e7e"
            val blocked = "9a67d378-1c8c-405e-bdb1-c113df823988"
            val activeWithoutCheckpoint = "f0f54f22-23af-42d3-9fea-5e90a1a683fe"
            val store = HistoryStore(root, HistoryOwner())
            store.save(HistoryEntry(alreadySaved, 10, "saved before clear"))
            store.save(HistoryEntry(blocked, 11, "text being cleared"))
            root.resolve("$alreadySaved.bak").writeText("synthetic backup")
            root.resolve("$blocked.tmp").apply { mkdir(); resolve("partial").writeText("synthetic") }
            val acceptedIds = setOf(alreadySaved, blocked, activeWithoutCheckpoint)

            try {
                store.clear(acceptedIds)
                fail("expected deletion failure")
            } catch (expected: IllegalStateException) {
                assertEquals("Cannot delete history copy", expected.message)
            }
            assertFalse(store.checkpoint(HistoryEntry(blocked, 11, "late blocked checkpoint", revision = 1)))
            store.save(HistoryEntry(activeWithoutCheckpoint, 12, "late active save"))
            store.updateDelivery(blocked, HistoryDelivery.ACCEPTED)
            val startedAfterBoundary = "d37f7c6e-08be-4076-9b54-eebd58ced971"
            store.save(HistoryEntry(startedAfterBoundary, 13, "take after clear boundary"))
            assertTrue(root.resolve("$alreadySaved.deleted").exists())
            assertTrue(root.resolve("$blocked.deleted").exists())
            assertTrue(root.resolve("$activeWithoutCheckpoint.deleted").exists())
            assertEquals("text being cleared", store.get(blocked)?.text)
            assertEquals(HistoryDelivery.REJECTED, store.get(blocked)?.delivery)
            assertNull(store.get(activeWithoutCheckpoint))
            assertNull(store.get(alreadySaved))
            assertTrue(store.list().any { it.id == blocked })
            assertFalse(root.resolve("$alreadySaved.bak").exists())

            val reopened = HistoryStore(root, HistoryOwner())
            assertEquals(setOf(alreadySaved, blocked, activeWithoutCheckpoint, startedAfterBoundary), reopened.clearableIds())
            root.resolve("$blocked.tmp").deleteRecursively()
            reopened.clear(acceptedIds)

            assertEquals(listOf(HistoryEntry(startedAfterBoundary, 13, "take after clear boundary")), reopened.list())
            assertTrue(root.resolve("$blocked.deleted").exists())
            assertTrue(root.resolve("$activeWithoutCheckpoint.deleted").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun startupPreservesTombstonedResidualCopiesForClearRetry() {
        val root = Files.createTempDirectory("history-clear-startup-retry").toFile()
        val id = "9a67d378-1c8c-405e-bdb1-c113df823988"
        try {
            root.resolve("$id.deleted").createNewFile()
            root.resolve("$id.tmp").apply { mkdir(); resolve("partial").writeText("synthetic") }

            val clearable = HistoryIo.executor.submit<Set<String>> { HistoryIo.store(root).clearableIds() }
                .get(10, TimeUnit.SECONDS)

            assertEquals(setOf(id), clearable)
            assertTrue(root.resolve("$id.tmp/partial").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun storageAvailabilitySeparatesResidualCopiesFromTombstoneMetadata() {
        val root = Files.createTempDirectory("history-clear-inventory").toFile()
        val tombstoneOnly = "b3de7a72-84ce-4721-8412-497b90c09315"
        val residual = "9a67d378-1c8c-405e-bdb1-c113df823988"
        try {
            root.resolve("$tombstoneOnly.deleted").createNewFile()
            root.resolve("$residual.bak").writeText("synthetic")
            val store = HistoryStore(root)

            assertEquals(setOf(tombstoneOnly, residual), store.clearableIds())
            assertEquals(setOf(residual), store.storedIds())
        } finally { root.deleteRecursively() }
    }
    @Test fun abandonedTemporaryCopiesAreRemovedAndDeleteRemovesKnownCopies() {
        val root = Files.createTempDirectory("history-test").toFile()
        try {
            val entry = HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 123L, "synthetic")
            val store = HistoryStore(root)
            store.save(entry)
            root.resolve("abandoned.tmp").writeText("partial")
            store.cleanAbandoned()
            assertFalse(root.resolve("abandoned.tmp").exists())
            root.resolve("${entry.id}.tmp").writeText("partial")
            root.resolve("${entry.id}.bak").writeText("old")
            store.delete(entry.id)
            assertEquals(listOf("${entry.id}.deleted"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun terminalTextCannotRegressToRecordingEvenWithLaterRevision() {
        val root = Files.createTempDirectory("history-order").toFile()
        try {
            val id = "b3de7a72-84ce-4721-8412-497b90c09315"
            val store = HistoryStore(root)
            assertTrue(store.checkpoint(HistoryEntry(id, 1, "whole take", HistoryDelivery.UNKNOWN, HistoryCompletion.COMPLETE, 2)))
            assertTrue(store.checkpoint(HistoryEntry(id, 1, "older prefix", HistoryDelivery.UNKNOWN, HistoryCompletion.RECORDING, 3)))
            val reopened = HistoryStore(root, HistoryOwner()).get(id)!!
            assertEquals("whole take", reopened.text)
            assertEquals(HistoryCompletion.COMPLETE, reopened.completion)
        } finally { root.deleteRecursively() }
    }
    @Test fun malformedRecordsDoNotEraseGoodData() {
        val root = Files.createTempDirectory("history-test").toFile()
        try {
            val entry = HistoryEntry("b3de7a72-84ce-4721-8412-497b90c09315", 123L, "safe synthetic text")
            val store = HistoryStore(root)
            store.save(entry)
            val bad = root.resolve("9a67d378-1c8c-405e-bdb1-c113df823988.history")
            bad.writeText("bad")
            try { store.list(); fail("expected read error") } catch (_: Exception) { }
            assertEquals(entry, store.get(entry.id))
            assertTrue(bad.exists())
        } finally { root.deleteRecursively() }
    }
}
