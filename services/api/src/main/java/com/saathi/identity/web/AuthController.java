package com.saathi.identity.web;

import com.saathi.identity.domain.OtpPurpose;
import com.saathi.identity.domain.UserSession;
import com.saathi.identity.service.SignInService;
import com.saathi.identity.service.StaffSignInService;
import com.saathi.identity.session.AuthenticatedPrincipal;
import com.saathi.identity.session.SessionCookies;
import com.saathi.identity.session.SessionService;
import com.saathi.identity.session.SignedIn;
import com.saathi.shared.audit.RequestTags;
import com.saathi.shared.error.ApiException;
import com.saathi.shared.error.ErrorCode;
import com.saathi.shared.error.RequestIdFilter;
import com.saathi.shared.paging.CursorPager;
import com.saathi.shared.paging.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10.5.1's sign-in surface: the customer's one factor, the staff member's two, and the device list
 * that lets either of them see and end their own sessions.
 *
 * <p>The base path is written on each controller rather than as a {@code server.servlet.context-path}
 * because the actuator endpoints live outside {@code /api/v1} on purpose: Compose's healthcheck probes
 * {@code /actuator/health/readiness}, and moving the whole servlet under a prefix would move that
 * probe with it and take the stack down in a way no test here would explain.
 *
 * <p>Two shapes are worth reading before changing anything. The cookies are issued from the
 * {@code Set-Cookie} header rather than written to the response directly, so one
 * {@link SessionCookies} remains the only place §12.4's two cookies are described. And
 * {@code POST /auth/login} answers a first-factor success with a 401: §12.1 makes every staff sign-in
 * two factors, and the code that says "the password was right, now prove the phone" is
 * {@code MFA_REQUIRED}, which is the departure from §10.2's list that {@code ErrorCode} already
 * documents.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** The reason a principal sees when their own device list says a session ended (§12.5). */
    private static final String SELF_LOGOUT = "Signed out";

    /** Reachable only once §11.2's operator permissions exist; the row still has to say why. */
    private static final String ADMIN_LOGOUT = "Revoked by your administrator";

    private final SignInService signIn;
    private final StaffSignInService staffSignIn;
    private final SessionService sessions;
    private final SessionCookies cookies;
    private final PrincipalViewFactory principalViews;
    private final RequestTags tags;
    private final Clock clock;

    /**
     * The only endpoint in this class whose status is 202. Nothing has been decided yet: the code is
     * on its way, and §10.5.1's answer is the same whether or not the phone is a customer, so this
     * cannot be read as "that number exists".
     */
    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SignInService.IssuedChallenge requestOtp(@Valid @RequestBody OtpRequest body, HttpServletRequest request) {
        OtpPurpose purpose = body.purpose() == null ? OtpPurpose.LOGIN : body.purpose();
        return signIn.requestChallenge(purpose, body.phone(), requestId());
    }

    /**
     * Redeems a customer's code and opens a session with it (§10.5.1). The response is the caller's
     * own {@code PrincipalView}, which is what lets a storefront render its chrome without a second
     * round trip to {@code GET /me}.
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<PrincipalView> verifyOtp(@Valid @RequestBody OtpVerifyRequest body,
                                                   HttpServletRequest request) {
        OtpPurpose purpose = body.purpose() == null ? OtpPurpose.LOGIN : body.purpose();
        SignedIn signedIn = signIn.signInWithOtp(purpose, body.phone(), body.challengeId(), body.code(),
            tags.of(request));
        return withCookies(signedIn);
    }

    /**
     * Staff sign-in: password, then the OTP §12.1 requires, in one endpoint called twice rather than
     * two endpoints or a pending-authentication table. See {@link StaffSignInService} for why the
     * second call is the one that can succeed and what stops the first from being a downgrade path.
     *
     * <p>A first call with no second factor answers 401 {@code MFA_REQUIRED} with the challenge in
     * {@code details} — the password was correct and the client is being told what is missing, which
     * is the only way this flow can be driven by a screen that has nothing but a phone field to show.
     */
    @PostMapping("/login")
    public ResponseEntity<PrincipalView> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        boolean hasChallenge = body.otpChallengeId() != null;
        boolean hasCode = body.otpCode() != null;
        if (hasChallenge != hasCode) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields failed validation.",
                Map.of("field_errors", List.of(Map.of("field", "otp_code",
                    "message", "otp_challenge_id and otp_code are sent together or not at all."))));
        }

        StaffSignInService.StaffSignIn result = staffSignIn.signIn(body.email(), body.password(),
            body.otpChallengeId(), body.otpCode(), tags.of(request), requestId());

        if (!result.signedIn()) {
            SignInService.IssuedChallenge challenge = result.challenge();
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Enter the code sent to your registered phone.",
                Map.of("challenge_id", challenge.challengeId(), "expires_at", challenge.expiresAt()));
        }
        return withCookies(result.session());
    }

    /**
     * Ends the session that made this call and no other (§12.5). It answers 204 even if the row was
     * already revoked: a client that is signing out does not care how it got there, and an error here
     * would teach every portal to check before logging out.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(AuthenticatedPrincipal caller) {
        sessions.revoke(caller, caller.sessionId(), SELF_LOGOUT);
        HttpHeaders headers = new HttpHeaders();
        cookies.clear(headers);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).headers(headers).build();
    }

    /**
     * The caller's own devices, most recently used first (§12.5). Expired rows stay listed with
     * {@code expired: true}: "this device is still open somewhere" and "this device has not been used
     * in weeks" are the two answers a person needs in order to notice a problem.
     *
     * <p>The list is paged in memory. A principal has a handful of devices, the repository already
     * loads them all, and §10.3's ban is on {@code OFFSET} paging in SQL — a cursor over a sorted list
     * of rows the query has already returned is the same rule applied where it costs nothing.
     */
    @GetMapping("/sessions")
    public Page<SessionView> listSessions(@RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) Integer limit,
                                          AuthenticatedPrincipal caller) {
        int pageSize = CursorPager.clampLimit(limit);
        Instant now = clock.instant();
        List<UserSession> rows = orderedSessionsOf(caller.accountId());
        int start = resumeAt(rows, cursor);
        int end = Math.min(start + CursorPager.fetchSize(pageSize), rows.size());
        List<UserSession> window = rows.subList(start, end);

        boolean hasMore = window.size() > pageSize;
        List<UserSession> page = hasMore ? window.subList(0, pageSize) : window;
        String next = hasMore ? encodeCursor(page.get(page.size() - 1)) : null;
        List<SessionView> data = page.stream().map(row -> SessionView.of(row, caller.sessionId(), now)).toList();
        return Page.of(data, next, hasMore, pageSize);
    }

    /**
     * §10.5.1's one row with two owners: the caller's own session, or another principal's with
     * {@code USER_FORCE_LOGOUT}. A session that is not addressable this way answers 404, never 403
     * (§4.3-5), so the endpoint cannot be used to discover that a colleague has a device.
     */
    @PostMapping("/sessions/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable("id") String sessionId, AuthenticatedPrincipal caller) {
        boolean own = sessionId.equals(caller.sessionId());
        if (!sessions.revoke(caller, sessionId, own ? SELF_LOGOUT : ADMIN_LOGOUT)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found in the caller's scope.");
        }
        // Revoking the session that is making the call ends it, so the cookies go the same way they
        // do on a logout. Any other row leaves this caller's own session intact.
        HttpHeaders headers = new HttpHeaders();
        if (own) {
            cookies.clear(headers);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).headers(headers).build();
    }

    private ResponseEntity<PrincipalView> withCookies(SignedIn signedIn) {
        HttpHeaders headers = new HttpHeaders();
        cookies.issue(headers, signedIn.session());
        return ResponseEntity.ok().headers(headers).body(principalViews.of(signedIn.principal()));
    }

    private List<UserSession> orderedSessionsOf(String accountId) {
        // (last_seen desc, id desc): the id is the stable secondary key §10.3 requires of every sort,
        // and two sessions sharing one timestamp is ordinary — two logins in the same second.
        Comparator<UserSession> byRecency = Comparator
            .comparing(UserSession::getLastSeenAt, Comparator.reverseOrder())
            .thenComparing(UserSession::getId, Comparator.reverseOrder());
        return sessions.liveSessionsOf(accountId).stream().sorted(byRecency).toList();
    }

    /** The first row that comes strictly after the cursor's {@code (last_seen_at, id)} pair. */
    private static int resumeAt(List<UserSession> rows, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        List<String> parts = CursorPager.decode(cursor);
        if (parts.size() != 2 || parts.get(0) == null || parts.get(1) == null) {
            throw CursorPager.badCursor();
        }
        Instant seenAt;
        try {
            seenAt = Instant.parse(parts.get(0));
        } catch (DateTimeParseException e) {
            throw CursorPager.badCursor();
        }
        for (int i = 0; i < rows.size(); i++) {
            UserSession row = rows.get(i);
            int byTime = row.getLastSeenAt().compareTo(seenAt);
            if (byTime < 0 || (byTime == 0 && row.getId().compareTo(parts.get(1)) < 0)) {
                return i;
            }
        }
        return rows.size();
    }

    private static String encodeCursor(UserSession last) {
        return CursorPager.encode(List.of(last.getLastSeenAt().toString(), last.getId()));
    }

    /** The platform's own id for this request — never the header, which is client text (§10.1). */
    private static String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    /**
     * A phone as the client typed it. There is no {@code @Pattern} here: {@code PhoneNormaliser}
     * accepts the local forms a customer actually uses and reports what it could not read as a
     * field error, which is a better message than a regular expression can write.
     */
    public record OtpRequest(@NotBlank @Size(max = 20) String phone, OtpPurpose purpose) {
    }

    /**
     * The phone again, deliberately. {@code target_hash} is one-way, so the challenge row cannot say
     * whose number it was sent to without the caller proving they know it — which is also what stops
     * a guessed challenge id from being redeemed against a different phone.
     */
    public record OtpVerifyRequest(
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(max = 26) String challengeId,
        @NotBlank @Size(min = 4, max = 8) String code,
        OtpPurpose purpose) {
    }

    /** {@code otpChallengeId} and {@code otpCode} are the second call; both or neither (§12.1). */
    public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 200) String password,
        @Size(max = 26) String otpChallengeId,
        @Size(min = 4, max = 8) String otpCode) {
    }
}
