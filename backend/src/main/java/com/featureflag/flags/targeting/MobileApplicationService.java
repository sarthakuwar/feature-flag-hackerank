package com.featureflag.flags.targeting;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.FieldErrors;

/**
 * The registry behind "is this app version supported": which mobile applications exist and the
 * lowest version of each that still counts as supported. Writes refresh the in-memory {@link
 * TargetingCacheService} and push an {@code application-changed} event on every environment's stream.
 */
@Service
public class MobileApplicationService {

	/** Reverse-DNS style ids ("com.acme.app") are the norm, so dots are allowed after the first character. */
	private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

	private static final String CHANGE_CREATED = "CREATE";

	private static final String CHANGE_UPDATED = "UPDATE";

	private static final String CHANGE_DELETED = "DELETE";

	private final MobileApplicationRepository applicationRepository;

	private final TargetingCacheService targetingCacheService;

	private final FlagStreamService flagStreamService;

	public MobileApplicationService(MobileApplicationRepository applicationRepository,
			TargetingCacheService targetingCacheService, FlagStreamService flagStreamService) {
		this.applicationRepository = applicationRepository;
		this.targetingCacheService = targetingCacheService;
		this.flagStreamService = flagStreamService;
	}

	public List<MobileApplication> list() {
		return applicationRepository.findAll().stream()
				.sorted(Comparator.comparing((MobileApplication application) -> application.getName().toLowerCase(Locale.ROOT)))
				.toList();
	}

	public MobileApplication getOrThrow(String key) {
		return applicationRepository.findByKey(key).orElseThrow(
				() -> new ApiException(404, "APPLICATION_NOT_FOUND", "No mobile application exists with key '" + key + "'."));
	}

	public MobileApplication create(MobileApplicationRequest request) {
		FieldErrors errors = new FieldErrors();

		if (request.key() == null || request.key().isBlank()) {
			errors.add("key", "Key is required.");
		} else if (!KEY_PATTERN.matcher(request.key()).matches()) {
			errors.add("key", "Key must be letters, numbers, dots, underscores and hyphens, starting with a letter or number.");
		}

		requireText(request.name(), "name", errors);
		requireText(request.changedBy(), "changedBy", errors);
		validateMinimumVersion(request.minSupportedVersion(), errors);

		if (request.key() != null && errors.isEmpty() && applicationRepository.existsByKey(request.key())) {
			throw new ApiException(409, "APPLICATION_KEY_EXISTS",
					"A mobile application with key '" + request.key() + "' already exists.");
		}

		errors.throwIfAny();

		MobileApplication application = new MobileApplication();
		application.setKey(request.key());
		application.setName(request.name().trim());
		application.setMinSupportedVersion(request.minSupportedVersion().trim());
		application.setUpdatedBy(request.changedBy());

		return persist(application, CHANGE_CREATED);
	}

	public MobileApplication update(String key, MobileApplicationUpdateRequest request) {
		MobileApplication application = getOrThrow(key);
		FieldErrors errors = new FieldErrors();
		requireText(request.name(), "name", errors);
		requireText(request.changedBy(), "changedBy", errors);
		validateMinimumVersion(request.minSupportedVersion(), errors);
		errors.throwIfAny();

		application.setName(request.name().trim());
		application.setMinSupportedVersion(request.minSupportedVersion().trim());
		application.setUpdatedBy(request.changedBy());

		return persist(application, CHANGE_UPDATED);
	}

	/** Rules only name the ld_application context kind, never a specific app, so nothing can dangle: deleting is always safe. */
	public void delete(String key, String changedBy) {
		MobileApplication application = getOrThrow(key);
		FieldErrors errors = new FieldErrors();
		requireText(changedBy, "changedBy", errors);
		errors.throwIfAny();

		applicationRepository.delete(application);
		targetingCacheService.evictApplication(key);
		flagStreamService.publishApplicationChanged(key, CHANGE_DELETED);
	}

	private MobileApplication persist(MobileApplication application, String change) {
		MobileApplication saved = applicationRepository.save(application);
		targetingCacheService.putApplication(saved);
		flagStreamService.publishApplicationChanged(saved.getKey(), change);

		return saved;
	}

	private void validateMinimumVersion(String version, FieldErrors errors) {
		if (version == null || version.isBlank()) {
			errors.add("minSupportedVersion", "Minimum supported version is required.");
		} else if (SemanticVersion.parse(version).isEmpty()) {
			errors.add("minSupportedVersion", "'" + version + "' is not a valid version (expected e.g. 2.4.0).");
		}
	}

	private void requireText(String value, String field, FieldErrors errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "This field is required.");
		}
	}
}
