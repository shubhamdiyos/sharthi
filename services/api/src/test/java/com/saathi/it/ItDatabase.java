package com.saathi.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Creates the one database the integration tests are allowed to own.
 *
 * <p>Two reasons this is a database rather than a schema in the development one: V1 must be
 * proven against an empty PostgreSQL, and {@code audit_event} cannot be cleaned up afterwards —
 * its own triggers reject DELETE and TRUNCATE. A disposable database is the only target that
 * can be left dirty on purpose.
 *
 * <p>The name is validated against a prefix before it reaches any statement, because
 * {@code CREATE DATABASE} takes no parameter and cannot. Nothing here will ever touch
 * {@code saathi}, {@code postgres} or a template database.
 */
final class ItDatabase {

    /** Postgres identifiers are 63 bytes; anything else is not a name this method understands. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final String REQUIRED_PREFIX = "saathi_it";

    private ItDatabase() {
    }

    static void ensureExists(SaathiItSettings settings) {
        String database = requireDisposable(settings.database());
        if (settings.password() == null || settings.password().isBlank()) {
            throw new IllegalStateException("""
                Integration tests need the Saathi database. From the repository root:
                  cp .env.example .env
                  docker compose up -d postgres
                POSTGRES_PASSWORD was empty, so either .env is missing or the key is unset.""");
        }
        try (Connection maintenance =
                 DriverManager.getConnection(settings.maintenanceUrl(), settings.username(), settings.password())) {
            if (exists(maintenance, database)) {
                if (settings.recreate()) {
                    drop(maintenance, database);
                    create(maintenance, database);
                }
                return;
            }
            create(maintenance, database);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Cannot reach PostgreSQL at " + settings.maintenanceUrl() + " as " + settings.username()
                    + ". Start it with: docker compose up -d postgres (from the repository root)", e);
        }
    }

    private static String requireDisposable(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalStateException("SAATHI_IT_DB must be a plain lowercase Postgres identifier, was: " + name);
        }
        if (!name.startsWith(REQUIRED_PREFIX)) {
            throw new IllegalStateException(
                "Integration tests may only own a database named " + REQUIRED_PREFIX + "*; refusing to act on " + name
                    + ". The development database is never a test target.");
        }
        return name;
    }

    private static boolean exists(Connection maintenance, String name) throws SQLException {
        try (PreparedStatement statement = maintenance.prepareStatement(
            "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void create(Connection maintenance, String name) throws SQLException {
        try (Statement statement = maintenance.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        }
    }

    /**
     * Only ever called for a validated {@code saathi_it*} name. The backends are closed first
     * because a dropped database that someone still holds open is an error, not a warning.
     */
    private static void drop(Connection maintenance, String name) throws SQLException {
        try (PreparedStatement statement = maintenance.prepareStatement(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
            statement.setString(1, name);
            statement.execute();
        }
        try (Statement statement = maintenance.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + name + "\"");
        }
    }
}
