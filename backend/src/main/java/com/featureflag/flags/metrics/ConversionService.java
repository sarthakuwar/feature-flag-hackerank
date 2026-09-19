package com.featureflag.flags.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.dto.ConversionRequest;

/**
 * Records a conversion and then lets a configured bandit react to it. This lives apart from
 * {@link MetricsService} because ExperimentService already depends on MetricsService to read
 * current metrics, so MetricsService calling ExperimentService would be a circular dependency.
 */
@Service
public class ConversionService {

	private static final Logger LOG = LoggerFactory.getLogger(ConversionService.class);

	private final MetricsService metricsService;

	private final ExperimentService experimentService;

	public ConversionService(MetricsService metricsService, ExperimentService experimentService) {
		this.metricsService = metricsService;
		this.experimentService = experimentService;
	}

	/**
	 * A failed reallocation is logged and swallowed: the conversion is already saved, and failing the
	 * request would make the client retry and record the same conversion twice.
	 */
	public ConversionEvent record(ConversionRequest request) {
		ConversionEvent saved = metricsService.recordConversion(request);

		try {
			experimentService.reallocateIfBanditEnabled(request.flagKey(), request.environment());
		} catch (RuntimeException exception) {
			LOG.warn("Bandit reallocation failed after conversion {} for flag '{}' in {}; the conversion is kept.", saved.getId(),
					request.flagKey(), request.environment(), exception);
		}

		return saved;
	}
}
