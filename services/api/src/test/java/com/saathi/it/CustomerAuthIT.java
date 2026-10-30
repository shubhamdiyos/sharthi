package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.identity.security.IdentityKeys;
import com.saathi.identity.session.SessionCredential;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §10.5.1's customer path over a socket: one factor, the two cookies §12.4 names, the device list
 * §12.5 promises, and the ward a storefront resolves its city from.
 *
 * <p>What is asserted here is mostly invisible from a unit test because it is a property of the
 * assembled request: that a mutation carrying a live session is refused without {@code
 * X-CSRF-Token} and survives the refusal, that the six digits never reach the body carrying the
 * challenge id, that a revoked row leaves the device list instead of appearing in it crossed out.
 *
 * <p>Each test asks for its own phone number. §12.8's five-codes-per-hour is counted from the
 * challenge rows themselves and this database outlives the run, so a shared number would inherit the
 * throttle from the last suite rather than test it. The pilot's own people are used only where the
 * assertion is about the row the seed wrote.
 */
class CustomerAuthIT extends AbstractIntegrationTest {

    private static final String RAMESH = "+919800003421";
    private static final String MEENA = "+919800006001";
    private static final String RAKESH = "+919000007712";

    /** A base per run plus a counter, so no number is reused inside an hour by any run. */
    private static final long RUN_SALT = ThreadLocalRandom.current().nextInt(0, 90_000_000);
    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong();

    @Autowired
    private RecordingOtpProvider otp;

    @Autowired
    private IdentityKeys keys;

    @Autowired
    private Clock clock;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /auth/otp/request
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a code request answers 202 with a challenge id and none of the digits")
    void requestCodeAnswersWithTheChallengeAlone() {
        String phone = freshPhone();
        HttpResponse<String> response = post("/api/v1/auth/otp/request", json("phone", phone));

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode json = body(response);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("challenge_id", "expires_at");
        assertThat(Ulid.isValid(json.get("challenge_id").stringValue())).isTrue();
        assertThat(Instant.parse(json.get("expires_at").stringValue()))
            .as("§12.8: five minutes").isAfter(clock.instant().plus(Duration.ofMinutes(4)));
        assertThat(json.toString())
            .as("§12.8: the digits are never in the answer")
            .doesNotContain(otp.codeFor(phone));
    }

    @Test
    @DisplayName("a challenge row stores two keyed hashes and no recoverable value")
    void challengeRowHoldsNothingRecoverable() {
        String phone = freshPhone();
        HttpResponse<String> response = post("/api/v1/auth/otp/request", json("phone", phone));
        String challengeId = challengeId(response);
        String code = otp.codeFor(phone);

        JsonNode row = challenge(challengeId);
        assertThat(row.get("state").stringValue()).isEqualTo("OPEN");
        assertThat(row.get("target_hash").stringValue())
            .as("§12.8's lookup key is a keyed hash: matchable, never reversible")
            .isEqualTo(keys.otpTargetHash(phone));
        assertThat(row.get("code_hash").stringValue()).isEqualTo(keys.otpCodeHash(challengeId, code));
        assertThat(row.get("code_hash").stringValue()).isNotEqualTo(code).hasSize(64);
        assertThat(row.get("request_id").stringValue())
            .as("the join into the logs is the request id, never the phone")
            .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());

        assertThat(response.body()).doesNotContain(row.get("target_hash").stringValue());
    }

    @Test
    @DisplayName("the answer is the same for a number the platform has never seen")
    void requestCodeDoesNotRevealWhetherThePhoneExists() {
        // The "known" number is made known here rather than taken from the pilot seed, because
        // §12.8's hourly budget counts durable rows per number and a seeded number is shared with
        // the tests that sign in as that person.
        String known = freshPhone();
        signIn(known);

        HttpResponse<String> hasAccount = post("/api/v1/auth/otp/request", json("phone", known));
        HttpResponse<String> neverSeen = post("/api/v1/auth/otp/request", json("phone", freshPhone()));

        assertThat(hasAccount.statusCode()).isEqualTo(neverSeen.statusCode());
        assertThat(body(hasAccount).propertyNames())
            .containsExactlyInAnyOrderElementsOf(body(neverSeen).propertyNames());
    }

    @Test
    @DisplayName("only the two purposes a stranger may ask for are accepted")
    void aPurposeThatNeedsAPrincipalIsRefused() {
        for (String purpose : List.of("STEP_UP", "PHONE_CHANGE", "PASSWORD_RESET")) {
            HttpResponse<String> response = post("/api/v1/auth/otp/request", """
                {"phone": "%s", "purpose": "%s"}""".formatted(freshPhone(), purpose));

            assertThat(response.statusCode()).as(purpose).isEqualTo(422);
            assertThat(error(response).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
            assertThat(error(response).get("details").get("field_errors").get(0).get("field").stringValue())
                .isEqualTo("purpose");
        }

        String phone = freshPhone();
        assertThat(post("/api/v1/auth/otp/request", """
            {"phone": "%s", "purpose": "REGISTRATION"}""".formatted(phone)).statusCode())
            .as("§12.1: signup is the same code path, with the intent stored on the challenge")
            .isEqualTo(202);
        assertThat(jdbc.queryForObject(
            "SELECT purpose FROM otp_challenge WHERE target_hash = ?", String.class, keys.otpTargetHash(phone))
        ).isEqualTo("REGISTRATION");
    }

    @Test
    @DisplayName("a number that is not an Indian mobile is refused before anything is issued")
    void aForeignNumberIsRefusedRatherThanPassedThrough() {
        // A blank field never reaches the normaliser: bean validation answers it first, and its
        // wording is the right one for an empty box. Only a number that looks like an attempt is
        // told why the platform will not hold it.
        for (String unusable : List.of("+14155550100", "12345", "abcdefghij")) {
            HttpResponse<String> response = post("/api/v1/auth/otp/request", json("phone", unusable));

            assertThat(response.statusCode()).as("[%s]", unusable).isEqualTo(422);
            assertThat(error(response).get("details").get("field_errors").get(0).get("field").stringValue())
                .isEqualTo("phone");
            assertThat(error(response).get("details").get("field_errors").get(0).get("message").stringValue())
                .as("[%s]", unusable).isEqualTo("Enter a 10-digit Indian mobile number.");
        }

        HttpResponse<String> blank = post("/api/v1/auth/otp/request", json("phone", ""));
        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(error(blank).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("phone");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM otp_challenge WHERE target_hash = ?", Integer.class,
            keys.otpTargetHash("+14155550100"))).as("nothing was issued").isZero();
    }

    @Test
    @DisplayName("five codes an hour for one number, and the sixth is refused")
    void hourlyCodeThrottleIsFive() {
        String phone = freshPhone();
        for (int i = 1; i <= 5; i++) {
            assertThat(post("/api/v1/auth/otp/request", json("phone", phone)).statusCode())
                .as("request %d", i).isEqualTo(202);
        }

        HttpResponse<String> sixth = post("/api/v1/auth/otp/request", json("phone", phone));
        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(error(sixth).get("code").stringValue()).isEqualTo("OTP_THROTTLED");
    }

    @Test
    @DisplayName("the newest code is the one that works, and the retired one says so")
    void aSupersededChallengeIsExpiredRatherThanValid() {
        String phone = freshPhone();
        String first = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));
        String second = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));
        assertThat(first).isNotEqualTo(second);

        HttpResponse<String> stale = verify(phone, first, "123456");
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(error(stale).get("code").stringValue())
            .as("§12.8: the old code is retired, not left open beside the new one").isEqualTo("OTP_EXPIRED");

        assertThat(verify(phone, second, otp.codeFor(phone)).statusCode()).isEqualTo(200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /auth/otp/verify
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("five wrong codes spend a challenge, and the right code cannot bring it back")
    void attemptCapCannotBeBypassed() {
        String phone = freshPhone();
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));
        String real = otp.codeFor(phone);

        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<String> wrong = verify(phone, challengeId, wrongCode(real, attempt));

            assertThat(wrong.statusCode()).as("attempt %d", attempt).isEqualTo(401);
            assertThat(error(wrong).get("code").stringValue()).isEqualTo("OTP_INVALID");
            assertThat(error(wrong).get("message").stringValue()).isEqualTo("That code is not right.");
        }
        assertThat(challenge(challengeId).get("state").stringValue()).isEqualTo("FAILED");

        HttpResponse<String> correct = verify(phone, challengeId, real);
        assertThat(correct.statusCode()).isEqualTo(401);
        assertThat(error(correct).get("message").stringValue())
            .as("§12.8's cap is the whole point of counting attempts")
            .isEqualTo("Too many wrong attempts for that code.");
        assertThat(challenge(challengeId).get("attempts").intValue())
            .as("the counter survived the refusals it produced — the reason verify returns a value")
            .isEqualTo(5);
    }

    @Test
    @DisplayName("a challenge is bound to its phone and says nothing more than 'not right'")
    void aChallengeCannotBeRedeemedForAnotherNumber() {
        String holder = freshPhone();
        String intruder = freshPhone();
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", holder)));

        HttpResponse<String> response = verify(intruder, challengeId, otp.codeFor(holder));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("OTP_INVALID");
        // The same string a mistyped code gets (§12.2): "that challenge was not sent to this phone"
        // is a fact about the platform an attacker should not get to confirm.
        assertThat(error(response).get("message").stringValue()).isEqualTo("That code is not right.");
    }

    @Test
    @DisplayName("a redeemed code cannot be redeemed again")
    void aChallengeIsSingleUse() {
        String phone = freshPhone();
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));
        String code = otp.codeFor(phone);

        assertThat(verify(phone, challengeId, code).statusCode()).isEqualTo(200);

        HttpResponse<String> replay = verify(phone, challengeId, code);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(error(replay).get("code").stringValue()).isEqualTo("OTP_ALREADY_CONSUMED");
        assertThat(challenge(challengeId).get("state").stringValue()).isEqualTo("CONSUMED");
    }

    @Test
    @DisplayName("an unknown challenge id is indistinguishable from a wrong code")
    void idsAreNotEnumerable() {
        HttpResponse<String> response = verify(freshPhone(), Ulid.next(clock), "123456");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("OTP_INVALID");
        assertThat(error(response).get("message").stringValue()).isEqualTo("That code is not right.");
    }

    @Test
    @DisplayName("a code is redeemed only by the body that names the phone, the id and the digits")
    void verifyNeedsEveryFieldItDocuments() {
        String phone = freshPhone();
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));

        HttpResponse<String> missingPhone = post("/api/v1/auth/otp/verify", """
            {"challenge_id": "%s", "code": "%s"}""".formatted(challengeId, otp.codeFor(phone)));
        assertThat(missingPhone.statusCode()).isEqualTo(422);
        assertThat(error(missingPhone).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("phone");

        HttpResponse<String> shortCode = post("/api/v1/auth/otp/verify", """
            {"phone": "%s", "challenge_id": "%s", "code": "123"}""".formatted(phone, challengeId));
        assertThat(shortCode.statusCode()).isEqualTo(422);
        assertThat(error(shortCode).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("code");

        HttpResponse<String> smuggled = post("/api/v1/auth/otp/verify", """
            {"phone": "%s", "challenge_id": "%s", "code": "%s", "admin": true}"""
            .formatted(phone, challengeId, otp.codeFor(phone)));
        assertThat(smuggled.statusCode())
            .as("§10.1: an unrecognised field is refused, not ignored").isEqualTo(422);

        // The challenge above is still open, so the well-formed request can still succeed.
        assertThat(verify(phone, challengeId, otp.codeFor(phone)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("one number is one customer, however it was typed")
    void spellingDoesNotMakeASecondPerson() {
        String digits = freshPhone().substring(3);
        String withCountryCode = "91 " + digits;
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", withCountryCode)));

        HttpResponse<String> signedIn = verify("0" + digits, challengeId, otp.codeFor("+91" + digits));

        assertThat(signedIn.statusCode())
            .as("§12.1: country code without a +, and the trunk 0, are the same ten digits")
            .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE phone = ?",
            Integer.class, "+91" + digits)).isEqualTo(1);
        assertThat(body(signedIn).get("principal").get("masked_contact").stringValue())
            .isEqualTo(masked("+91" + digits));
    }

    @Test
    @DisplayName("a code redeems into a session, an account and the two cookies §12.4 names")
    void verifyIssuesTheSessionAndBothCookies() {
        String phone = freshPhone();
        HttpResponse<String> response = signIn(phone);

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> cookies = setCookieHeaders(response);
        assertThat(cookies).hasSize(2);
        assertThat(cookieValue(response, SessionCredential.COOKIE_NAME))
            .as("<26 Crockford>.<64 hex>: both halves opaque")
            .matches("^[0-9A-Z]{26}\\.[0-9a-f]{64}$");
        cookies.forEach(cookie -> {
            assertThat(cookie).contains("SameSite=Lax").contains("Path=/");
            if (cookie.startsWith(SessionCredential.CSRF_COOKIE_NAME + "=")) {
                assertThat(cookie).as("the page has to read this one to copy it into a header")
                    .doesNotContain("HttpOnly");
            } else {
                assertThat(cookie).as("a script that can read this one can replay it").contains("HttpOnly");
            }
        });

        JsonNode json = body(response);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "principal", "scopes", "permissions", "step_up", "flags");
        assertThat(json.get("principal").get("kind").stringValue()).isEqualTo("CUSTOMER");
        assertThat(json.get("principal").get("masked_contact").stringValue()).isEqualTo(masked(phone));
        assertThat(json.get("principal").has("display_name"))
            .as("§10.1: an absent field, not a null one").isFalse();
        assertThat(json.get("scopes").size()).isZero();
        assertThat(json.get("permissions").size())
            .as("V3 seeds no permission rows, so /me promises nothing it does not enforce").isZero();
        assertThat(json.get("step_up").get("required_for").size()).isZero();
        assertThat(json.get("flags").get("cash_on_delivery").booleanValue()).isTrue();

        String sessionId = sessionIdOf(response);
        Map<String, Object> session = jdbc.queryForMap("SELECT principal_kind, tenant_scope::text AS tenant_scope, "
            + "ward_scope::text AS ward_scope, revoked_at::text AS revoked_at "
            + "FROM user_session WHERE id = ?", sessionId);
        assertThat(session.get("principal_kind")).isEqualTo("CUSTOMER");
        assertThat(session.get("tenant_scope")).isNull();
        assertThat(session.get("ward_scope")).isNull();
        assertThat(session.get("revoked_at")).isNull();

        // §12.1's stated intent is a row at the request; §10.5.1's identical answer for an unknown
        // phone puts the creation where the code is redeemed instead. Recorded on SignInService.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE phone = ?",
            Integer.class, phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("a staff number cannot be traded for a session on the phone alone")
    void theCustomerRouteRefusesStaff() {
        retireOtpHistoryFor(MEENA);
        // Meena is shared pilot data, and another suite signs her in through the staff door, so what
        // this test can own is not "she has no sessions" but "this refusal wrote no new one".
        int sessionsBefore = jdbc.queryForObject("SELECT count(*) FROM user_session WHERE user_account_id = ("
            + "SELECT id FROM user_account WHERE phone = ?)", Integer.class, MEENA);
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", MEENA)));

        HttpResponse<String> response = verify(MEENA, challengeId, otp.codeFor(MEENA));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("MFA_REQUIRED");
        assertThat(error(response).get("message").stringValue())
            .as("§12.1: accurate, because the code was right and a vague answer would be retried")
            .isEqualTo("Sign in with your email and password.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_session WHERE user_account_id = ("
            + "SELECT id FROM user_account WHERE phone = ?)", Integer.class, MEENA))
            .as("§12.4: the row decides access, and this one was never written").isEqualTo(sessionsBefore);
    }

    @Test
    @DisplayName("a delivery partner is not staff here, and gets the twelve-hour session §12.4 gives riders")
    void aRiderSignInIsOneFactorAndTwelveHours() {
        HttpResponse<String> response = signIn(RAKESH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("principal").get("kind").stringValue()).isEqualTo("RIDER");
        assertThat(body(response).get("principal").get("display_name").stringValue())
            .isEqualTo("Rakesh S. (R-104)");

        Map<String, Object> session = jdbc.queryForMap("SELECT EXTRACT(EPOCH FROM (idle_expires_at - created_at))::bigint"
            + " AS idle_seconds, EXTRACT(EPOCH FROM (absolute_expires_at - created_at))::bigint AS absolute"
            + " FROM user_session WHERE id = ?", sessionIdOf(response));
        assertThat(((Number) session.get("idle_seconds")).longValue())
            .as("§12.4: a rider's phone is a shared device, so idle and absolute are the same wall")
            .isEqualTo(43_200L);
        assertThat(((Number) session.get("absolute")).longValue()).isEqualTo(43_200L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /me, CSRF and POST /auth/logout
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /me needs a session, and a dead cookie still leaves the public reads alone")
    void meNeedsASessionAndCitiesDoesNot() {
        HttpResponse<String> signedIn = signIn(freshPhone());
        String cookie = cookieHeader(signedIn);

        HttpResponse<String> anonymous = get("/api/v1/me");
        assertThat(anonymous.statusCode()).as("no cookie").isEqualTo(401);
        assertThat(error(anonymous).get("code").stringValue()).isEqualTo("UNAUTHENTICATED");

        HttpResponse<String> me = get("/api/v1/me", Map.of("Cookie", cookie));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me).get("principal").get("masked_contact").stringValue())
            .isEqualTo(body(signedIn).get("principal").get("masked_contact").stringValue());

        HttpResponse<String> garbage = get("/api/v1/me", Map.of("Cookie", "saathi_session=not-a-credential"));
        assertThat(garbage.statusCode()).isEqualTo(401);

        // §12.4's rule is that the row decides, and the filter's corollary is that an unusable cookie
        // makes a request anonymous rather than rejected: PRD §8.1's first screen is a public read
        // that a stale session must not break.
        assertThat(get("/api/v1/cities", Map.of("Cookie", "saathi_session=not-a-credential")).statusCode())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("a mutation with a live session and no CSRF token is refused without ending the session")
    void loginCsrfIsRefusedAndTheSessionSurvivesIt() {
        HttpResponse<String> signedIn = signIn(freshPhone());
        String cookie = cookieHeader(signedIn);

        HttpResponse<String> refused = post("/api/v1/auth/logout", Map.of("Cookie", cookie), "");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(error(refused).get("code").stringValue())
            .as("the caller is known, the request is not").isEqualTo("FORBIDDEN");

        assertThat(get("/api/v1/me", Map.of("Cookie", cookie)).statusCode())
            .as("a refusal is not a revocation").isEqualTo(200);

        HttpResponse<String> loggedOut = post("/api/v1/auth/logout", asBrowser(signedIn), "");
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        setCookieHeaders(loggedOut).forEach(setCookie ->
            assertThat(setCookie).as("§12.4: both cookies are told to go").contains("Max-Age=0"));

        assertThat(get("/api/v1/me", Map.of("Cookie", cookie)).statusCode())
            .as("the row, not the cookie, decided").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM user_session WHERE id = ?",
            String.class, sessionIdOf(signedIn))).isEqualTo("Signed out");
    }

    @Test
    @DisplayName("a CSRF token minted for another session is refused")
    void aCsrfTokenIsBoundToTheSessionItWasIssuedWith() {
        HttpResponse<String> first = signIn(freshPhone());
        HttpResponse<String> second = signIn(freshPhone());

        Map<String, String> mixed = Map.of("Cookie", cookieHeader(first),
            SessionCredential.CSRF_HEADER, cookieValue(second, SessionCredential.CSRF_COOKIE_NAME));

        assertThat(post("/api/v1/auth/logout", mixed, "").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/me", Map.of("Cookie", cookieHeader(first))).statusCode()).isEqualTo(200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /auth/sessions and POST /auth/sessions/{id}/revoke
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the device list is a page, marks exactly one device as this one, and hides revoked rows")
    void deviceListShowsOpenSessions() {
        String phone = freshPhone();
        HttpResponse<String> first = signIn(phone);
        HttpResponse<String> second = signIn(phone);
        String cookie = cookieHeader(second);

        JsonNode page = body(get("/api/v1/auth/sessions", Map.of("Cookie", cookie)));
        assertThat(page.propertyNames()).containsExactly("data", "page");
        assertThat(page.get("page").propertyNames()).containsExactlyInAnyOrder("has_more", "limit");
        assertThat(page.get("page").has("next_cursor"))
            .as("§10.3: a cursor to follow exists only when there is something to follow").isFalse();
        assertThat(page.get("page").get("limit").intValue()).isEqualTo(20);
        assertThat(page.get("page").get("has_more").booleanValue()).isFalse();

        List<JsonNode> rows = page.get("data").valueStream().toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> row.get("current").booleanValue()).count())
            .as("the caller's own device is the one making the call").isEqualTo(1);
        rows.forEach(row -> {
            assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "device_label", "ip_hash",
                "last_seen_at", "idle_expires_at", "absolute_expires_at", "expired", "current");
            assertThat(row.get("expired").booleanValue()).isFalse();
            assertThat(row.get("ip_hash").stringValue())
                .as("§13.4: the field that stands in for an address is a keyed hash, never one")
                .matches("^[0-9a-f]{64}$");
        });
        assertThat(page.toString())
            .as("§10: the phone is not in this response, and §13.4's hash is not worth leaking either")
            .doesNotContain(phone, keys.otpTargetHash(phone));

        assertThat(post("/api/v1/auth/sessions/" + sessionIdOf(first) + "/revoke",
            withCookie(cookie, cookieValue(second, SessionCredential.CSRF_COOKIE_NAME)), "").statusCode())
            .as("a principal may end their own other devices").isEqualTo(204);

        assertThat(body(get("/api/v1/auth/sessions", Map.of("Cookie", cookie))).get("data").size())
            .as("§10.5.1 lists the devices that exist now").isEqualTo(1);
    }

    @Test
    @DisplayName("a device that no longer opens a door stays listed, and says so")
    void anExpiredSessionIsStillShown() {
        String phone = freshPhone();
        HttpResponse<String> expiring = signIn(phone);
        HttpResponse<String> live = signIn(phone);

        // V2's session_expiry_ordered CHECK will not accept an absolute expiry below created_at,
        // so ageing a row moves its birth with it. What the list has to answer for is a row the
        // server still keeps that no longer opens a door — how long ago it was issued is not the
        // assertion.
        jdbc.update("UPDATE user_session SET created_at = now() - interval '5 minutes', "
            + "idle_expires_at = now() - interval '4 minutes', "
            + "absolute_expires_at = now() - interval '4 minutes' WHERE id = ?", sessionIdOf(expiring));

        List<JsonNode> rows = body(get("/api/v1/auth/sessions", Map.of("Cookie", cookieHeader(live))))
            .get("data").valueStream().toList();
        JsonNode dead = rows.stream()
            .filter(row -> row.get("id").stringValue().equals(sessionIdOf(expiring)))
            .findFirst().orElseThrow();

        assertThat(dead.get("expired").booleanValue())
            .as("§12.5: 'still open somewhere' and 'not used in weeks' are different answers").isTrue();
        assertThat(rows).filteredOn(row -> row.get("expired").booleanValue()).hasSize(1);
    }

    @Test
    @DisplayName("the cursor is opaque, pages the list, and an invented offset is refused")
    void deviceListPagesWithACursor() {
        String phone = freshPhone();
        signIn(phone);
        HttpResponse<String> live = signIn(phone);
        String cookie = cookieHeader(live);

        JsonNode small = body(get("/api/v1/auth/sessions?limit=1", Map.of("Cookie", cookie)));
        assertThat(small.get("data").size()).isEqualTo(1);
        assertThat(small.get("page").get("has_more").booleanValue()).isTrue();
        String cursor = small.get("page").get("next_cursor").stringValue();
        assertThat(cursor).matches("^[A-Za-z0-9_-]+$");

        JsonNode next = body(get("/api/v1/auth/sessions?limit=1&cursor=" + cursor, Map.of("Cookie", cookie)));
        assertThat(next.get("data").get(0).get("id").stringValue())
            .as("the second page is the other row, not a repeat")
            .isNotEqualTo(small.get("data").get(0).get("id").stringValue());
        assertThat(next.get("page").get("has_more").booleanValue()).isFalse();

        HttpResponse<String> badLimit = get("/api/v1/auth/sessions?limit=0", Map.of("Cookie", cookie));
        assertThat(badLimit.statusCode()).isEqualTo(422);
        assertThat(error(badLimit).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("limit");

        HttpResponse<String> offset = get("/api/v1/auth/sessions?cursor=1", Map.of("Cookie", cookie));
        assertThat(offset.statusCode()).isEqualTo(422);
        assertThat(error(offset).get("details").get("field_errors").get(0).get("field").stringValue())
            .as("§10.3 bans OFFSET paging, so a number is not one of this endpoint's values")
            .isEqualTo("cursor");
    }

    @Test
    @DisplayName("another principal's device is not found rather than forbidden")
    void forceLogoutFailsClosed() {
        HttpResponse<String> ramesh = signIn(RAMESH);
        HttpResponse<String> other = signIn(freshPhone());
        String otherSession = sessionIdOf(other);

        HttpResponse<String> response = post("/api/v1/auth/sessions/" + otherSession + "/revoke",
            asBrowser(ramesh), "");

        assertThat(response.statusCode()).as("§4.3-5: 403 would confirm the device exists").isEqualTo(404);
        assertThat(error(response).get("code").stringValue()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/v1/me", Map.of("Cookie", cookieHeader(other))).statusCode())
            .as("and nothing happened to it").isEqualTo(200);

        assertThat(post("/api/v1/auth/sessions/" + otherSession + "/revoke", asBrowser(other), "")
            .statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/me", Map.of("Cookie", cookieHeader(other))).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("revoking the session that is making the call clears the cookies it was sent with")
    void selfRevocationLooksLikeALogout() {
        HttpResponse<String> signedIn = signIn(freshPhone());

        HttpResponse<String> revoked = post("/api/v1/auth/sessions/" + sessionIdOf(signedIn) + "/revoke",
            asBrowser(signedIn), "");

        assertThat(revoked.statusCode()).isEqualTo(204);
        setCookieHeaders(revoked).forEach(setCookie -> assertThat(setCookie).contains("Max-Age=0"));
    }

    @Test
    @DisplayName("an unknown session id is refused with 404 and no explanation")
    void revokingNothingIsNotFound() {
        HttpResponse<String> signedIn = signIn(freshPhone());

        HttpResponse<String> response = post("/api/v1/auth/sessions/" + Ulid.next(clock) + "/revoke",
            asBrowser(signedIn), "");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response).get("message").stringValue()).isEqualTo("Not found in the caller's scope.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /me/ward
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("binding a ward moves the session's scope and answers a fresh PrincipalView")
    void wardBindingIsTheCustomerSCityChoice() {
        HttpResponse<String> signedIn = signIn(freshPhone());
        String cookie = cookieHeader(signedIn);

        HttpResponse<String> bound = put("/api/v1/me/ward", asBrowser(signedIn), """
            {"city_code": "siwan", "ward_no": 7}""");

        assertThat(bound.statusCode()).isEqualTo(200);
        JsonNode scope = body(bound).get("scopes").get(0);
        assertThat(scope.propertyNames()).containsExactlyInAnyOrder("tenant_id", "tenant_code", "ward_id");
        assertThat(scope.get("tenant_code").stringValue()).isEqualTo("siwan");
        String wardId = jdbc.queryForObject("SELECT w.id FROM service_ward w JOIN tenant t ON t.id = w.tenant_id"
            + " WHERE t.code = 'siwan' AND w.ward_no = 7", String.class);
        assertThat(scope.get("ward_id").stringValue()).isEqualTo(wardId);
        assertThat(scope.has("merchant_id")).as("§10.1: absent rather than null").isFalse();

        JsonNode me = body(get("/api/v1/me", Map.of("Cookie", cookie)));
        assertThat(me.get("scopes").get(0).get("tenant_code").stringValue())
            .as("the row decides the next request, not the response that wrote it").isEqualTo("siwan");
        assertThat(jdbc.queryForObject("SELECT tenant_scope FROM user_session WHERE id = ?", String.class,
            sessionIdOf(signedIn)))
            .isEqualTo(jdbc.queryForObject("SELECT id FROM tenant WHERE code = 'siwan'", String.class));

        // A second bind is an ordinary change of mind, and it replaces rather than accumulates.
        assertThat(put("/api/v1/me/ward", withCookie(cookie,
            cookieValue(signedIn, SessionCredential.CSRF_COOKIE_NAME)), """
            {"city_code": "siwan", "ward_no": 12}""").statusCode()).isEqualTo(200);
        assertThat(body(get("/api/v1/me", Map.of("Cookie", cookie))).get("scopes").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a ward is bound by the public pair, and every wrong half of it is refused")
    void wardBindingRefusesWhatItCannotResolve() {
        HttpResponse<String> signedIn = signIn(freshPhone());
        Map<String, String> browser = asBrowser(signedIn);

        assertThat(put("/api/v1/me/ward", browser, """
            {"city_code": "nagpur", "ward_no": 1}""").statusCode()).isEqualTo(409);
        assertThat(put("/api/v1/me/ward", browser, """
            {"city_code": "barauli", "ward_no": 1}""").statusCode())
            .as("a suspended city is not a place to shop in").isEqualTo(409);
        assertThat(put("/api/v1/me/ward", browser, """
            {"city_code": "siwan", "ward_no": 3}""").statusCode())
            .as("ward 3 is Mashaun's number, and the pair is the address").isEqualTo(409);

        HttpResponse<String> noWard = put("/api/v1/me/ward", browser, """
            {"city_code": "siwan"}""");
        assertThat(noWard.statusCode()).isEqualTo(422);
        assertThat(error(noWard).get("details").get("field_errors").get(0).get("field").stringValue())
            .isEqualTo("ward_no");

        HttpResponse<String> anonymous = put("/api/v1/me/ward", Map.of(), """
            {"city_code": "siwan", "ward_no": 7}""");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(error(anonymous).get("code").stringValue()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("the ward a customer binds is a ward the platform serves, not one it names")
    void bindingUsesTheSameRulesAsServiceability() {
        HttpResponse<String> signedIn = signIn(freshPhone());
        String digits = freshPhone().substring(3);

        HttpResponse<String> response = put("/api/v1/me/ward", asBrowser(signedIn), """
            {"city_code": "SIWAN", "ward_no": 4}""");

        assertThat(response.statusCode()).as("one city, whichever way it is spelled").isEqualTo(200);
        assertThat(body(response).get("scopes").get(0).get("ward_id").stringValue())
            .isEqualTo(jdbc.queryForObject("SELECT id FROM service_ward WHERE ward_no = 4 "
                + "AND tenant_id = (SELECT id FROM tenant WHERE code = 'siwan')", String.class));
        assertThat(digits).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** A signed-in customer, as much of the pilot's history as the test needs and no more. */
    private HttpResponse<String> signIn(String phone) {
        // §12.8 counts requests per number from durable rows, so a seeded person is a budget every
        // test that signs in as them shares. For a fresh number this retires nothing.
        retireOtpHistoryFor(phone);
        String challengeId = challengeId(post("/api/v1/auth/otp/request", json("phone", phone)));
        HttpResponse<String> response = verify(phone, challengeId, otp.codeFor(phone));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("sign-in for a test failed: " + response.body());
        }
        return response;
    }

    private HttpResponse<String> verify(String phone, String challengeId, String code) {
        return post("/api/v1/auth/otp/verify", """
            {"phone": "%s", "challenge_id": "%s", "code": "%s"}""".formatted(phone, challengeId, code));
    }

    private String challengeId(HttpResponse<String> request) {
        return body(request).get("challenge_id").stringValue();
    }

    /** The pair a browser would send, spelled out for the cases {@code asBrowser} cannot: two
      different sign-ins, one cookie and the other one's token. */
    private static Map<String, String> withCookie(String cookieHeader, String csrfToken) {
        return Map.of("Cookie", cookieHeader, SessionCredential.CSRF_HEADER, csrfToken);
    }

    /** §10's mask, computed here rather than imported so the test asserts the wire, not the helper. */
    private static String masked(String e164) {
        String digits = e164.substring(3);
        return "+91 " + digits.substring(0, 2) + "XXXXXX" + digits.substring(8);
    }

    private static String wrongCode(String real, int attempt) {
        return String.format("%06d", (Long.parseLong(real) + attempt * 111_111L) % 1_000_000L);
    }

    private static String json(String key, String value) {
        return "{\"" + key + "\": \"" + value + "\"}";
    }

    private static String freshPhone() {
        return String.format("+9198%08d", RUN_SALT + PHONE_SEQUENCE.incrementAndGet());
    }

    private JsonNode challenge(String id) {
        return JSON.valueToTree(jdbc.queryForMap("SELECT state, attempts, target_hash, code_hash, request_id "
            + "FROM otp_challenge WHERE id = ?", id));
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
