package io.cortexj.data.jdbc;

import io.cortexj.data.ColType;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcFrameTest {

    @Test
    void readsTypedQueryResult() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:test;MODE=Legacy")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"reviews\" (\"id\" BIGINT, \"stars\" INT, \"score\" DOUBLE, "
                        + "\"verified\" BOOLEAN, \"text\" VARCHAR(64))");
                statement.execute("INSERT INTO \"reviews\" VALUES (1, 5, 4.5, TRUE, 'great')");
                statement.execute("INSERT INTO \"reviews\" VALUES (2, 1, 1.0, FALSE, 'bad')");
                statement.execute("INSERT INTO \"reviews\" VALUES (3, 4, 3.0, NULL, NULL)");
            }
            Frame frame = JdbcFrame.read(connection,
                    "SELECT \"id\", \"stars\", \"score\", \"verified\", \"text\" FROM \"reviews\" ORDER BY \"id\"");
            assertEquals(java.util.Arrays.asList("id", "stars", "score", "verified", "text"),
                    frame.schema().names());
            assertEquals(java.util.Arrays.asList(ColType.LONG, ColType.LONG, ColType.DOUBLE,
                    ColType.BOOLEAN, ColType.STRING), frame.schema().types());
            assertEquals(3, frame.rowCount());
            assertEquals(Long.valueOf(1L), frame.column("id").get(0));
            assertEquals(Double.valueOf(4.5), (Double) frame.column("score").get(0), 1e-9);
            assertNull(frame.column("verified").get(2));
            assertNull(frame.column("text").get(2));
            // fingerprint is stable across reads
            Frame again = JdbcFrame.read(connection,
                    "SELECT \"id\", \"stars\", \"score\", \"verified\", \"text\" FROM \"reviews\" ORDER BY \"id\"");
            assertEquals(Fingerprint.ofFrame(frame), Fingerprint.ofFrame(again));
        }
    }

    @Test
    void parameterizedQueryWorks() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:params;MODE=Legacy")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"t\" (\"id\" BIGINT, \"text\" VARCHAR(32))");
                statement.execute("INSERT INTO \"t\" VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            }
            Frame frame = JdbcFrame.read(connection, "SELECT \"id\", \"text\" FROM \"t\" WHERE \"id\" > ?",
                    1);
            assertEquals(2, frame.rowCount());
            assertEquals("b", frame.column("text").get(0));
        }
    }
}
