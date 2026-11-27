package cn.sunline.tss.tssveri.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.h2.tools.RunScript;
import org.h2.util.ScriptReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the queries from checked-in Axon-generated NEW17 unchanged. H2 does not validate vendor dialects. */
class ExistsSubqueryComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATE = "20261127";
    private static final List<String> FIELD_NAMES = List.of("tenant_id", "item_id", "gntltr_id", "amount");

    @Test void publishesCorrelatedExistsAndNotExistsWithIdenticalSampleAndFullExistsResults() throws Exception {
        try (var fixture = fixture(); var ignored = new LocalSpark()) {
            for (var job : fixture.jobs) {
                try (var sql = fixture.metadata.createStatement()) {
                    sql.execute("INSERT INTO TSS_BCOMP_TASK_NEW(BCOMP_INDEX,TASK_BUSI_DATE,TASK_BATCH,BCOMP_TIME_NODE,TASK_STATE) VALUES("
                        + job.index + ",'" + DATE + "','exists','D','2')");
                }
                SparkHandlerTable.main(fixture.arguments(job));
                assertPublication(fixture.metadata, job);
            }
            assertEquals(details(fixture.metadata, fixture.jobs.get(0).index),
                details(fixture.metadata, fixture.jobs.get(2).index), "EXISTS sample/full must publish the same complete small fixture");
            assertEquals(0, scalar(fixture.metadata, "SELECT COUNT(*) FROM TSS_BCOMP_FULL_STAGE_NEW"));
            assertEquals(0, scalar(fixture.metadata, "SELECT COUNT(*) FROM TSS_BCOMP_FULL_WIN_NEW"));
        }
    }

    @Test void readsEveryCompositeKeyExactlyOnceAcrossFourJdbcHashPartitionsOfTheGeneratedWrapper() throws Exception {
        try (var fixture = fixture(); var ignored = new LocalSpark();
             var spark = SparkSession.builder().appName("exists-subquery-hash").getOrCreate()) {
            for (var job : fixture.jobs) {
                for (boolean origin : List.of(true, false)) {
                    String url = origin ? fixture.originUrl : fixture.destinationUrl;
                    String source = origin ? job.originSql : job.destinationSql;
                    Set<String> expected = expectedKeys(job.negative(), origin);
                    String[] predicates;
                    try (var connection = DriverManager.getConnection(url, "sa", "")) {
                        predicates = PrimaryKeyHashPlanner.predicates(connection, source, List.of(1, 2), 4);
                    }
                    assertNotNull(predicates);
                    assertEquals(4, predicates.length);
                    var properties = new Properties();
                    properties.setProperty("user", "sa");
                    properties.setProperty("password", "");
                    properties.setProperty("driver", "org.h2.Driver");
                    var frame = spark.read().jdbc(url, source, predicates, properties);
                    assertEquals(4, frame.javaRDD().getNumPartitions());
                    List<Row> rows = frame.collectAsList();
                    var actual = new TreeSet<String>();
                    for (Row row : rows) {
                        assertTrue(actual.add(row.getString(0) + "/" + row.getString(1)), "overlapping HASH buckets duplicated a business key");
                    }
                    assertEquals(expected.size(), rows.size(), "child-table matches must not multiply outer rows");
                    assertEquals(expected, actual, job.code + (origin ? " origin" : " destination"));
                }
            }
        }
    }

    private static void assertPublication(Connection connection, Job job) throws Exception {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT ORIGIN_COUNT,DESTINATION_COUNT,MATCHED_COUNT,DIFFERENT_COUNT,ONLY_ORIGIN_COUNT,ONLY_DESTINATION_COUNT,"
                + "DETAIL_LIMIT,ORIGIN_SAMPLES,DESTINATION_SAMPLES,DIFFERENT_SAMPLES,SOURCE_TABLE_NAME,CONFIGURATION_SCOPE "
                + "FROM TSS_BCOMP_RESULT_NEW WHERE BCOMP_INDEX=" + job.index)) {
            assertTrue(rows.next());
            assertEquals(4, rows.getLong(1));
            assertEquals(4, rows.getLong(2));
            assertEquals(job.negative() ? 3 : 2, rows.getLong(3));
            assertEquals(job.negative() ? 0 : 1, rows.getLong(4));
            assertEquals(1, rows.getLong(5));
            assertEquals(1, rows.getLong(6));
            assertEquals(job.limit, rows.getInt(7));
            assertEquals(1, rows.getLong(8));
            assertEquals(1, rows.getLong(9));
            assertEquals(job.negative() ? 0 : 1, rows.getLong(10));
            assertEquals("kgnb_chrg_item", rows.getString(11));
            assertEquals(job.scope, rows.getString(12));
            assertFalse(rows.next());
        }
        var actual = details(connection, job.index);
        assertEquals(Set.of("2:[\"T1\",\"" + (job.negative() ? "3" : "4") + "\"]",
            "3:[\"T1\",\"" + (job.negative() ? "4" : "3") + "\"]"),
            actual.keySet().stream().filter(key -> !key.startsWith("4:")).collect(java.util.stream.Collectors.toSet()));
        if (job.negative()) {
            assertEquals(2, actual.size());
        } else {
            assertEquals(3, actual.size());
            var difference = JSON.readTree(actual.get("4:[\"T1\",\"2\"]"));
            assertEquals(1, difference.path("fields").size());
            var field = difference.path("fields").get(0);
            assertEquals("amount", field.path("name").asText());
            assertEquals("200", field.path("originValue").asText());
            assertEquals("201", field.path("destinationValue").asText());
        }
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT FIELD_NAME,DIFFERENT_COUNT FROM TSS_BCOMP_FIELD_COUNT_NEW WHERE BCOMP_INDEX=" + job.index)) {
            if (job.negative()) {
                assertFalse(rows.next());
            } else {
                assertTrue(rows.next());
                assertEquals("amount", rows.getString(1));
                assertEquals(1, rows.getLong(2));
                assertFalse(rows.next());
            }
        }
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT TASK_STATE,FILE1_ACOUNT,FILE2_ACOUNT,BCOMP_READ_MODE FROM TSS_BCOMP_TASK_NEW WHERE BCOMP_INDEX=" + job.index)) {
            assertTrue(rows.next());
            assertEquals("3", rows.getString(1));
            assertEquals(4, rows.getLong(2));
            assertEquals(4, rows.getLong(3));
            assertTrue(rows.getString(4).contains("HASHJDBC"));
            assertTrue(rows.getString(4).contains("1分区"), "small queries deliberately use one automatic partition");
            assertFalse(rows.next());
        }
    }

    private static Map<String, String> details(Connection connection, long index) throws SQLException {
        var result = new TreeMap<String, String>();
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT LINE_RESULT,COMP_INDEX_STR,DETAIL_JSON FROM TSS_BCOMP_DTL_NEW WHERE BCOMP_INDEX=" + index)) {
            while (rows.next()) {
                assertNull(result.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3)), "duplicate published business key");
            }
        }
        return result;
    }

    private static long scalar(Connection connection, String query) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Set<String> expectedKeys(boolean negative, boolean origin) {
        return negative ? Set.of("T1/" + (origin ? "4" : "3"), "T1/5", "T1/6", "T2/2")
            : Set.of("T1/1", "T1/2", "T1/" + (origin ? "3" : "4"), "T2/1");
    }

    private static Fixture fixture() throws Exception {
        Path fixturePath = Path.of(System.getProperty("bd.exists.fixture", "../sql/test-fixtures/axon-exists-new17.sql"));
        assertTrue(Files.isRegularFile(fixturePath), "Missing actual Axon-generated NEW17; override with -Dbd.exists.fixture=/absolute/path.sql");
        String id = UUID.randomUUID().toString();
        var fixture = new Fixture("jdbc:h2:mem:exists_metadata_" + id,
            "jdbc:h2:mem:exists_origin_" + id + ";DATABASE_TO_LOWER=TRUE",
            "jdbc:h2:mem:exists_destination_" + id + ";DATABASE_TO_LOWER=TRUE");
        try {
            try (var ddl = Files.newBufferedReader(Path.of("../sql/create_bcomp_new.sql"))) {
                RunScript.execute(fixture.metadata, ddl);
            }
            try (var sql = fixture.metadata.createStatement()) {
                sql.execute("INSERT INTO TSS_BCOMP_DATABASE_CONFIG_NEW(DATABASE_ID,DATABASE_NAME) VALUES(1,'528'),(2,'ccbs')");
            }
            loadGeneratedFixture(fixture.metadata, fixturePath);
            createSource(fixture.origin, true);
            createSource(fixture.destination, false);
            try (var sql = fixture.metadata.createStatement(); var rows = sql.executeQuery(
                "SELECT c.BCOMP_INDEX,c.BCOMP_NAME,c.BCOMP_SAMPLE_LIMIT,c.CONFIGURATION_SCOPE,s.ORIG_SQL,s.DEST_SQL "
                    + "FROM TSS_BCOMP_CONF_NEW c JOIN TSS_BCOMP_TABLE_SQL_NEW s ON c.BCOMP_INDEX=s.BCOMP_INDEX ORDER BY c.BCOMP_INDEX")) {
                while (rows.next()) fixture.jobs.add(new Job(rows.getLong(1), rows.getString(2), rows.getInt(3),
                    rows.getString(4), rows.getString(5), rows.getString(6)));
            }
            assertEquals(List.of("kgnb_chrg_item", "kgnb_chrg_item_1", "kgnb_chrg_item_2"), fixture.jobs.stream().map(Job::code).toList());
            assertEquals(List.of(100, -1, -1), fixture.jobs.stream().map(Job::limit).toList());
            assertEquals(List.of(false, true, false), fixture.jobs.stream().map(Job::negative).toList());
            for (var job : fixture.jobs) {
                assertTrue(job.scope.toUpperCase(Locale.ROOT).contains("EXISTS"));
                assertTrue(job.originSql.toLowerCase(Locale.ROOT).contains("result_q"), "retain the actual generator's double wrapper");
                try (var sql = fixture.metadata.createStatement(); var rows = sql.executeQuery(
                    "SELECT FIELD_NAME,FIELD_INDEX_FLAG FROM TSS_BCOMP_FIELD_NEW WHERE BCOMP_INDEX=" + job.index + " ORDER BY FIELD_OLD_INDEX")) {
                    var fields = new ArrayList<String>();
                    var keys = new ArrayList<String>();
                    while (rows.next()) { fields.add(rows.getString(1)); if ("1".equals(rows.getString(2))) keys.add(rows.getString(1)); }
                    assertEquals(FIELD_NAMES, fields);
                    assertEquals(FIELD_NAMES.subList(0, 2), keys);
                }
            }
            return fixture;
        } catch (Throwable error) {
            fixture.close();
            throw error;
        }
    }

    private static void loadGeneratedFixture(Connection connection, Path path) throws Exception {
        // H2 cannot execute START TRANSACTION. Adapt only transaction control; preserve every generated SQL literal.
        connection.setAutoCommit(false);
        try (var reader = new ScriptReader(Files.newBufferedReader(path)); var sql = connection.createStatement()) {
            reader.setSkipRemarks(true);
            String command;
            while ((command = reader.readStatement()) != null) {
                String marker = command.trim();
                if (marker.isEmpty() || marker.equalsIgnoreCase("START TRANSACTION")) continue;
                if (marker.equalsIgnoreCase("COMMIT")) connection.commit();
                else sql.execute(command);
            }
            connection.commit();
        }
        connection.setAutoCommit(true);
    }

    private static void createSource(Connection connection, boolean origin) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE KGNB_CHRG_ITEM(TENANT_ID VARCHAR(10),ITEM_ID VARCHAR(10),GNTLTR_ID VARCHAR(10),AMOUNT VARCHAR(20),PRIMARY KEY(TENANT_ID,ITEM_ID))");
            sql.execute("INSERT INTO KGNB_CHRG_ITEM VALUES('T1','1','G1','100'),('T1','2','G2','" + (origin ? "200" : "201")
                + "'),('T1','3','G3','300'),('T1','4','G4','400'),('T1','5','G5','500'),('T1','6','G6','600'),('T2','1','G7','700'),('T2','2','G8','800')");
            sql.execute("CREATE TABLE KGNA_GNTL_ACCT(GNTLTR_ID VARCHAR(10),GNTLTR_BSN_STCD VARCHAR(10))");
            // G1 matches twice; G5 has no child; G8 is UNKNOWN for <> 'S'; tenant T2 reuses item ID=1.
            sql.execute("INSERT INTO KGNA_GNTL_ACCT VALUES('G1','A'),('G1','B'),('G2','A'),('G3','" + (origin ? "A" : "S")
                + "'),('G4','" + (origin ? "S" : "A") + "'),('G6','S'),('G7','A'),('G8',NULL),(NULL,'A')");
        }
    }

    private record Job(long index, String code, int limit, String scope, String originSql, String destinationSql) {
        boolean negative() { return scope.toUpperCase(Locale.ROOT).contains("NOT EXISTS"); }
    }

    private static final class Fixture implements AutoCloseable {
        final String metadataUrl, originUrl, destinationUrl;
        final Connection metadata, origin, destination;
        final List<Job> jobs = new ArrayList<>();
        Fixture(String metadataUrl, String originUrl, String destinationUrl) throws SQLException {
            this.metadataUrl = metadataUrl; this.originUrl = originUrl; this.destinationUrl = destinationUrl;
            metadata = DriverManager.getConnection(metadataUrl, "sa", "");
            origin = DriverManager.getConnection(originUrl, "sa", "");
            destination = DriverManager.getConnection(destinationUrl, "sa", "");
        }
        String[] arguments(Job job) {
            return new String[]{"exists-" + job.index, metadataUrl, "sa", "", "org.h2.Driver", "100", Long.toString(job.index), DATE, "exists", "D",
                originUrl, "sa", "", "org.h2.Driver", job.originSql, destinationUrl, "sa", "", "org.h2.Driver", job.destinationSql, DATE};
        }
        public void close() throws SQLException { try (metadata; origin; destination) { } }
    }

    private static final class LocalSpark implements AutoCloseable {
        final Map<String, String> previous = new HashMap<>();
        LocalSpark() {
            Map.of("spark.master", "local[2]", "spark.ui.enabled", "false", "spark.driver.host", "127.0.0.1",
                "spark.driver.bindAddress", "127.0.0.1", "spark.default.parallelism", "2", "spark.sql.shuffle.partitions", "2")
                .forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
        }
        public void close() {
            if (SparkSession.getDefaultSession().isDefined()) SparkSession.getDefaultSession().get().stop();
            SparkSession.clearActiveSession(); SparkSession.clearDefaultSession();
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }
}
