package io.github.markusaugust.streamlord.json.jackson;

import java.util.List;
import java.util.Map;

/** A Java record whose collections take null, as every Java collection does. */
public record JavaHeld(List<Integer> xs, Map<String, Integer> m) {}
