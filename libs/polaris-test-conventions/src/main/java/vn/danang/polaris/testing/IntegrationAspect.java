package vn.danang.polaris.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the single aspect an {@code *IntegrationTest} targets (testing guideline §2, §4). Not repeatable, so a
 * class cannot declare two. Enforced by {@link TestConventionRules}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IntegrationAspect {

	Aspect value();
}
