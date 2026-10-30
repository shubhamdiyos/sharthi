package com.saathi.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saathi.identity.security.PasswordVault;
import com.saathi.shared.id.Ulid;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §12.1's staff sign-in: two factors, one endpoint, called twice.
 *
 * <p>Everything the customer suite proves about a session, this suite proves about a {@code Grant} —
 * that the second call is the only one that can open a door, that the number the code goes to is read
 * from the row the password unlocked and never from the request, and that an answer can be given
 * without saying whether the address named in it exists.
 *
 * <p>The pilot's staff rows carry no password on purpose: seeding one would be a dev password in a
 * repository, which §14 and the secrets gate both forbid. So these tests hash a passphrase they
 * invent at runtime, write it into the row for the duration of one test, and put the row back
 * afterwards — the schema's {@code $argon2id$v=} CHECK is what proves the write went through the
 * same vault every later module will use.
 */
@DisplayName("§12.1 staff sign-in")
class StaffAuthIT extends AbstractIntegrationTest {

    private static final String MEENA = "meena@saathi.example";
    private static final String MEENA_PHONE = "+919800006001";
    private static final String ANIL = "anil@saathi.example";
    private static final String ANIL_PHONE = "+919800006002";

    /** §12.2's only password rule is length, so this is a legal passphrase and not a weak one. */
    private static final String PASSWORD = "warehouse-lease-2026";
    private static final String WRONG_PASSWORD = "wrong-warehouse-2026";

    /** A customer that happens to hold an email: legal under V2, and not a staff account. */
    private static final String CUSTOMER_EMAIL = "naseem@saathi.example";
    private static final String CUSTOMER_PHONE = "+919800099111";

    @Autowired
    private RecordingOtpProvider otp;

    @Autowired
    private PasswordVault vault;

    @Autowired
    private Clock clock;

    @BeforeEach
    void giveTheSeedStaffPasswordsTheyDeliberatelyLack() {
        restoreSeedRows();
        setVaultedPassword(MEENA);
        setVaultedPassword(ANIL);
        // Both numbers are asked for codes by more than one test, and §12.8's five-an-hour budget is
        // counted from durable rows, so the budget is reset for the seeded people before each test.
        retireOtpHistoryFor(MEENA_PHONE);
        retireOtpHistoryFor(ANIL_PHONE);
    }

    @AfterEach
    void takeThemBackOff() {
        jdbc.update("DELETE FROM user_account WHERE email = ?", CUSTOMER_EMAIL);
        restoreSeedRows();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The handshake
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the password answers what is missing, and the code answers with a session")
    void twoFactorsThroughOneEndpoint() {
        HttpResponse<String> first = login(MEENA, PASSWORD);

        JsonNode details = mfaRequired(first);
        String challengeId = details.get("challenge_id").stringValue();
        assertThat(Ulid.isValid(challengeId)).isTrue();
        assertThat(Instant.parse(details.get("expires_at").stringValue()))
            .as("§12.8: the code is on the five-minute clock, not on this endpoint's patience")
            .isAfter(clock.instant().plus(Duration.ofMinutes(4)));
        assertThat(jdbc.queryForObject("SELECT target_hash FROM otp_challenge WHERE id = ?",
            String.class, challengeId))
            .as("§12.1: the second factor goes to the number on file, which no request could name")
            .isEqualTo(keys.otpTargetHash(MEENA_PHONE));
        assertThat(liveSessionsFor(MEENA)).as("the first call is not a partial sign-in").isZero();

        HttpResponse<String> second = login(MEENA, PASSWORD, challengeId, otp.codeFor(MEENA_PHONE));

        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode me = body(second);
        assertThat(me.propertyNames())
            .containsExactlyInAnyOrder("principal", "scopes", "permissions", "step_up", "flags");
        assertThat(me.get("principal").get("kind").stringValue()).isEqualTo("OPERATOR");
        assertThat(me.get("principal").get("display_name").stringValue()).isEqualTo("Meena Kumari");
        assertThat(me.get("principal").get("masked_contact").stringValue())
            .as("§10.4: a staffer recognises themselves by the address they signed in with, not the "
                + "recovery phone §12.7 keeps for the outage path")
            .isEqualTo("m***a@saathi.example");
        assertThat(me.get("scopes").size()).isEqualTo(1);
        assertThat(me.get("scopes").get(0).propertyNames())
            .as("§10.1: an operator's city is a grant, and a grant with no merchant or ward has no such keys")
            .containsExactlyInAnyOrder("tenant_id", "tenant_code");
        assertThat(me.get("scopes").get(0).get("tenant_code").stringValue()).isEqualTo("siwan");

        Map<String, Object> session = jdbc.queryForMap(
            "SELECT principal_kind, tenant_scope::text AS tenant_scope, merchant_scope::text AS merchant_scope, "
                + "ward_scope::text AS ward_scope, "
                + "EXTRACT(EPOCH FROM (idle_expires_at - created_at))::bigint AS idle_seconds, "
                + "EXTRACT(EPOCH FROM (absolute_expires_at - created_at))::bigint AS absolute_seconds "
                + "FROM user_session WHERE id = ?", sessionIdOf(second));
        assertThat(session.get("principal_kind")).isEqualTo("OPERATOR");
        assertThat(session.get("tenant_scope")).isEqualTo(jdbc.queryForObject(
            "SELECT id FROM tenant WHERE code = 'siwan'", String.class));
        assertThat(session.get("merchant_scope")).isNull();
        assertThat(session.get("ward_scope")).isNull();
        assertThat(((Number) session.get("idle_seconds")).longValue())
            .as("§12.4: forty-five idle minutes in a city where a shared console is normal").isEqualTo(2_700L);
        assertThat(((Number) session.get("absolute_seconds")).longValue()).isEqualTo(43_200L);
    }

    @Test
    @DisplayName("a platform grant is a scope with nowhere in it")
    void aSuperAdminsScopeIsThePlatformAndNotAPlace() {
        HttpResponse<String> signedIn = signInAsStaff(ANIL, ANIL_PHONE);

        JsonNode scopes = body(signedIn).get("scopes");
        assertThat(scopes.size()).isEqualTo(1);
        assertThat(scopes.get(0).propertyNames())
            .as("§10.1: absent over null, and a tenantless grant names no tenant, merchant or ward")
            .isEmpty();

        Map<String, Object> session = jdbc.queryForMap(
            "SELECT tenant_scope::text AS tenant_scope, "
                + "EXTRACT(EPOCH FROM (idle_expires_at - created_at))::bigint AS idle_seconds, "
                + "EXTRACT(EPOCH FROM (absolute_expires_at - created_at))::bigint AS absolute_seconds "
                + "FROM user_session WHERE id = ?", sessionIdOf(signedIn));
        assertThat(session.get("tenant_scope")).isNull();
        assertThat(((Number) session.get("idle_seconds")).longValue()).isEqualTo(1_800L);
        assertThat(((Number) session.get("absolute_seconds")).longValue())
            .as("§12.4: the shortest session in the building, because this one can reach every city")
            .isEqualTo(14_400L);
    }

    @Test
    @DisplayName("a right password and a wrong code is not a sign-in")
    void theSecondFactorIsNotAFormality() {
        String challengeId = mfaRequired(login(MEENA, PASSWORD)).get("challenge_id").stringValue();

        // Half the code space away from the real digits rather than a fixed "000000", which has a
        // millionth chance of being the code and would then be a test that fails at random.
        String wrongCode = String.format("%06d", (Integer.parseInt(otp.codeFor(MEENA_PHONE)) + 500_000) % 1_000_000);
        HttpResponse<String> response = login(MEENA, PASSWORD, challengeId, wrongCode);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("OTP_INVALID");
        assertThat(error(response).get("message").stringValue()).isEqualTo("That code is not right.");
        assertThat(jdbc.queryForObject("SELECT attempts FROM otp_challenge WHERE id = ?",
            Integer.class, challengeId)).isEqualTo(1);
        assertThat(liveSessionsFor(MEENA)).isZero();
    }

    @Test
    @DisplayName("a challenge issued for another staffer cannot be redeemed against this one's session")
    void theChallengeIsBoundToThePersonWhoAskedForIt() {
        String meenas = mfaRequired(login(MEENA, PASSWORD)).get("challenge_id").stringValue();
        mfaRequired(login(ANIL, PASSWORD));

        // Anil's challenge id, Meena's password and Meena's own digits: the only thing that can tell
        // these apart is the target hash on the row, and it is one-way, so the service has to recompute
        // it from the account the password unlocked rather than trust either identifier.
        HttpResponse<String> response = login(ANIL, PASSWORD, meenas, otp.codeFor(ANIL_PHONE));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("OTP_INVALID");
        assertThat(liveSessionsFor(ANIL)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What the endpoint cannot be used to learn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the same three answers for an address nobody has, a wrong password, and a non-staff account")
    void loginIsNotADirectory() {
        // V2 lets a CUSTOMER row carry an email; what it does not let it carry is a password_hash.
        // That is the third case, and the one an operator's support desk will report as "my customer
        // cannot log in with their email" — the answer must not tell them the address is on the system.
        jdbc.update("DELETE FROM user_account WHERE email = ?", CUSTOMER_EMAIL);
        jdbc.update("INSERT INTO user_account (id, kind, phone, email, display_name, state, created_at) "
            + "VALUES (?, 'CUSTOMER', ?, ?, 'Naseem Ali', 'ACTIVE', now())",
            Ulid.next(clock), CUSTOMER_PHONE, CUSTOMER_EMAIL);

        try {
            List<String> answers = List.of(
                nobody(),
                wrongPassword(),
                customerWithEmail());

            String first = answers.get(0);
            answers.forEach(answer -> assertThat(answer).isEqualTo(first));
            assertThat(first).isEqualTo("INVALID_CREDENTIALS|Email or password is not right.");
        } finally {
            jdbc.update("DELETE FROM user_account WHERE email = ?", CUSTOMER_EMAIL);
        }
    }

    private String nobody() {
        HttpResponse<String> response = login("nobody@saathi.example", PASSWORD);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).has("details"))
            .as("§10.2: no details, because a detail here would be a fact about the address").isFalse();
        return codeAndMessage(response);
    }

    private String wrongPassword() {
        HttpResponse<String> response = login(MEENA, WRONG_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(401);
        return codeAndMessage(response);
    }

    private String customerWithEmail() {
        HttpResponse<String> response = login(CUSTOMER_EMAIL, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(401);
        return codeAndMessage(response);
    }

    /** The request id differs per call, so equivalence is asserted on the two fields that could carry news. */
    private String codeAndMessage(HttpResponse<String> response) {
        JsonNode error = error(response);
        return error.get("code").stringValue() + "|" + error.get("message").stringValue();
    }

    @Test
    @DisplayName("an OTP is not sent for a password that did not match")
    void aWrongPasswordDoesNotBuyASecondFactor() {
        int before = challengesFor(MEENA_PHONE);

        HttpResponse<String> response = login(MEENA, WRONG_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(challengesFor(MEENA_PHONE))
            .as("otherwise the endpoint is a way to spend a number's five-codes-an-hour budget")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("the second factor is two fields or none, and there is no state to be stuck in")
    void halfASecondFactorIsRefused() {
        String challengeId = mfaRequired(login(MEENA, PASSWORD)).get("challenge_id").stringValue();

        HttpResponse<String> noCode = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_challenge_id": "%s"}""".formatted(MEENA, PASSWORD, challengeId));
        HttpResponse<String> noChallenge = post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_code": "123456"}""".formatted(MEENA, PASSWORD));
        // No pending-authentication state exists to authenticate against, so the second call has to
        // repeat the first one's credential. A body that omits it is not a "resume"; it is a mistake.
        HttpResponse<String> noPassword = post("/api/v1/auth/login", """
            {"otp_challenge_id": "%s", "otp_code": "123456"}""".formatted(challengeId));

        for (HttpResponse<String> response : List.of(noCode, noChallenge)) {
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(error(response).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
            JsonNode fieldError = error(response).get("details").get("field_errors").get(0);
            assertThat(fieldError.get("field").stringValue()).isEqualTo("otp_code");
            assertThat(fieldError.get("message").stringValue())
                .isEqualTo("otp_challenge_id and otp_code are sent together or not at all.");
        }

        assertThat(noPassword.statusCode()).isEqualTo(422);
        assertThat(fieldNames(error(noPassword).get("details").get("field_errors")))
            .containsExactlyInAnyOrder("email", "password");
        assertThat(liveSessionsFor(MEENA)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §12.8's lockout
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("six wrong passwords lock the account, and the lock ends by being read")
    void sixFailuresLockAndNothingUnlocksThemEarly() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            HttpResponse<String> response = login(MEENA, WRONG_PASSWORD);
            assertThat(response.statusCode()).as("attempt %d", attempt).isEqualTo(401);
            assertThat(error(response).get("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");
        }

        Map<String, Object> locked = jdbc.queryForMap("SELECT state, failed_auth_count, locked_until::text AS locked_until"
            + " FROM user_account WHERE email = ?", MEENA);
        assertThat(locked.get("state")).isEqualTo("LOCKED");
        assertThat(locked.get("failed_auth_count")).isEqualTo(6);
        assertThat(locked.get("locked_until")).isNotNull();

        // The right password does not clear a live lock: a lock that the correct credential walks
        // through is a lock that only stops people who do not know their password.
        HttpResponse<String> correctWhileLocked = login(MEENA, PASSWORD);
        assertThat(correctWhileLocked.statusCode()).as("§10.2: 423, the one status that means try later")
            .isEqualTo(423);
        assertThat(error(correctWhileLocked).get("code").stringValue()).isEqualTo("ACCOUNT_LOCKED");
        Instant until = Instant.parse(error(correctWhileLocked).get("details").get("locked_until").stringValue());
        assertThat(until)
            .as("§12.8: the client is told when the lock ends, so it never has to guess whether to retry")
            .isBetween(clock.instant().plus(Duration.ofMinutes(29)), clock.instant().plus(Duration.ofMinutes(31)));

        // §6.5 removed timers, so a lock ends when someone reads it after its moment, not when a job
        // notices. Thirty minutes is not waited out here: the row is moved to the far side of it.
        jdbc.update("UPDATE user_account SET locked_until = now() - interval '1 minute' WHERE email = ?", MEENA);

        assertThat(mfaRequired(login(MEENA, PASSWORD)).get("challenge_id").stringValue())
            .as("the same first-factor answer as before the lock")
            .isNotBlank();
        Map<String, Object> released = jdbc.queryForMap("SELECT state, failed_auth_count, locked_until "
            + "FROM user_account WHERE email = ?", MEENA);
        assertThat(released.get("state")).isEqualTo("ACTIVE");
        assertThat(released.get("failed_auth_count")).isEqualTo(0);
        assertThat(released.get("locked_until")).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §12.2's state, and §12.7's outage fallback
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a deactivated account is refused on its own code, and only once the password is right")
    void deactivationHasItsOwnAnswerAndComesLast() {
        HttpResponse<String> wrongPassword = login(MEENA, WRONG_PASSWORD);
        assertThat(error(wrongPassword).get("code").stringValue())
            .as("§12.2: a wrong password must not be the thing that reveals the state")
            .isEqualTo("INVALID_CREDENTIALS");

        jdbc.update("UPDATE user_account SET state = 'DEACTIVATED' WHERE email = ?", MEENA);

        HttpResponse<String> response = login(MEENA, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").stringValue()).isEqualTo("ACCOUNT_DEACTIVATED");
        assertThat(error(response).get("message").stringValue()).isEqualTo("This account is deactivated.");
        assertThat(liveSessionsFor(MEENA)).isZero();
    }

    @Test
    @DisplayName("deactivating a staffer ends the session they are already using")
    void deactivationEndsALiveSession() {
        HttpResponse<String> signedIn = signInAsStaff(MEENA, MEENA_PHONE);
        assertThat(get("/api/v1/me", Map.of("Cookie", cookieHeader(signedIn))).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE user_account SET state = 'DEACTIVATED' WHERE email = ?", MEENA);

        HttpResponse<String> response = get("/api/v1/me", Map.of("Cookie", cookieHeader(signedIn)));
        assertThat(response.statusCode())
            .as("§12.5: the row decides access, so there is nothing to wait for a job to revoke")
            .isEqualTo(401);
        assertThat(error(response).get("code").stringValue()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("mfa_optional lets a city operator in on one factor and never a super admin")
    void theOutageFallbackIsRefusedForThePlatformRole() {
        // Deliberately a global row rather than Siwan's: with the flag on for everyone, the only thing
        // left that can refuse Anil is §12.7's rule in code, which is the thing worth proving.
        jdbc.update("INSERT INTO feature_flag (id, key, tenant_id, enabled, depends_on, description) "
            + "VALUES (?, 'mfa_optional', NULL, true, NULL, 'test fixture: §12.7 outage fallback')",
            Ulid.next(clock));

        try {
            HttpResponse<String> operator = login(MEENA, PASSWORD);
            assertThat(operator.statusCode()).isEqualTo(200);
            assertThat(body(operator).get("flags").get("mfa_optional").booleanValue())
                .as("the screen that offers the outage path learns about it from the same response")
                .isTrue();

            HttpResponse<String> superAdmin = login(ANIL, PASSWORD);
            assertThat(superAdmin.statusCode()).isEqualTo(401);
            assertThat(error(superAdmin).get("code").stringValue())
                .as("a platform-wide flag would be one back door into every city at once")
                .isEqualTo("MFA_REQUIRED");
        } finally {
            jdbc.update("DELETE FROM feature_flag WHERE key = 'mfa_optional'");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §10.1: scope comes from the grant, not from a request body
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a staffer cannot move themselves into another city")
    void staffDoNotChooseTheirCity() {
        HttpResponse<String> signedIn = signInAsStaff(MEENA, MEENA_PHONE);

        HttpResponse<String> response = put("/api/v1/me/ward", asBrowser(signedIn), """
            {"city_code": "siwan", "ward_no": 1}""");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").stringValue()).isEqualTo("FORBIDDEN");
        assertThat(error(response).get("message").stringValue())
            .isEqualTo("A city is granted to staff, not chosen by them.");
        assertThat(jdbc.queryForObject("SELECT ward_scope FROM user_session WHERE id = ?",
            String.class, sessionIdOf(signedIn))).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private HttpResponse<String> login(String email, String password) {
        return post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s"}""".formatted(email, password));
    }

    private HttpResponse<String> login(String email, String password, String challengeId, String code) {
        return post("/api/v1/auth/login", """
            {"email": "%s", "password": "%s", "otp_challenge_id": "%s", "otp_code": "%s"}"""
            .formatted(email, password, challengeId, code));
    }

    /** Both factors, ending with the response that has the cookies in it. */
    private HttpResponse<String> signInAsStaff(String email, String phone) {
        String challengeId = mfaRequired(login(email, PASSWORD)).get("challenge_id").stringValue();
        HttpResponse<String> response = login(email, PASSWORD, challengeId, otp.codeFor(phone));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("staff sign-in for " + email + " failed: " + response.body());
        }
        return response;
    }

    private JsonNode mfaRequired(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("§12.1: the password alone is not a sign-in").isEqualTo(401);
        JsonNode error = error(response);
        assertThat(error.get("code").stringValue()).isEqualTo("MFA_REQUIRED");
        assertThat(error.get("details").propertyNames())
            .containsExactlyInAnyOrder("challenge_id", "expires_at");
        return error.get("details");
    }

    private void setVaultedPassword(String email) {
        jdbc.update("UPDATE user_account SET password_hash = ? WHERE email = ?", vault.hash(PASSWORD), email);
    }

    /** The seed's staff rows, exactly as the repeatable migration leaves them. */
    private void restoreSeedRows() {
        jdbc.update("UPDATE user_account SET password_hash = NULL, failed_auth_count = 0, "
            + "locked_until = NULL, state = 'ACTIVE' WHERE email IN (?, ?)", MEENA, ANIL);
        jdbc.update("UPDATE otp_challenge SET created_at = now() - interval '2 hours' "
            + "WHERE target_hash IN (?, ?)", keys.otpTargetHash(MEENA_PHONE), keys.otpTargetHash(ANIL_PHONE));
        // Rows this suite created are deleted rather than revoked, because the strongest thing the
        // customer suite can say about a refused staff sign-in — that no session row was ever written
        // for that person — is only true while the table says so.
        jdbc.update("DELETE FROM user_session WHERE user_account_id IN "
            + "(SELECT id FROM user_account WHERE email IN (?, ?))", MEENA, ANIL);
    }

    private int challengesFor(String e164) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM otp_challenge WHERE target_hash = ?",
            Integer.class, keys.otpTargetHash(e164));
        return count == null ? 0 : count;
    }

    private int liveSessionsFor(String email) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM user_session WHERE revoked_at IS NULL "
            + "AND user_account_id = (SELECT id FROM user_account WHERE email = ?)", Integer.class, email);
        return count == null ? 0 : count;
    }

    private static List<String> fieldNames(JsonNode fieldErrors) {
        return fieldErrors.valueStream().map(node -> node.get("field").stringValue()).toList();
    }

    private JsonNode error(HttpResponse<String> response) {
        return body(response).get("error");
    }
}
