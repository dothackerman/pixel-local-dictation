package ch.og.dictation

/** Diagnostic Run wall spans; same native binary for enabled and disabled runs. */
object NativeRoleProfiler {
    init { System.loadLibrary("sherpa-onnx-jni") }
    external fun start(enabled: Boolean): Boolean
    external fun clockNs(): Long
    external fun finish(path: String): Boolean
}
