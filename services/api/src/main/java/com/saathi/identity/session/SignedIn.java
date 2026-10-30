package com.saathi.identity.session;

/** A session, its cookie, and the caller it belongs to — what every sign-in path returns. */
public record SignedIn(AuthenticatedPrincipal principal, SessionService.IssuedSession session) {
}
