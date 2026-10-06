package vn.danang.polaris.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableSpringDataWebSupport
public class WebConfig {
    @Bean
    public Clock clock(){
        return Clock.systemUTC();
    }
}