package dev.bluetext.jdbc;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryResultSet — parsing N1QL JSON responses into JDBC ResultSet.
 */
class QueryResultSetTest {

    // --- Basic iteration ---

    @Nested class Iteration {

        @Test void emptyResultSet() throws Exception {
            var rs = new QueryResultSet("{\"results\":[], \"status\":\"success\"}");
            assertFalse(rs.next());
        }

        @Test void singleRow() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"abc\",\"count\":42}], \"status\":\"success\"}");
            assertTrue(rs.next());
            assertFalse(rs.next());
        }

        @Test void multipleRows() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}], \"status\":\"success\"}");
            assertTrue(rs.next());
            assertTrue(rs.next());
            assertTrue(rs.next());
            assertFalse(rs.next());
        }
    }

    // --- String getters ---

    @Nested class StringGetters {

        @Test void getStringByName() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"name\":\"alice\",\"email\":\"alice@test.com\"}], \"status\":\"success\"}");
            rs.next();
            assertEquals("alice", rs.getString("name"));
            assertEquals("alice@test.com", rs.getString("email"));
        }

        @Test void getStringByIndex() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"abc\",\"data\":\"xyz\"}], \"status\":\"success\"}");
            rs.next();
            assertEquals("abc", rs.getString(1));
            assertEquals("xyz", rs.getString(2));
        }

        @Test void getStringNull() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":null}], \"status\":\"success\"}");
            rs.next();
            assertNull(rs.getString("id"));
            assertTrue(rs.wasNull());
        }

        @Test void getStringMissingColumn() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"abc\"}], \"status\":\"success\"}");
            rs.next();
            assertNull(rs.getString("nonexistent"));
            assertTrue(rs.wasNull());
        }
    }

    // --- Numeric getters ---

    @Nested class NumericGetters {

        @Test void getInt() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"active\":1}], \"status\":\"success\"}");
            rs.next();
            assertEquals(1, rs.getInt("active"));
        }

        @Test void getLong() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"expires\":1775136298}], \"status\":\"success\"}");
            rs.next();
            assertEquals(1775136298L, rs.getLong("expires"));
        }

        @Test void getDouble() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"score\":3.14}], \"status\":\"success\"}");
            rs.next();
            assertEquals(3.14, rs.getDouble("score"), 0.001);
        }

        @Test void getIntNull() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"count\":null}], \"status\":\"success\"}");
            rs.next();
            assertEquals(0, rs.getInt("count"));
            assertTrue(rs.wasNull());
        }

        @Test void getLongNull() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"expires\":null}], \"status\":\"success\"}");
            rs.next();
            assertEquals(0L, rs.getLong("expires"));
            assertTrue(rs.wasNull());
        }
    }

    // --- Boolean getter ---

    @Nested class BooleanGetter {

        @Test void numericOneIsTrue() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"active\":1}], \"status\":\"success\"}");
            rs.next();
            assertTrue(rs.getBoolean("active"));
        }

        @Test void numericZeroIsFalse() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"active\":0}], \"status\":\"success\"}");
            rs.next();
            assertFalse(rs.getBoolean("active"));
        }

        @Test void booleanTrue() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"flag\":true}], \"status\":\"success\"}");
            rs.next();
            assertTrue(rs.getBoolean("flag"));
        }

        @Test void booleanFalse() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"flag\":false}], \"status\":\"success\"}");
            rs.next();
            assertFalse(rs.getBoolean("flag"));
        }

        @Test void stringTrueIsTrue() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"flag\":\"true\"}], \"status\":\"success\"}");
            rs.next();
            assertTrue(rs.getBoolean("flag"));
        }

        @Test void stringOneIsTrue() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"flag\":\"1\"}], \"status\":\"success\"}");
            rs.next();
            assertTrue(rs.getBoolean("flag"));
        }

        @Test void nullIsFalse() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"flag\":null}], \"status\":\"success\"}");
            rs.next();
            assertFalse(rs.getBoolean("flag"));
            assertTrue(rs.wasNull());
        }
    }

    // --- Bytes (Base64 round-trip) ---

    @Nested class BytesGetter {

        @Test void base64RoundTrip() throws Exception {
            byte[] original = {0x01, 0x02, 0x03, (byte)0xFF};
            String encoded = Base64.getEncoder().encodeToString(original);
            var rs = new QueryResultSet("{\"results\":[{\"data\":\"" + encoded + "\"}], \"status\":\"success\"}");
            rs.next();
            assertArrayEquals(original, rs.getBytes("data"));
        }

        @Test void nullBytes() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"data\":null}], \"status\":\"success\"}");
            rs.next();
            assertNull(rs.getBytes("data"));
        }

        @Test void nonBase64FallsBackToUtf8() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"data\":\"plain text\"}], \"status\":\"success\"}");
            rs.next();
            assertArrayEquals("plain text".getBytes(), rs.getBytes("data"));
        }
    }

    // --- SELECT * auto-unwrap ---

    @Nested class SelectStarUnwrap {

        @Test void unwrapsSingleKeyNesting() throws Exception {
            // N1QL SELECT * returns: {"sessions": {"id": "abc", "data": "xyz"}}
            var rs = new QueryResultSet("{\"results\":[{\"sessions\":{\"id\":\"abc\",\"data\":\"xyz\"}}], \"status\":\"success\"}");
            rs.next();
            assertEquals("abc", rs.getString("id"));
            assertEquals("xyz", rs.getString("data"));
        }

        @Test void doesNotUnwrapMultipleKeys() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"abc\",\"data\":\"xyz\"}], \"status\":\"success\"}");
            rs.next();
            assertEquals("abc", rs.getString("id"));
            assertEquals("xyz", rs.getString("data"));
        }
    }

    // --- Error detection ---

    @Nested class ErrorDetection {

        @Test void fatalStatusThrows() {
            assertThrows(SQLException.class, () ->
                new QueryResultSet("{\"errors\":[{\"code\":24045,\"msg\":\"Collection not found\"}],\"status\":\"fatal\"}"));
        }

        @Test void errorsStatusThrows() {
            assertThrows(SQLException.class, () ->
                new QueryResultSet("{\"errors\":[{\"code\":3000,\"msg\":\"Syntax error\"}],\"status\":\"errors\"}"));
        }

        @Test void successStatusDoesNotThrow() {
            assertDoesNotThrow(() ->
                new QueryResultSet("{\"results\":[],\"status\":\"success\"}"));
        }
    }

    // --- Metadata ---

    @Nested class Metadata {

        @Test void columnCount() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"a\",\"name\":\"b\",\"count\":1}], \"status\":\"success\"}");
            var meta = rs.getMetaData();
            assertEquals(3, meta.getColumnCount());
        }

        @Test void columnNames() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"a\",\"name\":\"b\"}], \"status\":\"success\"}");
            var meta = rs.getMetaData();
            assertEquals("id", meta.getColumnName(1));
            assertEquals("name", meta.getColumnName(2));
        }

        @Test void findColumn() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"a\",\"name\":\"b\"}], \"status\":\"success\"}");
            rs.next();
            assertEquals(1, rs.findColumn("id"));
            assertEquals(2, rs.findColumn("name"));
        }

        @Test void findColumnThrowsForUnknown() throws Exception {
            var rs = new QueryResultSet("{\"results\":[{\"id\":\"a\"}], \"status\":\"success\"}");
            rs.next();
            assertThrows(SQLException.class, () -> rs.findColumn("nonexistent"));
        }
    }
}
