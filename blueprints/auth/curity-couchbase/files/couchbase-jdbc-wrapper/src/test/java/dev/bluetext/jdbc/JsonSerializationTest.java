package dev.bluetext.jdbc;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSON serialization (RFC 8259) and document KEY resolution.
 */
class JsonSerializationTest {

    // --- jsonString (RFC 8259 escaping) ---

    @Nested class JsonStringEscaping {

        @Test void plainString() {
            assertEquals("\"hello\"", QueryPreparedStatement.jsonString("hello"));
        }

        @Test void escapesDoubleQuotes() {
            assertEquals("\"he said \\\"hi\\\"\"", QueryPreparedStatement.jsonString("he said \"hi\""));
        }

        @Test void escapesBackslash() {
            assertEquals("\"path\\\\to\\\\file\"", QueryPreparedStatement.jsonString("path\\to\\file"));
        }

        @Test void escapesNewline() {
            assertEquals("\"line1\\nline2\"", QueryPreparedStatement.jsonString("line1\nline2"));
        }

        @Test void escapesCarriageReturn() {
            assertEquals("\"line1\\rline2\"", QueryPreparedStatement.jsonString("line1\rline2"));
        }

        @Test void escapesTab() {
            assertEquals("\"col1\\tcol2\"", QueryPreparedStatement.jsonString("col1\tcol2"));
        }

        @Test void escapesBackspace() {
            assertEquals("\"before\\bafter\"", QueryPreparedStatement.jsonString("before\bafter"));
        }

        @Test void escapesFormFeed() {
            assertEquals("\"before\\fafter\"", QueryPreparedStatement.jsonString("before\fafter"));
        }

        @Test void escapesControlCharAsUnicode() {
            // \u0001 should be escaped as \\u0001
            assertEquals("\"\\u0001\"", QueryPreparedStatement.jsonString("\u0001"));
        }

        @Test void nullReturnsJsonNull() {
            assertEquals("null", QueryPreparedStatement.jsonString(null));
        }
    }

    // --- toJsonValue ---

    @Nested class ToJsonValue {

        @Test void nullValue() {
            assertEquals("null", QueryPreparedStatement.toJsonValue(null));
        }

        @Test void stringValue() {
            assertEquals("\"hello\"", QueryPreparedStatement.toJsonValue("hello"));
        }

        @Test void integerValue() {
            assertEquals("42", QueryPreparedStatement.toJsonValue(42));
        }

        @Test void longValue() {
            assertEquals("1775136298", QueryPreparedStatement.toJsonValue(1775136298L));
        }

        @Test void doubleValue() {
            assertEquals("3.14", QueryPreparedStatement.toJsonValue(3.14));
        }

        @Test void booleanTrue() {
            assertEquals("true", QueryPreparedStatement.toJsonValue(true));
        }

        @Test void booleanFalse() {
            assertEquals("false", QueryPreparedStatement.toJsonValue(false));
        }
    }
}
