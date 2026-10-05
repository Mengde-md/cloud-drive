package com.mengde.clouddrive.deployment;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** Runs the root schema migrations without starting the historical web writer. */
public final class DatabaseMigrationApplication {
    private DatabaseMigrationApplication() { }

    public static void main(String[] args) {
        try {
            Settings settings = Settings.from(System.getenv());
            var result = Flyway.configure()
                    .dataSource(settings.url(), settings.username(), settings.password())
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .load().migrate();
            System.out.println("Migration succeeded: executed=" + result.migrationsExecuted);
        } catch (RuntimeException failure) {
            // Do not echo connection properties or exception messages containing a JDBC URL.
            System.err.println("Migration failed: " + failure.getClass().getSimpleName()
                    + "; check required environment and secured database diagnostics.");
            System.exit(1);
        }
    }

    public record Settings(String url, String username, String password) {
        public static Settings from(Map<String, String> environment) {
            String url = required(environment, "CLOUDDRIVE_DB_URL");
            if (!url.startsWith("jdbc:mysql://") || url.matches("(?i).*[?&](user|password)=.*")) {
                throw new IllegalArgumentException("CLOUDDRIVE_DB_URL must be MySQL without credentials");
            }
            return new Settings(url, required(environment, "CLOUDDRIVE_DB_USERNAME"),
                    required(environment, "CLOUDDRIVE_DB_PASSWORD"));
        }

        private static String required(Map<String, String> environment, String key) {
            String value = environment.get(key);
            if (value == null || value.isBlank() || value.startsWith("change-me")) {
                throw new IllegalArgumentException("Missing deployment setting: " + key);
            }
            return value;
        }

        @Override public String toString() {
            return "Settings[connection=<redacted>]";
        }
    }
}
