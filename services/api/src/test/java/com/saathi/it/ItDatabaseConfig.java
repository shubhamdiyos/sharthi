package com.saathi.it;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Creates the IT database before Flyway is asked to migrate it, in the one place the
 * application's own migration path can be intercepted.
 *
 * <p>Deliberately a {@link FlywayMigrationStrategy} rather than a test-side script: the ITs then
 * exercise exactly what the api container does on boot — Flyway, against a database the app did
 * not create — so a V1 that only works on a hand-made schema cannot pass here.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@EnableConfigurationProperties(SaathiItSettings.class)
class ItDatabaseConfig {

    @Bean
    FlywayMigrationStrategy itDatabaseThenMigrate(SaathiItSettings settings) {
        return flyway -> {
            ItDatabase.ensureExists(settings);
            flyway.migrate();
        };
    }
}
