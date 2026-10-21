package com.peekcart.global.flyway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** No Spring context: the dedicated task owns this JVM and its singleton containers. */
@Tag("flyway-contract-lab")
class FlywayContractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Integer> BEFORE_BACKFILL = Map.of(
            "order", 10, "product", 8, "payment", 8, "notification", 6);
    private static final Map<String, Integer> LATEST = Map.of(
            "order", 14, "product", 13, "payment", 15, "notification", 9);
    private final Path root = Path.of(System.getProperty("flyway.lab.root"));
    private final Path evidence = Path.of(System.getProperty("flyway.lab.evidence"));
    private final String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String user = "fl_" + nonce;
    private final String password = UUID.randomUUID().toString();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> invocations = new ArrayList<>();
    private final Map<String, Map<String, Object>> services = new LinkedHashMap<>();
    private final List<Scenario> scenarios = new ArrayList<>();
    private int invocationNumber;
    private int mysqlPort;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void fourServiceMigrationsPreserveHistoryAndExposeNonTransactionalFailure() throws Exception {
        Files.createDirectories(evidence);
        report.put("complete", false);
        report.put("services", services);
        report.put("invocations", invocations);
        report.put("parentPid", ProcessHandle.current().pid());
        try {
            report.put("mysqlImage", SharedContainers.MYSQL.getDockerImageName());
            report.put("containerId", SharedContainers.MYSQL.getContainerId());
            mysqlPort = inspectOwnedEndpoint("before-restart-endpoint");
            report.put("initialMappedPort", mysqlPort);
            rootSql("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
            for (String service : List.of("order", "product", "payment", "notification")) {
                exerciseService(service);
            }
            Map<String, Object> before = snapshots();
            write("before-restart.json", before);
            String containerId = SharedContainers.MYSQL.getContainerId();
            report.put("restartRequestedAt", Instant.now().toString());
            Process restart = new ProcessBuilder("docker", "restart", "--time", "10", containerId)
                    .redirectErrorStream(true).redirectOutput(evidence.resolve("mysql-restart.log").toFile()).start();
            try {
                assertThat(restart.waitFor(30, TimeUnit.SECONDS)).as("owned Docker restart deadline").isTrue();
                assertThat(restart.exitValue()).isZero();
            } finally {
                if (restart.isAlive()) { restart.destroyForcibly(); restart.waitFor(5, TimeUnit.SECONDS); }
            }
            mysqlPort = inspectOwnedEndpoint("after-restart-endpoint");
            report.put("restartedMappedPort", mysqlPort);
            Instant startedBefore = Instant.parse(JSON.readTree(evidence.resolve("before-restart-endpoint.json").toFile()).path("startedAt").asText());
            Instant startedAfter = Instant.parse(JSON.readTree(evidence.resolve("after-restart-endpoint.json").toFile()).path("startedAt").asText());
            assertThat(startedAfter).isAfter(startedBefore);
            awaitReady();
            report.put("restartReadyAt", Instant.now().toString());
            assertThat(SharedContainers.MYSQL.getContainerId()).isEqualTo(containerId);
            Map<String, Object> after = snapshots();
            write("after-restart.json", after);
            assertThat(after).isEqualTo(before);
            for (Scenario scenario : scenarios) {
                invoke(scenario, "validate", "latest", !scenario.failed());
                Map<String, Object> result = invoke(scenario, "migrate", "latest", !scenario.failed());
                if (!scenario.failed()) {
                    assertThat(((Number) result.get("migrationsExecuted")).intValue()).isZero();
                    assertThat(reserve(scenario.schema())).isGreaterThan(scenario.lastReserved());
                }
            }
            assertThat(snapshots()).isEqualTo(before);
            report.put("restartStatePreserved", true);
            report.put("complete", true);
        } finally {
            write("report.json", report);
        }
    }

    private void exerciseService(String service) throws Exception {
        Path migrations = root.resolve(service + "-service/src/main/resources/db/migration");
        Map<String, String> hashes = new LinkedHashMap<>();
        try (var paths = Files.list(migrations)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().matches("V[0-9]+__.*\\.sql")).sorted().toList()) {
                hashes.put(root.relativize(file).toString(), sha256(Files.readAllBytes(file)));
            }
        }
        assertThat(hashes).hasSize(LATEST.get(service));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productionMigrationSha256", hashes);
        services.put(service, result);
        String locations = "filesystem:" + migrations;

        String fresh = schema(service, "fresh");
        Scenario freshScenario = new Scenario(fresh, locations, false, 0);
        invoke(freshScenario, "migrate", "latest", true);
        historyMatches(fresh, hashes);
        assertNullable(fresh, "dead_letter_records", "root_record_id", "YES");
        assertNullable(fresh, "outbox_events", "record_kind", "YES");
        invoke(freshScenario, "validate", "latest", true);
        assertThat(((Number) invoke(freshScenario, "migrate", "latest", true).get("migrationsExecuted")).intValue()).isZero();
        scenarios.add(new Scenario(fresh, locations, false, reserve(fresh)));
        result.put("fresh", true);

        String upgrade = schema(service, "upgrade");
        Scenario upgradeScenario = new Scenario(upgrade, locations, false, 0);
        invoke(upgradeScenario, "migrate", BEFORE_BACKFILL.get(service).toString(), true);
        seed(upgrade, true);
        List<Map<String, String>> oldLedger = rows(upgrade, "SELECT * FROM dead_letter_records ORDER BY id");
        List<Map<String, String>> oldOutbox = rows(upgrade, "SELECT * FROM outbox_events ORDER BY id");
        write(service + "-legacy-before.json", Map.of("ledger", oldLedger, "outbox", oldOutbox));
        invoke(upgradeScenario, "migrate", "latest", true);
        historyMatches(upgrade, hashes);
        oldLedger.get(0).put("root_record_id", "10");
        oldOutbox.get(0).put("record_kind", "DOMAIN");
        assertExistingColumnsPreserved(oldLedger, rows(upgrade, "SELECT * FROM dead_letter_records ORDER BY id"));
        assertExistingColumnsPreserved(oldOutbox, rows(upgrade, "SELECT * FROM outbox_events ORDER BY id"));
        long reserved = reserve(upgrade);
        assertThat(reserved).isGreaterThan(21);
        result.put("upgrade", true);
        result.put("allocatorAfterLegacyMaximum", reserved);

        Path candidate = evidence.resolve(service + "-candidate");
        Files.createDirectories(candidate);
        Path sqlFile = candidate.resolve("V999__fixture_contract.sql");
        String validSql = "ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL;\n"
                + "ALTER TABLE outbox_events MODIFY record_kind VARCHAR(10) NOT NULL;\n";
        Files.writeString(sqlFile, validSql);
        Files.writeString(candidate.resolve("beforeMigrate__fixture_guard.sql"),
                "SET SESSION sql_mode = 'STRICT_ALL_TABLES';\nUPDATE fixture_guard SET required_value = NULL WHERE blocked = 1;\n");
        sql(upgrade, "CREATE TABLE fixture_guard (required_value INT NOT NULL, blocked INT NOT NULL)");
        sql(upgrade, "INSERT INTO fixture_guard VALUES (1,1)");
        Scenario guarded = new Scenario(upgrade, locations + ",filesystem:" + candidate, false, reserved);
        Object beforeGuard = snapshot(upgrade);
        invoke(guarded, "migrate", "latest", false);
        assertThat(snapshot(upgrade)).isEqualTo(beforeGuard);
        assertThat(rows(upgrade, "SELECT * FROM flyway_schema_history WHERE version='999'")).isEmpty();
        sql(upgrade, "UPDATE fixture_guard SET blocked=0");
        invoke(guarded, "migrate", "latest", true);
        assertNullable(upgrade, "dead_letter_records", "root_record_id", "NO");
        assertNullable(upgrade, "outbox_events", "record_kind", "NO");
        result.put("callbackRejectedWithoutHistoryThenResumed", true);
        Object beforeChecksum = snapshot(upgrade);
        Files.writeString(sqlFile, validSql.replace("BIGINT NOT NULL", "BIGINT NULL"));
        invoke(guarded, "validate", "latest", false);
        assertThat(snapshot(upgrade)).isEqualTo(beforeChecksum);
        Files.writeString(sqlFile, validSql);
        invoke(guarded, "validate", "latest", true);
        result.put("checksumMismatchRejectedThenOriginalRestored", true);
        result.put("candidateSha256", sha256(Files.readAllBytes(sqlFile)));
        scenarios.add(guarded);

        String failed = schema(service, "failed");
        Scenario failureBase = new Scenario(failed, locations, false, 0);
        invoke(failureBase, "migrate", "latest", true);
        seed(failed, false);
        Path broken = evidence.resolve(service + "-broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("V999__fixture_partial_failure.sql"),
                "UPDATE outbox_events SET retry_count=retry_count+1 WHERE id=10;\n"
                        + "ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL;\n"
                        + "ALTER TABLE outbox_events MODIFY fixture_missing_column BIGINT NOT NULL;\n");
        Scenario failedScenario = new Scenario(failed, locations + ",filesystem:" + broken, true, 0);
        invoke(failedScenario, "migrate", "latest", false);
        assertNullable(failed, "dead_letter_records", "root_record_id", "NO");
        assertNullable(failed, "outbox_events", "record_kind", "YES");
        assertThat(rows(failed, "SELECT retry_count FROM outbox_events WHERE id=10").get(0).get("retry_count")).isEqualTo("4");
        var failedHistory = rows(failed, "SELECT success FROM flyway_schema_history WHERE version='999'");
        assertThat(failedHistory).hasSize(1);
        assertThat(failedHistory.get(0).get("success")).isIn("0", "false");
        Object failedSnapshot = snapshot(failed);
        invoke(failedScenario, "validate", "latest", false);
        invoke(failedScenario, "migrate", "latest", false);
        assertThat(snapshot(failed)).isEqualTo(failedSnapshot);
        result.put("partialDdlAndDmlCommittedWithFailedHistory", true);
        result.put("failedHistoryBlocksNewJvm", true);
        scenarios.add(failedScenario);
    }

    private String schema(String service, String suffix) throws Exception {
        String name = "fl_" + service + "_" + suffix + "_" + nonce;
        rootSql("CREATE DATABASE " + name);
        rootSql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON " + name + ".* TO '" + user + "'@'%'");
        return name;
    }

    private void seed(String schema, boolean legacy) throws Exception {
        sql(schema, "INSERT INTO dead_letter_records (id,root_record_id,cluster_id,topic_generation,origin_topic,"
                + "origin_partition,origin_offset,failed_consumer_group,origin_kind,status,attempt_count,occurred_at) VALUES "
                + "(10," + (legacy ? "NULL" : "10") + ",'fixture',1,'orders',0,10,'group','RESOLVED_ORIGIN','OPEN',3,'2026-10-20 01:02:03.123456'),"
                + "(20,20,'fixture',1,'orders',0,20,'group','RESOLVED_ORIGIN','ACKED',2,'2026-10-20 01:02:04.654321'),"
                + "(21,20,'fixture',1,'orders',0,21,'group','RESOLVED_ORIGIN','OPEN',1,'2026-10-20 01:02:05.000001')");
        sql(schema, "INSERT INTO outbox_events (id,aggregate_type,aggregate_id,event_type,event_id,payload,status,"
                + "retry_count,created_at,record_kind) VALUES (10,'Order','10','Created','fixture-event-10','{}','PENDING',3,"
                + "'2026-10-20 01:02:03.123456'," + (legacy ? "NULL" : "'DOMAIN'") + "),"
                + "(20,'Order','20','Created','fixture-event-20','{}','PUBLISHED',0,'2026-10-20 01:02:03.654321','DOMAIN')");
        sql(schema, "INSERT INTO outbox_events (id,aggregate_type,aggregate_id,event_type,event_id,payload,status,"
                + "retry_count,created_at,record_kind,destination_topic,destination_partition,record_key,"
                + "source_record_timestamp,replay_target_event_id,replay_headers,replay_root_record_id,target_consumer_group)"
                + " VALUES (30,'DLQ','20','__replay__','fixture-attempt-30','{}','PENDING',0,"
                + "'2026-10-20 01:02:04.123456','REPLAY','orders',0,'fixture-key',1792458123123,"
                + "'fixture-target-event','{}',20,'group')");
    }

    private void historyMatches(String schema, Map<String, String> hashes) throws Exception {
        var history = rows(schema, "SELECT script,success,checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(hashes.size());
        assertThat(history.stream().map(r -> r.get("script")).toList()).containsExactlyInAnyOrderElementsOf(
                hashes.keySet().stream().map(p -> Path.of(p).getFileName().toString()).toList());
        for (var row : history) {
            assertThat(row.get("success")).isIn("1", "true");
            assertThat(row.get("checksum")).isNotNull();
        }
    }

    private void assertExistingColumnsPreserved(List<Map<String, String>> before, List<Map<String, String>> after) {
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) assertThat(after.get(i)).containsAllEntriesOf(before.get(i));
    }

    private void assertNullable(String schema, String table, String column, String expected) throws Exception {
        assertThat(rows(schema, "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='" + table + "' AND column_name='" + column + "'").get(0).get("IS_NULLABLE")).isEqualTo(expected);
    }

    private long reserve(String schema) throws Exception {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.executeUpdate("INSERT INTO dead_letter_record_ids VALUES ()", Statement.RETURN_GENERATED_KEYS);
            long id;
            try (var keys = statement.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); id = keys.getLong(1); }
            statement.executeUpdate("DELETE FROM dead_letter_record_ids WHERE id=" + id);
            return id;
        }
    }

    private Map<String, Object> snapshots() throws Exception {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) all.put(scenario.schema(), snapshot(scenario.schema()));
        return all;
    }

    private Object snapshot(String schema) throws Exception {
        return Map.of("ledger", rows(schema, "SELECT * FROM dead_letter_records ORDER BY id"),
                "outbox", rows(schema, "SELECT * FROM outbox_events ORDER BY id"),
                "allocator", rows(schema, "SELECT * FROM dead_letter_record_ids ORDER BY id"),
                "history", rows(schema, "SELECT * FROM flyway_schema_history ORDER BY installed_rank"),
                "columns", rows(schema, "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT "
                        + "FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION"));
    }

    private List<Map<String, String>> rows(String schema, String query) throws Exception {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var results = statement.executeQuery(query)) {
                List<Map<String, String>> rows = new ArrayList<>();
                while (results.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= results.getMetaData().getColumnCount(); i++) {
                        row.put(results.getMetaData().getColumnLabel(i), results.getString(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private void sql(String schema, String sql) throws Exception {
        try (Connection connection = connection(schema); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute(sql);
        }
    }

    private void rootSql(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url("peekcart_test"), "root", SharedContainers.MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute(sql);
        }
    }

    private Connection connection(String schema) throws SQLException {
        return DriverManager.getConnection(url(schema), user, password);
    }

    private String url(String schema) {
        return "jdbc:mysql://" + SharedContainers.MYSQL.getHost() + ":" + mysqlPort
                + "/" + schema + "?connectTimeout=2000&socketTimeout=3000&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private Map<String, Object> invoke(Scenario scenario, String operation, String target, boolean expectedSuccess) throws Exception {
        String id = String.format("%03d-%s-%s", ++invocationNumber, scenario.schema(), operation);
        Path resultFile = evidence.resolve(id + ".json");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("flyway.lab.classpath"), FlywayContractProcess.class.getName(),
                operation, scenario.locations(), target, resultFile.toString(), id);
        builder.environment().putAll(Map.of("FLYWAY_LAB_URL", url(scenario.schema()), "FLYWAY_LAB_USER", user,
                "FLYWAY_LAB_PASSWORD", password));
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve(id + ".log").toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("child JVM deadline %s", id).isTrue();
            Map<String, Object> result = JSON.readValue(resultFile.toFile(), new TypeReference<>() {});
            result.put("exitCode", process.exitValue());
            invocations.add(result);
            assertThat(((Number) result.get("pid")).longValue()).isEqualTo(process.pid()).isNotEqualTo(ProcessHandle.current().pid());
            assertThat(result.get("success")).as(id).isEqualTo(expectedSuccess);
            assertThat(process.exitValue()).as(id).isEqualTo(expectedSuccess ? 0 : 2);
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private int inspectOwnedEndpoint(String phase) throws Exception {
        String id = SharedContainers.MYSQL.getContainerId();
        Path output = evidence.resolve(phase + ".json");
        // Select only identity/state/ports: a full inspect would include fixture credentials.
        String format = "{\"id\":\"{{.Id}}\",\"status\":{{json .State.Status}},"
                + "\"startedAt\":{{json .State.StartedAt}},\"ports\":{{json .NetworkSettings.Ports}}}";
        Process process = new ProcessBuilder("docker", "inspect", "--format", format, id)
                .redirectOutput(output.toFile()).redirectError(evidence.resolve(phase + ".stderr").toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("owned inspect deadline").isTrue();
            assertThat(process.exitValue()).isZero();
            var actual = JSON.readTree(output.toFile());
            assertThat(actual.path("id").asText()).isEqualTo(id);
            assertThat(actual.path("status").asText()).isEqualTo("running");
            var bindings = actual.path("ports").path("3306/tcp");
            assertThat(bindings.isArray() && !bindings.isEmpty()).isTrue();
            int port = Integer.parseInt(bindings.get(0).path("HostPort").asText());
            assertThat(port).isBetween(1, 65535);
            return port;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = connection(scenarios.get(0).schema())) {
                if (connection.isValid(2)) return;
            } catch (SQLException failure) { last = failure; }
            Thread.sleep(250);
        }
        throw new IllegalStateException("owned MySQL did not become ready after restart", last);
    }

    private void write(String name, Object value) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name).toFile(), value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Scenario(String schema, String locations, boolean failed, long lastReserved) {}
}
