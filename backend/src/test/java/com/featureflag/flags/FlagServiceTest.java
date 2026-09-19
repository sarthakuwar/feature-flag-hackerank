package com.featureflag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.regex.Pattern;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.featureflag.flags.audit.AuditAction;
import com.featureflag.flags.audit.AuditService;
import com.featureflag.flags.dto.BulkArchiveResult;
import com.featureflag.flags.dto.BulkRestoreResult;
import com.featureflag.flags.dto.EnvironmentConfigRequest;
import com.featureflag.flags.dto.FlagCreateRequest;
import com.featureflag.flags.dto.FlagUpdateRequest;
import com.featureflag.flags.dto.StrategyRequest;
import com.featureflag.flags.dto.VariationCreateRequest;
import com.featureflag.flags.dto.VariationRequest;
import com.featureflag.flags.dto.VariationUpdateRequest;
import com.featureflag.flags.targeting.IndividualTarget;
import com.featureflag.flags.targeting.TargetingRule;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.PageParams;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

	@Mock
	private FlagRepository flagRepository;

	@Mock
	private FlagCacheService flagCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	@Mock
	private AuditService auditService;

	private FlagService flagService;

	@BeforeEach
	void setUp() {
		FlagAccess flagAccess = new FlagAccess(flagRepository);
		flagService = new FlagService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess,
				new FlagVariationService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess),
				new FlagArchiveService(flagRepository, flagCacheService, flagStreamService, auditService, flagAccess));
		lenient().when(flagRepository.save(any(Flag.class))).thenAnswer((InvocationOnMock invocation) -> invocation.getArgument(0));
	}

	@Test
	void listWithoutFiltersAsksForEveryLifecycleAndAnEmptyPatternOldestFirstWithKeyTieBreak() {
		Page<Flag> repositoryPage = new PageImpl<>(List.of(existingBooleanFlag("a")), PageRequest.of(1, 25), 30);
		when(flagRepository.search(any(), any(), any())).thenReturn(repositoryPage);

		Page<Flag> result = flagService.list(null, null, new PageParams(2, 25));

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(flagRepository).search(eq(List.of(FlagLifecycle.values())), eq(""), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).as("page 2 is zero-based index 1").isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
		assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("key"));
		assertThat(result).isSameAs(repositoryPage);
	}

	@Test
	void listFiltersByLifecycleAndQuotesTheTrimmedSearchTextSoRegexCharactersMatchLiterally() {
		when(flagRepository.search(any(), any(), any())).thenReturn(Page.empty());

		flagService.list(FlagLifecycle.ARCHIVED, "  a.b*(c)  ", new PageParams(1, 50));

		verify(flagRepository).search(eq(List.of(FlagLifecycle.ARCHIVED)), eq(Pattern.quote("a.b*(c)")), any(Pageable.class));
	}

	@Test
	void aBlankSearchTextIsNoFilter() {
		when(flagRepository.search(any(), any(), any())).thenReturn(Page.empty());

		flagService.list(null, "   ", new PageParams(1, 50));

		verify(flagRepository).search(any(), eq(""), any(Pageable.class));
	}

	@Test
	void createSavesFlagWithDefaultEnvironmentsAndRecordsAudit() {
		Flag saved = flagService.create(booleanCreateRequest("new-flag"));

		assertThat(saved.getKey()).isEqualTo("new-flag");
		assertThat(saved.getLifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
		assertThat(saved.getEnvironments()).containsKeys(EnvironmentKey.DEVELOPMENT, EnvironmentKey.STAGING,
				EnvironmentKey.PRODUCTION);
		assertThat(saved.getEnvironments().get(EnvironmentKey.PRODUCTION).isEnabled()).isFalse();

		verify(flagCacheService).put(saved);
		verify(auditService).record("new-flag", AuditAction.CREATE, "changer", "Created flag 'New Flag'.");
	}

	@Test
	void createIsIdempotentAndReturnsTheExistingFlagWithoutErrorOrDuplicateAudit() {
		Flag existing = existingBooleanFlag("new-flag");
		when(flagRepository.findByKey("new-flag")).thenReturn(Optional.of(existing));

		Flag result = flagService.create(booleanCreateRequest("new-flag"));

		assertThat(result).isSameAs(existing);
		verify(flagRepository, never()).save(any());
		verify(auditService, never()).record(any(), any(), any(), any());
	}

	@Test
	void createRejectsInvalidKeyFormat() {
		assertThatThrownBy(() -> flagService.create(booleanCreateRequest("Not Valid Key!")))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void createRejectsBooleanFlagWithoutExactlyTwoVariations() {
		FlagCreateRequest request = new FlagCreateRequest("three-way-flag", "Three Way", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true"),
						new VariationRequest("extra", "Extra", "extra")),
				List.of(), "changer");

		assertThatThrownBy(() -> flagService.create(request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void createRejectsDuplicateVariationKeysWithinFlag() {
		FlagCreateRequest request = new FlagCreateRequest("dup-flag", "Dup", "desc", FlagType.MULTIVARIATE, "owner",
				List.of(new VariationRequest("a", "A", "a"), new VariationRequest("a", "A again", "a2")), List.of(), "changer");

		assertThatThrownBy(() -> flagService.create(request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void createRejectsSelfDependency() {
		FlagCreateRequest request = new FlagCreateRequest("self-dep", "Self Dep", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of("self-dep"), "changer");

		assertThatThrownBy(() -> flagService.create(request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void createRejectsDependencyOnAnUnknownFlag() {
		when(flagRepository.existsByKey("does-not-exist")).thenReturn(false);
		FlagCreateRequest request = new FlagCreateRequest("dependent-flag", "Dependent", "desc", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")),
				List.of("does-not-exist"), "changer");

		assertThatThrownBy(() -> flagService.create(request)).isInstanceOf(ValidationException.class);
	}

	@Test
	void getOrThrowThrowsApiExceptionWhenFlagMissing() {
		when(flagRepository.findByKey("missing")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> flagService.getOrThrow("missing")).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 404);
	}

	@Test
	void updateEnvironmentRejectsRolloutReferencingUnknownVariation() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		EnvironmentConfigRequest request = new EnvironmentConfigRequest(true, "off",
				List.of(rolloutStrategy(50, "does-not-exist")), "changer");

		assertThatThrownBy(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION, request))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void updateEnvironmentRejectsPercentageOutOfRange() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		EnvironmentConfigRequest request = new EnvironmentConfigRequest(true, "off", List.of(rolloutStrategy(150, "on")),
				"changer");

		assertThatThrownBy(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION, request))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void updateEnvironmentRejectsUserIdStrategyWithNoUserIds() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		StrategyRequest badStrategy = new StrategyRequest(null, StrategyType.USER_ID, List.of(), null, null, 0, "on");
		EnvironmentConfigRequest request = new EnvironmentConfigRequest(true, "off", List.of(badStrategy), "changer");

		assertThatThrownBy(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION, request))
				.isInstanceOf(ValidationException.class);
	}

	@Test
	void addVariationAppendsToAMultivariateFlagAndRecordsAudit() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.addVariation("multi-flag", new VariationCreateRequest("purple", "Purple", "purple", "changer"));

		assertThat(saved.getVariations()).extracting(Variation::getKey).containsExactly("red", "blue", "purple");
		verify(auditService).record("multi-flag", AuditAction.UPDATE, "changer", "Added variation 'purple'.");
	}

	@Test
	void addVariationRejectsDuplicateKey() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.addVariation("multi-flag", new VariationCreateRequest("red", "Red again", "r", "changer")))
				.isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void addVariationRejectsOnABooleanFlag() {
		Flag flag = existingBooleanFlag("bool-flag");
		when(flagRepository.findByKey("bool-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.addVariation("bool-flag", new VariationCreateRequest("maybe", "Maybe", "maybe", "changer")))
				.isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void addVariationRejectsWhenFlagIsArchived() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.addVariation("multi-flag", new VariationCreateRequest("purple", "Purple", "purple", "changer")))
				.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "FLAG_ARCHIVED");
	}

	@Test
	void updateVariationChangesNameAndValueButKeepsKeyStable() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.updateVariation("multi-flag", "red", new VariationUpdateRequest("Crimson", "crimson", "changer"));

		Variation updated = saved.getVariations().stream().filter(v -> v.getKey().equals("red")).findFirst().orElseThrow();
		assertThat(updated.getName()).isEqualTo("Crimson");
		assertThat(updated.getValue()).isEqualTo("crimson");
		verify(auditService).record("multi-flag", AuditAction.UPDATE, "changer", "Updated variation 'red'.");
	}

	@Test
	void updateVariationThrowsNotFoundForAnUnknownVariationKey() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.updateVariation("multi-flag", "does-not-exist",
				new VariationUpdateRequest("X", "x", "changer"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 404).hasFieldOrPropertyWithValue("code", "VARIATION_NOT_FOUND");
	}

	@Test
	void removeVariationDeletesAnUnreferencedVariation() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.removeVariation("multi-flag", "green", "changer");

		assertThat(saved.getVariations()).extracting(Variation::getKey).containsExactly("red", "blue");
		verify(auditService).record("multi-flag", AuditAction.UPDATE, "changer", "Removed variation 'green'.");
	}

	@Test
	void updateEnvironmentKeepsTargetingRulesAndIndividualTargetsThatItDoesNotOwn() {
		Flag flag = existingBooleanFlag("existing-flag");
		TargetingRule rule = new TargetingRule();
		rule.setId("keep-me");
		rule.setServedVariationKey("on");
		IndividualTarget target = new IndividualTarget("user", "on", java.util.Set.of("vip"));
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getRules().add(rule);
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getIndividualTargets().add(target);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION,
				new EnvironmentConfigRequest(true, "off", List.of(rolloutStrategy(50, "on")), "changer"));

		EnvironmentConfig config = saved.getEnvironments().get(EnvironmentKey.PRODUCTION);
		assertThat(config.isEnabled()).isTrue();
		assertThat(config.getStrategies()).hasSize(1);
		assertThat(config.getRules()).containsExactly(rule);
		assertThat(config.getIndividualTargets()).containsExactly(target);
	}

	@Test
	void removeVariationIsBlockedWhileATargetingRuleOrIndividualTargetStillServesIt() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		flag.getVariations().add(new Variation("gold", "Gold", "gold"));
		TargetingRule fixed = new TargetingRule();
		fixed.setId("serves-green");
		fixed.setServedVariationKey("green");
		flag.getEnvironments().get(EnvironmentKey.STAGING).getRules().add(fixed);
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getIndividualTargets()
				.add(new IndividualTarget("user", "gold", java.util.Set.of("vip")));
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "green", "changer")).isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "gold", "changer")).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void removeVariationIsBlockedWhileARuleRolloutGivesItATrafficShareButNotWhenItsShareIsZero() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		flag.getVariations().add(new Variation("gold", "Gold", "gold"));
		TargetingRule rollout = new TargetingRule();
		rollout.setId("rollout");
		rollout.setRolloutWeights(new java.util.LinkedHashMap<>(Map.of("red", 60, "green", 40, "gold", 0)));
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).getRules().add(rollout);
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "green", "changer")).isInstanceOf(ValidationException.class);

		Flag saved = flagService.removeVariation("multi-flag", "gold", "changer");

		assertThat(saved.getVariations()).extracting(Variation::getKey).doesNotContain("gold");
	}

	@Test
	void removeVariationRejectsWhenItWouldLeaveFewerThanTwoVariations() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "red", "changer")).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void removeVariationRejectsOnABooleanFlag() {
		Flag flag = existingBooleanFlag("bool-flag");
		when(flagRepository.findByKey("bool-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("bool-flag", "on", "changer")).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void removeVariationRejectsWhenAnEnvironmentStillReferencesItViaOffVariationKey() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).setOffVariationKey("green");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "green", "changer")).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void removeVariationRejectsWhenAStrategyStillServesIt() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		Strategy strategy = new Strategy();
		strategy.setId("s1");
		strategy.setType(StrategyType.USER_ID);
		strategy.setUserIds(List.of("u1"));
		strategy.setServedVariationKey("green");
		flag.getEnvironments().get(EnvironmentKey.STAGING).getStrategies().add(strategy);
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.removeVariation("multi-flag", "green", "changer")).isInstanceOf(ValidationException.class);
		verify(flagRepository, never()).save(any());
	}

	@Test
	void updateRecordsAuditActionAndPersistsFields() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.update("existing-flag",
				new FlagUpdateRequest("Renamed", "New description", "new-owner", List.of(), "changer"));

		assertThat(saved.getName()).isEqualTo("Renamed");
		assertThat(saved.getDescription()).isEqualTo("New description");
		assertThat(saved.getOwner()).isEqualTo("new-owner");
		verify(auditService).record("existing-flag", AuditAction.UPDATE, "changer", "Updated flag details.");
	}

	@Test
	void updateEnvironmentRecordsPlainUpdateActionWhenEnabledStateDoesNotChange() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).setEnabled(true);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		EnvironmentConfigRequest request = new EnvironmentConfigRequest(true, "off", List.of(rolloutStrategy(75, "on")),
				"changer");

		flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION, request);

		verify(auditService).record(any(), eq(AuditAction.UPDATE), eq("changer"), any());
		verify(flagStreamService, times(1)).publishFlagChanged(EnvironmentKey.PRODUCTION, "existing-flag",
				AuditAction.UPDATE.name());
	}

	@Test
	void updateRejectsWhenFlagIsArchived() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		FlagUpdateRequest request = new FlagUpdateRequest("Renamed", "desc", "owner", List.of(), "changer");

		assertThatThrownBy(() -> flagService.update("existing-flag", request)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 409).hasFieldOrPropertyWithValue("code", "FLAG_ARCHIVED");
		verify(flagRepository, never()).save(any());
	}

	@Test
	void updateEnvironmentRejectsWhenFlagIsArchived() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		EnvironmentConfigRequest request = new EnvironmentConfigRequest(true, "off", List.of(rolloutStrategy(50, "on")),
				"changer");

		assertThatThrownBy(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION, request))
				.isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("statusCode", 409)
				.hasFieldOrPropertyWithValue("code", "FLAG_ARCHIVED");
		verify(flagRepository, never()).save(any());
	}

	@Test
	void archiveIsIdempotentWhenAlreadyArchived() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag result = flagService.archive("existing-flag", "changer");

		assertThat(result).isSameAs(flag);
		verify(flagRepository, never()).save(any());
		verify(auditService, never()).record(any(), any(), any(), any());
	}

	@Test
	void restoreIsIdempotentWhenAlreadyActive() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag result = flagService.restore("existing-flag", "changer");

		assertThat(result).isSameAs(flag);
		verify(flagRepository, never()).save(any());
		verify(auditService, never()).record(any(), any(), any(), any());
	}

	@Test
	void archiveRejectsWhenAnActiveFlagDependsOnIt() {
		Flag flag = existingBooleanFlag("dependency-flag");
		when(flagRepository.findByKey("dependency-flag")).thenReturn(Optional.of(flag));

		Flag dependent = existingBooleanFlag("dependent-flag");
		dependent.setDependsOn(List.of("dependency-flag"));
		when(flagRepository.findByLifecycle(FlagLifecycle.ACTIVE)).thenReturn(List.of(dependent));

		assertThatThrownBy(() -> flagService.archive("dependency-flag", "changer")).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 409).hasFieldOrPropertyWithValue("code", "FLAG_HAS_DEPENDENTS");
		verify(flagRepository, never()).save(any());
	}

	@Test
	void archiveSetsLifecycleAndRecordsAuditAcrossAllEnvironments() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag archived = flagService.archive("existing-flag", "changer");

		assertThat(archived.getLifecycle()).isEqualTo(FlagLifecycle.ARCHIVED);
		verify(auditService).record("existing-flag", AuditAction.ARCHIVE, "changer", "Archived flag.");

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			verify(flagStreamService).publishFlagChanged(environment, "existing-flag", AuditAction.ARCHIVE.name());
		}
	}

	@Test
	void restoreSetsLifecycleActiveAndRecordsAudit() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Flag restored = flagService.restore("existing-flag", "changer");

		assertThat(restored.getLifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
		verify(auditService).record("existing-flag", AuditAction.RESTORE, "changer", "Restored flag from archive.");
	}

	@Test
	void bulkArchiveArchivesEachActiveFlagAndSkipsAlreadyArchivedOnes() {
		Flag active = existingBooleanFlag("active-flag");
		Flag alreadyArchived = existingBooleanFlag("archived-flag");
		alreadyArchived.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("active-flag")).thenReturn(Optional.of(active));
		when(flagRepository.findByKey("archived-flag")).thenReturn(Optional.of(alreadyArchived));
		when(flagRepository.findByKey("unknown-flag")).thenReturn(Optional.empty());
		lenient().when(flagRepository.findByLifecycle(FlagLifecycle.ACTIVE)).thenReturn(List.of(active));

		BulkArchiveResult result = flagService.bulkArchive(List.of("active-flag", "archived-flag", "unknown-flag"), "changer");

		assertThat(result.archived()).containsExactly("active-flag");
		assertThat(result.alreadyArchived()).containsExactly("archived-flag");
		assertThat(result.notFound()).containsExactly("unknown-flag");
		assertThat(result.blocked()).isEmpty();
		verify(auditService).record(eq("active-flag"), eq(AuditAction.ARCHIVE), eq("changer"), any());
		verify(auditService, never()).record(eq("archived-flag"), any(), any(), any());
	}

	@Test
	void bulkArchiveBlocksAFlagWithAStillActiveDependentOutsideTheBatch() {
		Flag dependency = existingBooleanFlag("dependency-flag");
		Flag dependent = existingBooleanFlag("dependent-flag");
		dependent.setDependsOn(List.of("dependency-flag"));
		when(flagRepository.findByKey("dependency-flag")).thenReturn(Optional.of(dependency));
		when(flagRepository.findByLifecycle(FlagLifecycle.ACTIVE)).thenReturn(List.of(dependency, dependent));

		BulkArchiveResult result = flagService.bulkArchive(List.of("dependency-flag"), "changer");

		assertThat(result.archived()).isEmpty();
		assertThat(result.blocked()).containsOnlyKeys("dependency-flag");
		assertThat(result.blocked().get("dependency-flag")).containsExactly("dependent-flag");
		verify(flagRepository, never()).save(any());
	}

	@Test
	void bulkArchiveAllowsArchivingADependencyTogetherWithItsOnlyDependentInTheSameBatch() {
		Flag dependency = existingBooleanFlag("dependency-flag");
		Flag dependent = existingBooleanFlag("dependent-flag");
		dependent.setDependsOn(List.of("dependency-flag"));
		when(flagRepository.findByKey("dependency-flag")).thenReturn(Optional.of(dependency));
		when(flagRepository.findByKey("dependent-flag")).thenReturn(Optional.of(dependent));
		when(flagRepository.findByLifecycle(FlagLifecycle.ACTIVE)).thenReturn(List.of(dependency, dependent));

		BulkArchiveResult result = flagService.bulkArchive(List.of("dependency-flag", "dependent-flag"), "changer");

		assertThat(result.blocked()).isEmpty();
		assertThat(result.archived()).containsExactlyInAnyOrder("dependency-flag", "dependent-flag");
	}

	@Test
	void bulkRestoreRestoresArchivedFlagsAndLeavesAlreadyActiveOnesCompletelyUntouched() {
		Flag archived = existingBooleanFlag("archived-flag");
		archived.setLifecycle(FlagLifecycle.ARCHIVED);
		Flag active = existingBooleanFlag("active-flag");
		EnvironmentConfig productionBefore = active.getEnvironments().get(EnvironmentKey.PRODUCTION);
		when(flagRepository.findByKey("archived-flag")).thenReturn(Optional.of(archived));
		when(flagRepository.findByKey("active-flag")).thenReturn(Optional.of(active));
		when(flagRepository.findByKey("unknown-flag")).thenReturn(Optional.empty());

		BulkRestoreResult result = flagService.bulkRestore(List.of("archived-flag", "active-flag", "unknown-flag"), "changer");

		assertThat(result.restored()).containsExactly("archived-flag");
		assertThat(result.alreadyActive()).containsExactly("active-flag");
		assertThat(result.notFound()).containsExactly("unknown-flag");
		// bulk revive must never disable/reset environments of flags that were never archived
		assertThat(active.getEnvironments().get(EnvironmentKey.PRODUCTION)).isSameAs(productionBefore);
		verify(auditService, never()).record(eq("active-flag"), any(), any(), any());
		verify(flagStreamService, never()).publishFlagChanged(any(), eq("active-flag"), any());
	}

	@Test
	void validateBulkArchiveReportsBlockedKeysWithoutArchivingAnything() {
		Flag dependency = existingBooleanFlag("dependency-flag");
		Flag dependent = existingBooleanFlag("dependent-flag");
		dependent.setDependsOn(List.of("dependency-flag"));
		when(flagRepository.findByKey("dependency-flag")).thenReturn(Optional.of(dependency));
		when(flagRepository.findByLifecycle(FlagLifecycle.ACTIVE)).thenReturn(List.of(dependency, dependent));

		Map<String, List<String>> blocked = flagService.validateBulkArchive(List.of("dependency-flag"));

		assertThat(blocked).containsOnlyKeys("dependency-flag");
		verify(flagRepository, never()).save(any());
	}

	// ---- concurrent create ----

	@Test
	void createReturnsTheWinnersFlagWhenAConcurrentCreateTakesTheKeyBetweenTheCheckAndTheSave() {
		Flag winner = existingBooleanFlag("new-flag");
		when(flagRepository.findByKey("new-flag")).thenReturn(Optional.empty(), Optional.of(winner));
		when(flagRepository.save(any(Flag.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

		Flag result = flagService.create(booleanCreateRequest("new-flag"));

		assertThat(result).isSameAs(winner);
		verify(flagCacheService, never()).put(any());
		verify(auditService, never()).record(any(), any(), any(), any());
		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	@Test
	void createRethrowsADuplicateKeyThatNoFlagExplains() {
		when(flagRepository.findByKey("new-flag")).thenReturn(Optional.empty());
		when(flagRepository.save(any(Flag.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

		assertThatThrownBy(() -> flagService.create(booleanCreateRequest("new-flag"))).isInstanceOf(DuplicateKeyException.class);
	}

	// ---- stream events for flag-wide writes ----

	@Test
	void createAnnouncesTheNewFlagToEveryEnvironment() {
		flagService.create(booleanCreateRequest("new-flag"));

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			verify(flagStreamService).publishFlagChanged(environment, "new-flag", AuditAction.CREATE.name());
		}
	}

	@Test
	void updateAndVariationWritesAnnounceTheChangeToEveryEnvironment() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		flagService.update("multi-flag", new FlagUpdateRequest("Renamed", "d", "owner", List.of(), "changer"));
		flagService.addVariation("multi-flag", new VariationCreateRequest("purple", "Purple", "purple", "changer"));
		flagService.updateVariation("multi-flag", "red", new VariationUpdateRequest("Crimson", "crimson", "changer"));
		flagService.removeVariation("multi-flag", "green", "changer");

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			verify(flagStreamService, times(4)).publishFlagChanged(environment, "multi-flag", AuditAction.UPDATE.name());
		}
	}

	@Test
	void aRejectedWritePublishesNothing() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		assertThatThrownBy(() -> flagService.addVariation("multi-flag", new VariationCreateRequest("red", "Dup", "r", "changer")))
				.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> flagService.update("multi-flag", new FlagUpdateRequest(" ", "d", "owner", List.of(), "changer")))
				.isInstanceOf(ValidationException.class);

		verify(flagStreamService, never()).publishFlagChanged(any(), any(), any());
	}

	// ---- removing a variation an experiment split still weights ----

	@Test
	void removeVariationIsBlockedWhileAnExperimentSplitStillListsItEvenAtWeightZero() {
		Flag flag = existingMultivariateFlag("multi-flag");
		flag.getVariations().add(new Variation("green", "Green", "green"));
		Strategy experiment = new Strategy();
		experiment.setId("exp");
		experiment.setType(StrategyType.EXPERIMENT_SPLIT);
		experiment.setVariationWeights(new LinkedHashMap<>(Map.of("red", 50, "blue", 50, "green", 0)));
		flag.getEnvironments().get(EnvironmentKey.STAGING).getStrategies().add(experiment);
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		Map<String, List<String>> errors = fieldErrors(() -> flagService.removeVariation("multi-flag", "green", "changer"));

		assertThat(errors.get("key")).singleElement().asString().contains("STAGING");
		verify(flagRepository, never()).save(any());

		experiment.setVariationWeights(new LinkedHashMap<>(Map.of("red", 50, "blue", 50)));

		assertThat(flagService.removeVariation("multi-flag", "green", "changer").getVariations()).hasSize(2);
	}

	// ---- changedBy on archive and restore ----

	@Test
	void archiveAndRestoreRequireChangedByEvenWhenTheyWouldBeNoOps() {
		Flag active = existingBooleanFlag("active-flag");
		Flag archived = existingBooleanFlag("archived-flag");
		archived.setLifecycle(FlagLifecycle.ARCHIVED);
		when(flagRepository.findByKey("active-flag")).thenReturn(Optional.of(active));
		when(flagRepository.findByKey("archived-flag")).thenReturn(Optional.of(archived));

		assertThat(fieldErrors(() -> flagService.archive("active-flag", null))).containsKey("changedBy");
		assertThat(fieldErrors(() -> flagService.archive("archived-flag", " "))).containsKey("changedBy");
		assertThat(fieldErrors(() -> flagService.restore("archived-flag", null))).containsKey("changedBy");
		assertThat(fieldErrors(() -> flagService.restore("active-flag", ""))).containsKey("changedBy");

		assertThat(active.getLifecycle()).isEqualTo(FlagLifecycle.ACTIVE);
		verify(flagRepository, never()).save(any());
		verify(auditService, never()).record(any(), any(), any(), any());
	}

	@Test
	void archiveOfAnUnknownFlagIsStillNotFoundBeforeChangedByIsChecked() {
		when(flagRepository.findByKey("ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> flagService.archive("ghost", null)).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("code", "FLAG_NOT_FOUND");
	}

	@Test
	void bulkArchiveAndRestoreRequireChangedByBeforeTouchingAnyFlag() {
		assertThat(fieldErrors(() -> flagService.bulkArchive(List.of("a-flag"), null))).containsKey("changedBy");
		assertThat(fieldErrors(() -> flagService.bulkRestore(List.of("a-flag"), " "))).containsKey("changedBy");

		verify(flagRepository, never()).findByKey(any());
		verify(flagRepository, never()).save(any());
	}

	// ---- bulk key lists ----

	@Test
	void bulkOperationsRejectANullOrBlankKeyWithAValidationErrorNotAnNpe() {
		List<String> withNull = new ArrayList<>(List.of("a-flag"));
		withNull.add(null);
		List<String> withBlank = List.of("a-flag", " ");

		assertThat(fieldErrors(() -> flagService.bulkArchive(withNull, "changer"))).containsOnlyKeys("keys[1]");
		assertThat(fieldErrors(() -> flagService.bulkRestore(withBlank, "changer"))).containsOnlyKeys("keys[1]");
		assertThat(fieldErrors(() -> flagService.validateBulkArchive(withNull))).containsOnlyKeys("keys[1]");

		verify(flagRepository, never()).findByKey(any());
	}

	@Test
	void validateBulkArchiveStillReportsAnUnknownKeyAsNotFound() {
		when(flagRepository.findByKey("ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> flagService.validateBulkArchive(List.of("ghost"))).isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("statusCode", 404).hasFieldOrPropertyWithValue("code", "FLAG_NOT_FOUND");
	}

	@Test
	void bulkOperationsAcceptExactlyTheMaximumNumberOfKeysAndRejectOneMore() {
		List<String> atLimit = IntStream.range(0, FlagArchiveService.MAX_BULK_KEYS).mapToObj(index -> "flag-" + index).toList();
		List<String> overLimit = IntStream.rangeClosed(0, FlagArchiveService.MAX_BULK_KEYS).mapToObj(index -> "flag-" + index).toList();

		assertThat(flagService.bulkArchive(atLimit, "changer").notFound()).hasSize(FlagArchiveService.MAX_BULK_KEYS);
		assertThat(flagService.bulkRestore(atLimit, "changer").notFound()).hasSize(FlagArchiveService.MAX_BULK_KEYS);

		assertThat(fieldErrors(() -> flagService.bulkArchive(overLimit, "changer"))).containsOnlyKeys("keys");
		assertThat(fieldErrors(() -> flagService.bulkRestore(overLimit, "changer"))).containsOnlyKeys("keys");
		assertThat(fieldErrors(() -> flagService.validateBulkArchive(overLimit))).containsOnlyKeys("keys");
	}

	@Test
	void bulkOperationsStillRequireAtLeastOneKey() {
		assertThat(fieldErrors(() -> flagService.bulkArchive(null, "changer"))).containsKey("keys");
		assertThat(fieldErrors(() -> flagService.bulkRestore(List.of(), "changer"))).containsKey("keys");
		assertThat(fieldErrors(() -> flagService.validateBulkArchive(null))).containsKey("keys");
	}

	// ---- required fields that used to default silently ----

	@Test
	void updateEnvironmentRequiresEnabledInsteadOfSilentlyDisablingTheEnvironment() {
		Flag flag = existingBooleanFlag("existing-flag");
		flag.getEnvironments().get(EnvironmentKey.PRODUCTION).setEnabled(true);
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));

		Map<String, List<String>> errors = fieldErrors(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION,
				new EnvironmentConfigRequest(null, "off", List.of(), "changer")));

		assertThat(errors).containsKey("enabled");
		assertThat(flag.getEnvironments().get(EnvironmentKey.PRODUCTION).isEnabled()).isTrue();
		verify(flagRepository, never()).save(any());
	}

	@Test
	void aPercentageRolloutStrategyRequiresItsPercentage() {
		Flag flag = existingBooleanFlag("existing-flag");
		when(flagRepository.findByKey("existing-flag")).thenReturn(Optional.of(flag));
		StrategyRequest missing = new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, null, "on",
				Map.of(), BanditAlgorithm.NONE, null);

		Map<String, List<String>> errors = fieldErrors(() -> flagService.updateEnvironment("existing-flag", EnvironmentKey.PRODUCTION,
				new EnvironmentConfigRequest(true, "off", List.of(missing), "changer")));

		assertThat(errors).containsKey("strategies[0].percentage");
	}

	// ---- payload validation ----

	@Test
	void aJsonPayloadWithTrailingGarbageIsRejected() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		for (String payload : List.of("{} x", "{\"a\":1} {\"b\":2}", "[1,2] ]", "{\"a\":")) {
			Map<String, List<String>> errors = fieldErrors(() -> flagService.addVariation("multi-flag",
					new VariationCreateRequest("purple", "Purple", "purple", null, PayloadType.JSON, payload, "changer")));

			assertThat(errors).as(payload).containsKey("payloadValue");
		}

		verify(flagRepository, never()).save(any());
	}

	@Test
	void aWellFormedJsonPayloadIsAccepted() {
		Flag flag = existingMultivariateFlag("multi-flag");
		when(flagRepository.findByKey("multi-flag")).thenReturn(Optional.of(flag));

		Flag saved = flagService.addVariation("multi-flag",
				new VariationCreateRequest("purple", "Purple", "purple", null, PayloadType.JSON, " {\"a\": [1, 2]} ", "changer"));

		assertThat(saved.getVariations()).extracting(Variation::getKey).contains("purple");
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<String>> fieldErrors(ThrowingCallable call) {
		ValidationException thrown = catchThrowableOfType(ValidationException.class, call);

		assertThat(thrown).as("a ValidationException").isNotNull();

		return (Map<String, List<String>>) thrown.getDetails().get("fieldErrors");
	}

	private FlagCreateRequest booleanCreateRequest(String key) {
		return new FlagCreateRequest(key, "New Flag", "description", FlagType.BOOLEAN, "owner",
				List.of(new VariationRequest("off", "Off", "false"), new VariationRequest("on", "On", "true")), List.of(),
				"changer");
	}

	private StrategyRequest rolloutStrategy(int percentage, String servedVariationKey) {
		return new StrategyRequest(null, StrategyType.PERCENTAGE_ROLLOUT, List.of(), null, null, percentage, servedVariationKey);
	}

	private Flag existingBooleanFlag(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName("Existing Flag");
		flag.setType(FlagType.BOOLEAN);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setOwner("owner");
		flag.setDependsOn(new ArrayList<>());
		flag.setVariations(List.of(new Variation("off", "Off", "false"), new Variation("on", "On", "true")));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(false, "off", new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}

	private Flag existingMultivariateFlag(String key) {
		Flag flag = new Flag();
		flag.setKey(key);
		flag.setName("Existing Multivariate Flag");
		flag.setType(FlagType.MULTIVARIATE);
		flag.setLifecycle(FlagLifecycle.ACTIVE);
		flag.setOwner("owner");
		flag.setDependsOn(new ArrayList<>());
		flag.setVariations(new ArrayList<>(List.of(new Variation("red", "Red", "red"), new Variation("blue", "Blue", "blue"))));

		Map<EnvironmentKey, EnvironmentConfig> environments = new EnumMap<>(EnvironmentKey.class);

		for (EnvironmentKey environment : EnvironmentKey.values()) {
			environments.put(environment, new EnvironmentConfig(false, "red", new ArrayList<>()));
		}

		flag.setEnvironments(environments);

		return flag;
	}
}
