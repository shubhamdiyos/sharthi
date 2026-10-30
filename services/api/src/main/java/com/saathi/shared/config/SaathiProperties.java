package com.saathi.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings a Saathi process refuses to start without.
 *
 * <p>{@code sessionSecret} and {@code auditIpKey} have <em>no defaults anywhere</em>: a
 * missing one fails validation at boot rather than quietly running on a key that is in the
 * repository. Compose interpolates them with {@code ${VAR:?}} for the same reason (§2.2).
 */
@ConfigurationProperties(prefix = "saathi")
@org.springframework.validation.annotation.Validated
public record SaathiProperties(
    @NotBlank @Pattern(regexp = "dev|staging|prod", message = "saathi.environment must be dev, staging or prod")
    String environment,

    @NotBlank String sessionSecret,
    @NotBlank String auditIpKey,

    // Required even though application.yml defaults it: a provider that resolves to null would
    // be an OTP that is never sent and never reported as failing.
    @NotBlank @Pattern(regexp = "logging|http", message = "saathi.otp-provider must be logging or http (OD-8)")
    String otpProvider,

    S3 s3) {

    public boolean isProduction() {
        return "prod".equals(environment);
    }

    /** Object storage is optional in development, so its fields are not validated as required. */
    public record S3(String endpoint, String bucket, String accessKey, String secretKey, String region) {
    }
}
