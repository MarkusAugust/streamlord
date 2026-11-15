package io.github.markusaugust.streamlord.analysis

/** A project that defines [signals] and knows nothing more, as the tests of unknown signals ask one. */
internal class Defined(
    private val signals: Set<String>,
) : ProjectSignals {
    override fun defined() = signals

    override fun own(): SignalFacts? = null

    override fun others() = emptyList<SignalFacts>()
}
