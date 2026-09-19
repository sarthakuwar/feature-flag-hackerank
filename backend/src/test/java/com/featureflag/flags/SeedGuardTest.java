package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

import com.featureflag.flags.audit.AuditRepository;
import com.featureflag.flags.experiment.ExperimentService;
import com.featureflag.flags.metrics.ConversionRepository;
import com.featureflag.flags.metrics.ImpressionRepository;
import com.featureflag.flags.rollout.ProgressiveRolloutService;
import com.featureflag.flags.targeting.MobileApplicationRepository;
import com.featureflag.flags.targeting.MobileApplicationService;
import com.featureflag.flags.targeting.SegmentRepository;
import com.featureflag.flags.targeting.SegmentService;
import com.featureflag.flags.targeting.TargetingCacheService;
import com.featureflag.flags.targeting.TargetingService;

/** Never connects to any host: the guard only parses connection strings. */
class SeedGuardTest {

	@ParameterizedTest
	@ValueSource(strings = { "mongodb://localhost:27017/featureflag_db", "mongodb://127.0.0.1:27017/featureflag_db",
			"mongodb://[::1]:27017/featureflag_db", "mongodb://LOCALHOST/featureflag_db",
			"mongodb://admin:secret@localhost:27017/featureflag_db?authSource=admin",
			"mongodb://localhost:27017,127.0.0.1:27018/featureflag_db?replicaSet=rs0" })
	void aLocalTargetIsSeededWithoutForcing(String uri) {
		assertThat(new SeedGuard(uri, false).permitsSeeding()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "mongodb://db.example.com:27017/featureflag_db", "mongodb://10.0.0.5:27017/featureflag_db",
			"mongodb+srv://cluster0.example.mongodb.net/featureflag_db",
			"mongodb://localhost.example.com:27017/featureflag_db",
			"mongodb://localhost:27017,db.example.com:27017/featureflag_db",
			"mongodb://localhost@db.example.com:27017/featureflag_db", "not a uri", "" })
	void aRemoteOrUnparseableTargetIsRefusedWithoutForcing(String uri) {
		assertThat(new SeedGuard(uri, false).permitsSeeding()).isFalse();
	}

	@Test
	void forcingSeedsARemoteTarget() {
		SeedGuard guard = new SeedGuard("mongodb://db.example.com:27017/featureflag_db", true);

		assertThat(guard.permitsSeeding()).isTrue();
		assertThat(guard.isForced()).isTrue();
	}

	@Test
	void theSeederTouchesNothingWhenTheGuardRefuses() {
		FlagRepository flagRepository = mock(FlagRepository.class);
		AuditRepository auditRepository = mock(AuditRepository.class);
		ImpressionRepository impressionRepository = mock(ImpressionRepository.class);
		ConversionRepository conversionRepository = mock(ConversionRepository.class);
		SegmentRepository segmentRepository = mock(SegmentRepository.class);
		MobileApplicationRepository applicationRepository = mock(MobileApplicationRepository.class);
		FlagService flagService = mock(FlagService.class);
		FlagCacheService flagCacheService = mock(FlagCacheService.class);
		TargetingCacheService targetingCacheService = mock(TargetingCacheService.class);
		SeedGuard guard = mock(SeedGuard.class);
		when(guard.permitsSeeding()).thenReturn(false);

		FlagSeeder seeder = new FlagSeeder(guard, flagRepository, auditRepository, flagService, flagCacheService,
				impressionRepository, conversionRepository, mock(ExperimentService.class), segmentRepository,
				applicationRepository, mock(SegmentService.class), mock(MobileApplicationService.class),
				mock(TargetingService.class), targetingCacheService, mock(ProgressiveRolloutService.class));

		seeder.run(new DefaultApplicationArguments());

		verifyNoInteractions(flagRepository, auditRepository, impressionRepository, conversionRepository, segmentRepository,
				applicationRepository, flagService, flagCacheService, targetingCacheService);
	}
}
