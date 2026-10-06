package vn.danang.polaris;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;

@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class TestAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.from(PolarisAssistantApp::main).with(TestcontainersConfiguration.class).run(args);
    }
}
