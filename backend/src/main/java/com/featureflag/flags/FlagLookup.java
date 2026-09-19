package com.featureflag.flags;

import java.util.Optional;

/**
 * The other flags an evaluation needs to resolve prerequisites. Implementations must be answered from
 * memory - evaluation never does I/O - which is why the production implementation is the flag cache
 * that the write path keeps fresh.
 */
public interface FlagLookup {

	FlagLookup EMPTY = key -> Optional.empty();

	Optional<Flag> get(String key);
}
