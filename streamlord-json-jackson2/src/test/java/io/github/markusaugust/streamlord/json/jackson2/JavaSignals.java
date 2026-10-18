package io.github.markusaugust.streamlord.json.jackson2;

/** A Java record, whose components Jackson reads through a creator without the Kotlin module. */
public record JavaSignals(String name, Integer count, int n) {}
