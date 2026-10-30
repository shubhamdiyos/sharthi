package com.saathi.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The two signing keys have no default anywhere in the project, so the only thing standing
 * between a deployed Saathi and a known-session-key is this validation. It is worth a test:
 * §14.7's RATE_NOT_APPROVED and §12.4's sessions both assume the boot failed loudly.
 */
class SaathiPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static SaathiProperties props(String environment, String sessionSecret, String auditIpKey,
        String otpProvider) {
        return new SaathiProperties(environment, sessionSecret, auditIpKey, otpProvider,
            new SaathiProperties.S3("http://minio:9000", "saathi-media", "key", "secret", "us-east-1"));
    }

    private static Set<String> violatedProperties(SaathiProperties target) {
        return validator.validate(target).stream()
            .map(v -> v.getPropertyPath().toString())
            .collect(Collectors.toSet());
    }

    @Test
    void aCompleteConfigurationPassesAndIsWhatComposeProduces() {
        Set<String> violations = violatedProperties(props("dev", "a-real-secret", "another-real-secret", "logging"));
        assertEquals(Set.of(), violations);
    }

    @Test
    void aMissingSessionSecretStopsTheBoot() {
        for (String blank : new String[] {null, "", "   "}) {
            assertTrue(violatedProperties(props("dev", blank, "another-secret", "logging"))
                .contains("sessionSecret"), "null secret would sign sessions with nothing");
        }
    }

    @Test
    void aMissingAuditIpKeyStopsTheBoot() {
        for (String blank : new String[] {null, "", "   "}) {
            assertTrue(violatedProperties(props("dev", "a-secret", blank, "logging"))
                .contains("auditIpKey"), "an empty HMAC key makes every ip_hash the same value");
        }
    }

    @Test
    void onlyTheThreeNamedEnvironmentsExist() {
        // "local" and "test" are not environments here: a profile that is not dev, staging or
        // prod is a profile nobody has reviewed the secrets for.
        assertEquals(Set.of("environment"), violatedProperties(props("local", "a", "b", "logging")));
        assertEquals(Set.of("environment"), violatedProperties(props("PROD", "a", "b", "logging")));
        assertEquals(Set.of("environment"), violatedProperties(props(null, "a", "b", "logging")));
        assertTrue(violatedProperties(props("prod", "a", "b", "logging")).isEmpty());
        assertTrue(violatedProperties(props("staging", "a", "b", "logging")).isEmpty());
    }

    @Test
    void theOtpProviderIsChosenFromTheTwoThatExist() {
        // OD-8 is still open. `logging` is the documented dev-mode answer; anything else is a
        // provider nobody selected, and a typo here would silently disable delivery.
        assertEquals(Set.of("otpProvider"), violatedProperties(props("dev", "a", "b", "twilio")));
        assertEquals(Set.of("otpProvider"), violatedProperties(props("dev", "a", "b", null)));
        assertTrue(violatedProperties(props("dev", "a", "b", "http")).isEmpty());
    }

    @Test
    void objectStorageIsNotRequiredInDevelopment() {
        SaathiProperties noBucket = new SaathiProperties("dev", "a", "b", "logging",
            new SaathiProperties.S3(null, null, null, null, null));
        assertEquals(Set.of(), violatedProperties(noBucket), "minio is on an optional profile (§2.2)");
    }

    @Test
    void theProductionFlagIsDerivedFromTheValidatedEnvironment() {
        assertTrue(props("prod", "a", "b", "http").isProduction());
        assertTrue(!props("dev", "a", "b", "logging").isProduction());
    }

    @Test
    void thePrefixAndTheKeyNamesMatchWhatComposeActuallySets() {
        // @ConfigurationProperties(prefix = "saathi") maps saathi.session-secret to the
        // environment variable SAATHI_SESSION_SECRET. If either side is renamed, Compose stops
        // supplying the value and this is the test that says why the app refused to start.
        assertEquals("logging", props("dev", "a", "b", "logging").otpProvider());
        assertEquals("http://minio:9000", props("dev", "a", "b", "logging").s3().endpoint());
        assertEquals("saathi-media", props("dev", "a", "b", "logging").s3().bucket());
        assertEquals("us-east-1", props("dev", "a", "b", "logging").s3().region());
    }
}
