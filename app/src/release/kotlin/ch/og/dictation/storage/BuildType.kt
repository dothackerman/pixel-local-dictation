package ch.og.dictation.storage

/** Release is the product only: no Benchmark switch, no recording saved (ADR-010 amendment). */
internal const val SAVES_RECORDINGS = false

/** Copy writes the phone's clipboard. */
internal fun copyTarget(system: Clipboard): Clipboard = system
