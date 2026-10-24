package vn.danang.polaris.testing;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Build-time guard for the unit/integration convention of {@code docs/development/testing-guideline.md} §4.
 * A module applies it with {@code @ArchTest static final ArchTests rules = ArchTests.in(TestConventionRules.class)}.
 */
public final class TestConventionRules {

	private static final DescribedPredicate<JavaClass> UNIT_TEST = simpleNameEndingWith("Test")
			.and(DescribedPredicate.not(simpleNameEndingWith("IntegrationTest"))).as("unit tests (*Test)");

	@ArchTest
	static final ArchRule unitTestsStartNoSpringContext = noClasses().that(UNIT_TEST).should()
			.dependOnClassesThat().resideInAnyPackage("org.springframework.boot.test..", "org.springframework.test.context..")
			.because("a *Test is Spring-free; a test that needs a context is an *IntegrationTest");

	@ArchTest
	static final ArchRule unitTestsOpenNoRealBoundary = noClasses().that(UNIT_TEST).should()
			.dependOnClassesThat().resideInAnyPackage("org.testcontainers..", "com.github.tomakehurst.wiremock..")
			.because("a *Test opens no socket, database or container; a test that does is an *IntegrationTest");

	static final ArchRule integrationTestsDeclareAspect = classes().that().haveSimpleNameEndingWith("IntegrationTest")
			.should().beAnnotatedWith(IntegrationAspect.class)
			.because("an *IntegrationTest declares exactly one aspect");

	/** Existing untagged tests sit in a frozen baseline that only shrinks (Q5 to Q7 tag them). */
	@ArchTest
	static final ArchRule integrationTestsDeclareOneAspect = FreezingArchRule.freeze(integrationTestsDeclareAspect);

	@ArchTest
	static final ArchRule aspectOnlyOnIntegrationTests = classes().that().areAnnotatedWith(IntegrationAspect.class)
			.should().haveSimpleNameEndingWith("IntegrationTest")
			.because("only integration tests declare an aspect");

	private TestConventionRules() {
	}
}
