package com.featureflag.flags.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.featureflag.flags.EnvironmentKey;
import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.dto.ConversionRequest;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {

	private static final ConversionRequest REQUEST = new ConversionRequest("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1",
			"purchase", 1.0);

	@Mock
	private MetricsService metricsService;

	@Mock
	private ExperimentService experimentService;

	@InjectMocks
	private ConversionService conversionService;

	@Test
	void savesTheConversionThenReallocatesTheBandit() {
		ConversionEvent saved = new ConversionEvent("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", 1.0);
		when(metricsService.recordConversion(REQUEST)).thenReturn(saved);

		assertThat(conversionService.record(REQUEST)).isSameAs(saved);

		verify(experimentService).reallocateIfBanditEnabled("flag-a", EnvironmentKey.PRODUCTION);
	}

	@Test
	void aFailedReallocationDoesNotFailAConversionThatWasAlreadySaved() {
		ConversionEvent saved = new ConversionEvent("flag-a", EnvironmentKey.PRODUCTION, "a", "user-1", "purchase", 1.0);
		when(metricsService.recordConversion(REQUEST)).thenReturn(saved);
		doThrow(new IllegalStateException("mongo is down")).when(experimentService).reallocateIfBanditEnabled("flag-a",
				EnvironmentKey.PRODUCTION);

		assertThat(conversionService.record(REQUEST)).isSameAs(saved);
	}

	@Test
	void anInvalidConversionIsRejectedWithoutTouchingTheBandit() {
		when(metricsService.recordConversion(REQUEST)).thenThrow(new ValidationException(Map.of()));

		assertThatThrownBy(() -> conversionService.record(REQUEST)).isInstanceOf(ValidationException.class);

		verify(experimentService, never()).reallocateIfBanditEnabled("flag-a", EnvironmentKey.PRODUCTION);
	}
}
