package ch.og.dictation
import org.junit.Assert.*
import org.junit.Test
class ReleaseTruthSpecReviewTest {
 @Test fun failedReleaseMustNotBecomeSuccessfulOnRetryWhileDeviceRemainsAllocated() {
  var released = false
  var releaseCalls = 0
  val device = object : Recorder.AudioInput {
   override val bufferSize = 8
   override fun start() {}
   override fun read(buffer: ShortArray) = -1
   override fun stop() {}
   override fun release() { releaseCalls++; if (releaseCalls == 1) throw IllegalStateException("synthetic release failure"); released = true }
  }
  val recorder = Recorder { device }
  recorder.start()
  assertTrue(runCatching { recorder.stop() }.isFailure)
  val secondStop = runCatching { recorder.stop() }
  assertTrue("stop success requires actual release", secondStop.isFailure || released)
 }
}
