package com.featureflag.flags.targeting;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.featureflag.flags.EnvironmentKey;

import jakarta.annotation.PostConstruct;

/**
 * In-memory copy of segments and mobile applications that flag evaluation reads, the same pattern
 * as {@link com.featureflag.flags.FlagCacheService}: loaded once at startup, refreshed synchronously
 * by every write in this process, and pushed to connected SDKs over the SSE stream so their local
 * copies follow within seconds. Evaluation therefore never touches MongoDB.
 */
@Service
public class TargetingCacheService implements TargetingLookup {

	private final SegmentRepository segmentRepository;

	private final MobileApplicationRepository applicationRepository;

	private final ConcurrentHashMap<String, Segment> segmentsByEnvironmentAndKey = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, MobileApplication> applicationsByKey = new ConcurrentHashMap<>();

	private volatile boolean loaded;

	public TargetingCacheService(SegmentRepository segmentRepository, MobileApplicationRepository applicationRepository) {
		this.segmentRepository = segmentRepository;
		this.applicationRepository = applicationRepository;
	}

	@PostConstruct
	public void load() {
		segmentsByEnvironmentAndKey.clear();
		applicationsByKey.clear();
		segmentRepository.findAll().forEach(this::putSegment);
		applicationRepository.findAll().forEach(this::putApplication);
		loaded = true;
	}

	/** True once {@link #load()} has read every segment and application. */
	public boolean isLoaded() {
		return loaded;
	}

	public void putSegment(Segment segment) {
		segmentsByEnvironmentAndKey.put(cacheKey(segment.getEnvironment(), segment.getKey()), segment);
	}

	public void evictSegment(EnvironmentKey environment, String segmentKey) {
		segmentsByEnvironmentAndKey.remove(cacheKey(environment, segmentKey));
	}

	public Collection<Segment> segmentsIn(EnvironmentKey environment) {
		return segmentsByEnvironmentAndKey.values().stream().filter(segment -> segment.getEnvironment() == environment)
				.toList();
	}

	public void putApplication(MobileApplication application) {
		applicationsByKey.put(application.getKey(), application);
	}

	public void evictApplication(String applicationKey) {
		applicationsByKey.remove(applicationKey);
	}

	public Collection<MobileApplication> applications() {
		return applicationsByKey.values();
	}

	@Override
	public Optional<Segment> segment(EnvironmentKey environment, String segmentKey) {
		return Optional.ofNullable(segmentsByEnvironmentAndKey.get(cacheKey(environment, segmentKey)));
	}

	@Override
	public Optional<MobileApplication> application(String applicationKey) {
		return Optional.ofNullable(applicationsByKey.get(applicationKey));
	}

	private String cacheKey(EnvironmentKey environment, String segmentKey) {
		return environment.name() + ":" + segmentKey;
	}
}
