package vn.danang.polaris.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TR-K4 (image side): with this module's production {@code application.yml}, closing the context (what SIGTERM does)
 * lets a request already in flight finish while new requests are refused. The context holds only embedded Tomcat and
 * Spring MVC, configured from {@code src/main/resources/application.yml} alone, so the test needs no infrastructure
 * and fails if the production config stops shutting down gracefully.
 */
@DisplayName("Polaris Core - Graceful shutdown drains in-flight requests")
class GracefulShutdownTest {

    private static final String PRODUCTION_CONFIG = "file:src/main/resources/application.yml";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private ConfigurableApplicationContext context;

    @AfterEach
    void releaseAndClose() {
        SlowEndpoint.release.countDown();
        if (context != null) {
            context.close();
        }
        http.close();
    }

    @Test
    @DisplayName("The production config sets server.shutdown=graceful and a positive shutdown-phase timeout")
    void productionConfig_declaresGracefulShutdown() {
        context = start();
        Environment env = context.getEnvironment();

        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class))
                .isNotNull()
                .isPositive();
    }

    @Test
    @DisplayName("A request in flight when shutdown starts completes with 200 while new requests are refused")
    void inFlightRequest_completesAfterShutdownStarts() throws Exception {
        context = start();
        URI base = URI.create("http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort());

        CompletableFuture<HttpResponse<String>> inFlight = http.sendAsync(
                HttpRequest.newBuilder(base.resolve("/slow")).timeout(WAIT).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(SlowEndpoint.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("request reached the handler").isTrue();

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(context::close);
        awaitNewRequestsRefused(base.resolve("/fast"));

        assertThat(inFlight).as("in-flight request still running").isNotDone();
        assertThat(shutdown).as("shutdown waits for the in-flight request").isNotDone();

        SlowEndpoint.release.countDown();

        HttpResponse<String> response = inFlight.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("done");
        shutdown.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(context.isActive()).isFalse();
    }

    /** Shutdown has started once the connector stops accepting connections. Each probe uses a fresh connection. */
    private static void awaitNewRequestsRefused(URI uri) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try (HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
                probe.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (IOException refused) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("server kept accepting new requests after shutdown started");
    }

    private static ConfigurableApplicationContext start() {
        SlowEndpoint.reset();
        return new SpringApplicationBuilder(WebOnlyApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.config.location=" + PRODUCTION_CONFIG, "server.port=0")
                .run();
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            LifecycleAutoConfiguration.class,
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class})
    @Import(SlowEndpoint.class)
    static class WebOnlyApp {
    }

    @RestController
    static class SlowEndpoint {

        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        static void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @GetMapping("/slow")
        String slow() throws InterruptedException {
            entered.countDown();
            release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            return "done";
        }

        @GetMapping("/fast")
        String fast() {
            return "ok";
        }
    }
}
