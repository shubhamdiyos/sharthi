package com.saathi.it;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the integration tests find PostgreSQL, and which disposable database they may create.
 *
 * <p>Bound from {@code saathi-it.*} in {@code application-test.yml}, which reads the same keys
 * the root {@code .env} carries. No value here is a committed credential: the password defaults
 * to empty and the bootstrap fails with the reproduce command if it is missing.
 */
@ConfigurationProperties(prefix = "saathi-it")
public record SaathiItSettings(
    String host,
    int port,
    String database,
    String maintenanceDatabase,
    String username,
    String password,
    boolean recreate) {

    /** JDBC url of the maintenance database the IT database is created from. */
    String maintenanceUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + maintenanceDatabase;
    }
}
