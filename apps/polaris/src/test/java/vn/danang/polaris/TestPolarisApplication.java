package vn.danang.polaris;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class TestPolarisApplication {

    public static void main(String[] args) {
        SpringApplication.from(PolarisApp::main).with(TestcontainersConfiguration.class).run(args);
    }
}
