package vn.danang.polaris.testing.fixtures;

import org.springframework.boot.test.context.SpringBootTest;
import vn.danang.polaris.testing.Aspect;
import vn.danang.polaris.testing.IntegrationAspect;

@SpringBootTest
@IntegrationAspect(Aspect.WIRING)
public class SpringBootedIntegrationTest {
}
