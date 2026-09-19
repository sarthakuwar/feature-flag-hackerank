package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.featureflag.flags.FlagStreamService;
import com.featureflag.flags.targeting.dto.MobileApplicationRequest;
import com.featureflag.flags.targeting.dto.MobileApplicationUpdateRequest;
import com.featureflag.shared.ApiException;
import com.featureflag.shared.PageParams;
import com.featureflag.shared.ValidationException;

@ExtendWith(MockitoExtension.class)
class MobileApplicationServiceTest {

	@Mock
	private MobileApplicationRepository applicationRepository;

	@Mock
	private TargetingCacheService targetingCacheService;

	@Mock
	private FlagStreamService flagStreamService;

	private MobileApplicationService applicationService;

	@BeforeEach
	void setUp() {
		applicationService = new MobileApplicationService(applicationRepository, targetingCacheService, flagStreamService);
		lenient().when(applicationRepository.save(any(MobileApplication.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void createSavesRefreshesTheCacheAndBroadcastsOnEveryEnvironmentStream() {
		MobileApplication created = applicationService.create(new MobileApplicationRequest("com.acme.app", " Acme app ", " 2.4.0 ", "tester"));

		assertThat(created.getKey()).isEqualTo("com.acme.app");
		assertThat(created.getName()).isEqualTo("Acme app");
		assertThat(created.getMinSupportedVersion()).isEqualTo("2.4.0");
		verify(targetingCacheService).putApplication(created);
		verify(flagStreamService).publishApplicationChanged("com.acme.app", "CREATE");
	}

	@Test
	void createValidatesKeyNameAndMinimumVersion() {
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("", "Name", "1.0.0", "t")))).containsKey("key");
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("has space", "Name", "1.0.0", "t")))).containsKey("key");
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("ok.app", " ", "1.0.0", "t")))).containsKey("name");
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("ok.app", "Name", "", "t")))).containsKey("minSupportedVersion");
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("ok.app", "Name", "banana", "t")))).containsKey("minSupportedVersion");
		assertThat(fieldErrors(() -> applicationService.create(new MobileApplicationRequest("ok.app", "Name", "1.0.0", null)))).containsKey("changedBy");
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void createRejectsADuplicateKeyAsAConflict() {
		when(applicationRepository.existsByKey("com.acme.app")).thenReturn(true);

		assertThatThrownBy(() -> applicationService.create(new MobileApplicationRequest("com.acme.app", "Acme", "1.0.0", "t")))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(409));
	}

	@Test
	void updateChangesNameAndMinimumVersionButNotTheKey() {
		MobileApplication existing = application("com.acme.app", "Acme", "2.0.0");
		when(applicationRepository.findByKey("com.acme.app")).thenReturn(Optional.of(existing));

		MobileApplication updated = applicationService.update("com.acme.app", new MobileApplicationUpdateRequest("Acme mobile", "2.5.0", "tester"));

		assertThat(updated.getKey()).isEqualTo("com.acme.app");
		assertThat(updated.getName()).isEqualTo("Acme mobile");
		assertThat(updated.getMinSupportedVersion()).isEqualTo("2.5.0");
		verify(flagStreamService).publishApplicationChanged("com.acme.app", "UPDATE");
	}

	@Test
	void updateRejectsAnInvalidVersionAndPersistsNothing() {
		when(applicationRepository.findByKey("com.acme.app")).thenReturn(Optional.of(application("com.acme.app", "Acme", "2.0.0")));

		assertThat(fieldErrors(() -> applicationService.update("com.acme.app", new MobileApplicationUpdateRequest("Acme", "nope", "t"))))
				.containsKey("minSupportedVersion");
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void unknownApplicationIsNotFound() {
		when(applicationRepository.findByKey("ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> applicationService.getOrThrow("ghost"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.getCode()).isEqualTo("APPLICATION_NOT_FOUND"));
		assertThatThrownBy(() -> applicationService.delete("ghost", "tester")).isInstanceOf(ApiException.class);
	}

	@Test
	void deleteRemovesTheApplicationEvictsItAndBroadcasts() {
		MobileApplication existing = application("com.acme.app", "Acme", "2.0.0");
		when(applicationRepository.findByKey("com.acme.app")).thenReturn(Optional.of(existing));

		applicationService.delete("com.acme.app", "tester");

		verify(applicationRepository).delete(existing);
		verify(targetingCacheService).evictApplication("com.acme.app");
		verify(flagStreamService).publishApplicationChanged("com.acme.app", "DELETE");
	}

	@Test
	void listAsksTheRepositoryForOnePageSortedByNameThenKey() {
		Page<MobileApplication> repositoryPage = new PageImpl<>(List.of(application("a", "Alpha app", "1.0.0")),
				PageRequest.of(2, 10), 25);
		when(applicationRepository.findPage(any(Pageable.class))).thenReturn(repositoryPage);

		Page<MobileApplication> result = applicationService.list(new PageParams(3, 10));

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(applicationRepository).findPage(pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).as("page 3 is zero-based index 2").isEqualTo(2);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
		assertThat(pageable.getValue().getSort()).containsExactly(Sort.Order.asc("name"), Sort.Order.asc("key"));
		assertThat(result).isSameAs(repositoryPage);
	}

	private static MobileApplication application(String key, String name, String minSupportedVersion) {
		MobileApplication application = new MobileApplication();
		application.setKey(key);
		application.setName(name);
		application.setMinSupportedVersion(minSupportedVersion);

		return application;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> fieldErrors(org.junit.jupiter.api.function.Executable executable) {
		ValidationException thrown = org.junit.jupiter.api.Assertions.assertThrows(ValidationException.class, executable);

		return (Map<String, List<String>>) thrown.getDetails().get("fieldErrors");
	}
}
