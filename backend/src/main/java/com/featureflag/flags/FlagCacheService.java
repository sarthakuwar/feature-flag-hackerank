package com.featureflag.flags;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

/**
 * In-memory read cache flag evaluation runs against, so evaluation never hits MongoDB
 * per request. Refreshed synchronously on every write in the same process.
 */
@Service
public class FlagCacheService implements FlagLookup {

	private final FlagRepository flagRepository;

	private final ConcurrentHashMap<String, Flag> flagsByKey = new ConcurrentHashMap<>();

	private volatile boolean loaded;

	public FlagCacheService(FlagRepository flagRepository) {
		this.flagRepository = flagRepository;
	}

	@PostConstruct
	public void load() {
		flagsByKey.clear();
		flagRepository.findAll().forEach(flag -> flagsByKey.put(flag.getKey(), flag));
		loaded = true;
	}

	/** True once {@link #load()} has read every flag, so readiness can tell an empty cache from an unloaded one. */
	public boolean isLoaded() {
		return loaded;
	}

	public void put(Flag flag) {
		flagsByKey.put(flag.getKey(), flag);
	}

	@Override
	public Optional<Flag> get(String key) {
		return Optional.ofNullable(flagsByKey.get(key));
	}

	public Collection<Flag> all() {
		return flagsByKey.values();
	}
}
