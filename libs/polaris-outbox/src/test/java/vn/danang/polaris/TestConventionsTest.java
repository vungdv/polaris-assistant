package vn.danang.polaris;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import vn.danang.polaris.testing.TestConventionRules;

/** Enforces the unit/integration naming convention on this module's tests (testing guideline §4). */
@AnalyzeClasses(packages = "vn.danang.polaris", importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsTest {

	@ArchTest
	static final ArchTests conventions = ArchTests.in(TestConventionRules.class);
}
