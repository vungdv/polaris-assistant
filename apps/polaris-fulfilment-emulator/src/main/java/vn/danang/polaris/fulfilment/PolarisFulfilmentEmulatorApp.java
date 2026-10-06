package vn.danang.polaris.fulfilment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Stateless Fulfilment context: emulated partners claim placed orders and report shipments (Plan 3, F2). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PolarisFulfilmentEmulatorApp {

    public static void main(String[] args) {
        SpringApplication.run(PolarisFulfilmentEmulatorApp.class, args);
    }
}
