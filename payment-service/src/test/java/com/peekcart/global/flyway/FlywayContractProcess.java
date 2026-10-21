package com.peekcart.global.flyway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate JVM entrypoint used only by the isolated Flyway contract lab. */
public final class FlywayContractProcess {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !List.of("migrate", "validate", "info").contains(args[0])) {
            throw new IllegalArgumentException("unsupported lab invocation");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", args[0]);
        result.put("pid", ProcessHandle.current().pid());
        result.put("jvmStartedAt", java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
        boolean success = false;
        try {
            var config = Flyway.configure().dataSource(
                            System.getenv("FLYWAY_LAB_URL"), System.getenv("FLYWAY_LAB_USER"),
                            System.getenv("FLYWAY_LAB_PASSWORD"))
                    .locations(args[1].split(","))
                    .cleanDisabled(true).baselineOnMigrate(false).connectRetries(0);
            if (!args[2].equals("latest")) config.target(args[2]);
            Flyway flyway = config.load();
            if (args[0].equals("migrate")) {
                var migrated = flyway.migrate();
                result.put("migrationsExecuted", migrated.migrationsExecuted);
                result.put("flywayVersion", migrated.flywayVersion);
                success = migrated.success;
            } else if (args[0].equals("validate")) {
                var validation = flyway.validateWithResult();
                result.put("invalidCount", validation.invalidMigrations.size());
                result.put("errorCode", validation.errorDetails == null ? "" : validation.errorDetails.errorCode.toString());
                success = validation.validationSuccessful;
            } else {
                result.put("migrations", java.util.Arrays.stream(flyway.info().all()).map(migration -> Map.of(
                        "version", migration.getVersion() == null ? "" : migration.getVersion().toString(),
                        "script", migration.getScript(), "state", migration.getState().toString())).toList());
                success = true;
            }
        } catch (Throwable failure) {
            result.put("failureType", failure.getClass().getName());
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            result.put("causeType", cause.getClass().getName());
            if (cause instanceof java.sql.SQLException sql) {
                result.put("sqlState", sql.getSQLState());
                result.put("sqlErrorCode", sql.getErrorCode());
            }
        } finally {
            result.put("success", success);
            result.put("invocation", args[4]);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(), result);
        }
        if (!success) System.exit(2);
    }
}
