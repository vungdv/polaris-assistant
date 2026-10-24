package vn.danang.polaris.testing;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.danang.polaris.testing.fixtures.CleanUnitTest;
import vn.danang.polaris.testing.fixtures.ContainerUnitTest;
import vn.danang.polaris.testing.fixtures.SpringBootedIntegrationTest;
import vn.danang.polaris.testing.fixtures.SpringBootedUnitTest;
import vn.danang.polaris.testing.fixtures.SpringExtendedUnitTest;
import vn.danang.polaris.testing.fixtures.TaggedIntegrationTest;
import vn.danang.polaris.testing.fixtures.TaggedUnitTest;
import vn.danang.polaris.testing.fixtures.UntaggedIntegrationTest;
import vn.danang.polaris.testing.fixtures.WireMockUnitTest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC-1 to AC-4: each rule fails on a violating fixture and passes on a conforming one. */
class TestConventionRulesTest {

	private static JavaClasses of(Class<?>... classes) {
		return new ClassFileImporter().importClasses(classes);
	}

	@ParameterizedTest
	@ValueSource(classes = { SpringBootedUnitTest.class, SpringExtendedUnitTest.class })
	void unitTestWithSpringContextViolatesR1(Class<?> fixture) {
		assertThatThrownBy(() -> TestConventionRules.unitTestsStartNoSpringContext.check(of(fixture)))
				.isInstanceOf(AssertionError.class).hasMessageContaining(fixture.getSimpleName());
	}

	@ParameterizedTest
	@ValueSource(classes = { ContainerUnitTest.class, WireMockUnitTest.class })
	void unitTestWithRealBoundaryViolatesR2(Class<?> fixture) {
		assertThatThrownBy(() -> TestConventionRules.unitTestsOpenNoRealBoundary.check(of(fixture)))
				.isInstanceOf(AssertionError.class).hasMessageContaining(fixture.getSimpleName());
	}

	@Test
	void springFreeUnitTestPassesR1AndR2() {
		JavaClasses clean = of(CleanUnitTest.class);
		assertThatCode(() -> TestConventionRules.unitTestsStartNoSpringContext.check(clean)).doesNotThrowAnyException();
		assertThatCode(() -> TestConventionRules.unitTestsOpenNoRealBoundary.check(clean)).doesNotThrowAnyException();
	}

	@Test
	void integrationTestsAreExemptFromR1AndR2() {
		JavaClasses booted = of(SpringBootedIntegrationTest.class);
		assertThatCode(() -> TestConventionRules.unitTestsStartNoSpringContext.check(booted)).doesNotThrowAnyException();
		assertThatCode(() -> TestConventionRules.unitTestsOpenNoRealBoundary.check(booted)).doesNotThrowAnyException();
	}

	@Test
	void untaggedIntegrationTestViolatesR3() {
		assertThatThrownBy(() -> TestConventionRules.integrationTestsDeclareAspect.check(of(UntaggedIntegrationTest.class)))
				.isInstanceOf(AssertionError.class).hasMessageContaining("UntaggedIntegrationTest");
	}

	@Test
	void taggedIntegrationTestPassesR3AndR4() {
		JavaClasses tagged = of(TaggedIntegrationTest.class);
		assertThatCode(() -> TestConventionRules.integrationTestsDeclareAspect.check(tagged)).doesNotThrowAnyException();
		assertThatCode(() -> TestConventionRules.aspectOnlyOnIntegrationTests.check(tagged)).doesNotThrowAnyException();
	}

	@Test
	void aspectOnNonIntegrationTestViolatesR4() {
		ArchRule rule = TestConventionRules.aspectOnlyOnIntegrationTests;
		assertThatThrownBy(() -> rule.check(of(TaggedUnitTest.class))).isInstanceOf(AssertionError.class)
				.hasMessageContaining("TaggedUnitTest");
	}
}
