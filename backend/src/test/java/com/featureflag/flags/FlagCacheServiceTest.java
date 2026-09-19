package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FlagCacheService is the in-process "local cache" a real SDK would hold after bootstrapping:
 * loaded once from the flag service, then read on every evaluation with zero network I/O. These
 * tests exercise it directly, independent of the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class FlagCacheServiceTest {

	@Mock
	private FlagRepository flagRepository;

	private FlagCacheService flagCacheService;

	@BeforeEach
	void setUp() {
		flagCacheService = new FlagCacheService(flagRepository);
	}

	@Test
	void startsEmptyBeforeLoadIsCalled() {
		assertThat(flagCacheService.all()).isEmpty();
		assertThat(flagCacheService.get("anything")).isEmpty();
	}

	@Test
	void loadPopulatesTheCacheFromTheRepository() {
		when(flagRepository.findAll()).thenReturn(List.of(flagWithKey("flag-a"), flagWithKey("flag-b")));

		flagCacheService.load();

		assertThat(flagCacheService.all()).hasSize(2);
		assertThat(flagCacheService.get("flag-a")).isPresent();
		assertThat(flagCacheService.get("flag-b")).isPresent();
	}

	@Test
	void loadReplacesRatherThanAccumulatesOnRepeatedCalls() {
		when(flagRepository.findAll()).thenReturn(List.of(flagWithKey("flag-a")))
				.thenReturn(List.of(flagWithKey("flag-a"), flagWithKey("flag-b")));

		flagCacheService.load();
		assertThat(flagCacheService.all()).hasSize(1);

		flagCacheService.load();
		assertThat(flagCacheService.all()).hasSize(2);
	}

	@Test
	void putAddsOrOverwritesAFlagByKeyWithoutTouchingTheRepository() {
		Flag original = flagWithKey("flag-a");
		original.setName("Original");
		flagCacheService.put(original);

		Flag updated = flagWithKey("flag-a");
		updated.setName("Updated");
		flagCacheService.put(updated);

		assertThat(flagCacheService.all()).hasSize(1);
		assertThat(flagCacheService.get("flag-a")).get().extracting(Flag::getName).isEqualTo("Updated");
	}

	@Test
	void getForAnUnknownKeyReturnsEmptyRatherThanThrowing() {
		assertThat(flagCacheService.get("does-not-exist")).isEmpty();
	}

	private Flag flagWithKey(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName(key);
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setVariations(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true")));

		return flag;
	}
}
