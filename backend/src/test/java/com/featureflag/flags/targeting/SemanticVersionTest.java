package com.featureflag.flags.targeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticVersionTest {

	@Test
	void parsesFullVersion() {
		SemanticVersion version = SemanticVersion.parse("2.14.3").orElseThrow();

		assertThat(version.major()).isEqualTo(2);
		assertThat(version.minor()).isEqualTo(14);
		assertThat(version.patch()).isEqualTo(3);
		assertThat(version.prerelease()).isEmpty();
	}

	@Test
	void missingMinorAndPatchDefaultToZero() {
		assertThat(SemanticVersion.parse("3").orElseThrow()).isEqualByComparingTo(SemanticVersion.parse("3.0.0").orElseThrow());
		assertThat(SemanticVersion.parse("3.1").orElseThrow()).isEqualByComparingTo(SemanticVersion.parse("3.1.0").orElseThrow());
	}

	@Test
	void leadingVAndBuildMetadataAreTolerated() {
		assertThat(SemanticVersion.parse("v1.2.3+build.7").orElseThrow()).isEqualByComparingTo(SemanticVersion.parse("1.2.3").orElseThrow());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  ", "abc", "1.2.3.4", "1..2", "1.2.x", "-1.0.0", "1.0.0-", "99999999999999999999.0.0" })
	void rejectsUnparseableVersions(String raw) {
		assertThat(SemanticVersion.parse(raw)).isEmpty();
	}

	@Test
	void comparesNumericPartsNumericallyNotAsStrings() {
		assertThat(SemanticVersion.parse("1.9.0").orElseThrow()).isLessThan(SemanticVersion.parse("1.10.0").orElseThrow());
		assertThat(SemanticVersion.parse("2.0.0").orElseThrow()).isGreaterThan(SemanticVersion.parse("1.99.99").orElseThrow());
	}

	@Test
	void followsTheSemverPrecedenceExample() {
		// The ordering chain given in the semver 2.0 spec, section 11.
		List<String> ascending = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
				"1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");

		for (int index = 0; index < ascending.size() - 1; index++) {
			SemanticVersion lower = SemanticVersion.parse(ascending.get(index)).orElseThrow();
			SemanticVersion higher = SemanticVersion.parse(ascending.get(index + 1)).orElseThrow();

			assertThat(lower).as(ascending.get(index) + " < " + ascending.get(index + 1)).isLessThan(higher);
		}
	}

	@Test
	void buildMetadataDoesNotAffectPrecedence() {
		assertThat(SemanticVersion.parse("1.0.0+a").orElseThrow()).isEqualByComparingTo(SemanticVersion.parse("1.0.0+b").orElseThrow());
	}
}
