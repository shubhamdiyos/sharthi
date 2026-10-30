package com.saathi.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for the integration tests: a real Tomcat on a random port, the real Flyway-migrated
 * schema, and no mocks of either.
 *
 * <p>HTTP goes through the container rather than MockMvc on purpose. The things this module has
 * to prove — that {@code RequestIdFilter} is actually registered, that Boot's own Jackson mapper
 * emits snake_case, that an unmapped path answers 404 in the §10.2 envelope — are all properties
 * of the wiring, and a mock servlet stack can be green while every one of them is broken.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    protected static final JsonMapper JSON = new JsonMapper();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    protected HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    protected HttpResponse<String> get(String path, String requestIdHeader) {
        return send(HttpRequest.newBuilder(uri(path))
            .header("X-Request-Id", requestIdHeader)
            .GET());
    }

    protected HttpResponse<String> post(String path, String body) {
        return post(path, "application/json", body);
    }

    protected HttpResponse<String> post(String path, String contentType, String body) {
        return send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    protected HttpResponse<String> delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    protected JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("request to the running api failed: " + request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
