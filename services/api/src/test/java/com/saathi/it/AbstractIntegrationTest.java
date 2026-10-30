package com.saathi.it;

import com.saathi.identity.security.IdentityKeys;
import com.saathi.identity.session.SessionCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Autowired
    protected IdentityKeys keys;

    /**
     * Moves a number's OTP challenges out of §12.8's one-hour window.
     *
     * <p>The budget is counted from durable rows on purpose, which means a test that has to sign in
     * as a seeded pilot person shares that budget with every other test that does and with its own
     * earlier runs. Such a test cannot use a fresh number without stopping to be about that person,
     * so the fixture ages the history instead. The throttle itself is asserted by a test that uses a
     * number of its own and never calls this.
     */
    protected void retireOtpHistoryFor(String e164) {
        jdbc.update("UPDATE otp_challenge SET created_at = now() - interval '2 hours' WHERE target_hash = ?",
            keys.otpTargetHash(e164));
    }

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

    /**
     * A mutation as a browser sends one: the session cookie, and the header the CSRF interceptor
     * reads. The JDK client sends {@code Cookie} without restriction, so no mock of the servlet
     * layer stands between these tests and §12.4's double-submit rule.
     */
    protected HttpResponse<String> post(String path, Map<String, String> headers, String body) {
        return send(headers(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)), headers));
    }

    protected HttpResponse<String> put(String path, Map<String, String> headers, String body) {
        return send(headers(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)), headers));
    }

    protected HttpResponse<String> get(String path, Map<String, String> headers) {
        return send(headers(HttpRequest.newBuilder(uri(path)).GET(), headers));
    }

    protected HttpResponse<String> delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    protected JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    /** The value a response set for one cookie, or empty when it did not set that cookie. */
    protected static Optional<String> cookieSet(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
            .map(setCookie -> setCookie.split(";", 2)[0].trim())
            .filter(nameValue -> nameValue.startsWith(name + "="))
            .map(nameValue -> nameValue.substring(name.length() + 1))
            .findFirst();
    }

    /** Every {@code Set-Cookie} line, so the attributes are assertable and not just the value. */
    protected static List<String> setCookieHeaders(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie");
    }

    /** The two cookies a sign-in issues, presented the way a client has to present them again. */
    protected static Map<String, String> asBrowser(HttpResponse<String> signInResponse) {
        return Map.of("Cookie", cookieHeader(signInResponse),
            SessionCredential.CSRF_HEADER, cookieValue(signInResponse, SessionCredential.CSRF_COOKIE_NAME));
    }

    /** The session a response just issued, which is the first half of its own cookie value. */
    protected static String sessionIdOf(HttpResponse<String> signInResponse) {
        String credential = cookieValue(signInResponse, SessionCredential.COOKIE_NAME);
        return credential.substring(0, credential.indexOf('.'));
    }

    protected static String cookieValue(HttpResponse<String> response, String name) {
        return cookieSet(response, name).orElseThrow(
            () -> new IllegalStateException("no " + name + " cookie in " + response.body()));
    }

    protected static String cookieHeader(HttpResponse<String> signInResponse) {
        return SessionCredential.COOKIE_NAME + "=" + cookieValue(signInResponse, SessionCredential.COOKIE_NAME);
    }

    private static HttpRequest.Builder headers(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
        return builder;
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
