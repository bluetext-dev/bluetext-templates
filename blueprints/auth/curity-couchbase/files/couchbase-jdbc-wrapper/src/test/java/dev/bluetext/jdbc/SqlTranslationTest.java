package dev.bluetext.jdbc;

import dev.bluetext.jdbc.SqlPPConnection.TranslatedSql;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PostgreSQL → Couchbase SQL++ translation.
 * Calls SqlPPConnection.translateSql() directly — pure static method, no connection needed.
 */
class SqlTranslationTest {

    private static final String CATALOG = "curity";

    private TranslatedSql t(String sql) { return SqlPPConnection.translateSql(sql, CATALOG); }

    // --- Identifier quoting ---

    @Nested class IdentifierQuoting {

        @Test void doubleQuotesToBackticks() {
            var r = t("SELECT \"id\", \"username\" FROM \"accounts\"");
            assertTrue(r.sql().contains("`id`"));
            assertTrue(r.sql().contains("`username`"));
        }

        @Test void preservesStringLiterals() {
            var r = t("SELECT * FROM \"accounts\" WHERE \"status\" = 'active'");
            assertTrue(r.sql().contains("'active'"));
        }
    }

    // --- Table qualification ---

    @Nested class TableQualification {

        @Test void qualifiesFrom() {
            assertTrue(t("SELECT \"id\" FROM \"sessions\"").sql().contains("`curity`.`_default`.`sessions`"));
        }

        @Test void qualifiesUpdate() {
            assertTrue(t("UPDATE \"sessions\" SET \"expires\" = ?").sql().contains("`curity`.`_default`.`sessions`"));
        }

        @Test void qualifiesDelete() {
            assertTrue(t("DELETE FROM \"sessions\" WHERE \"id\" = ?").sql().contains("`curity`.`_default`.`sessions`"));
        }

        @Test void qualifiesInsertInto() {
            assertTrue(t("INSERT INTO \"sessions\" (\"id\") VALUES (?)").sql().contains("`curity`.`_default`.`sessions`"));
        }

        @Test void doesNotDoubleQualify() {
            var r = SqlPPConnection.translateSql("SELECT * FROM `curity`.`_default`.`sessions`", CATALOG);
            assertFalse(r.sql().contains("`curity`.`_default`.`curity`"));
        }

        @Test void noCatalogNoQualification() {
            var r = SqlPPConnection.translateSql("SELECT * FROM \"sessions\"", null);
            assertTrue(r.sql().contains("`sessions`"));
            assertFalse(r.sql().contains("_default"));
        }
    }

    // --- DDL detection ---

    @Nested class DdlDetection {

        @Test void createTable()      { assertNull(t("CREATE TABLE \"sessions\" (\"id\" VARCHAR(64))")); }
        @Test void createIndex()      { assertNull(t("CREATE INDEX idx ON \"sessions\" (\"id\")")); }
        @Test void createUniqueIndex(){ assertNull(t("CREATE UNIQUE INDEX idx ON \"sessions\" (\"id\")")); }
        @Test void createSequence()   { assertNull(t("CREATE SEQUENCE seq START WITH 1")); }
        @Test void alterTable()       { assertNull(t("ALTER TABLE \"sessions\" ADD COLUMN \"foo\" VARCHAR(32)")); }
        @Test void dropTable()        { assertNull(t("DROP TABLE \"sessions\"")); }
        @Test void dropIndex()        { assertNull(t("DROP INDEX idx")); }
        @Test void truncate()         { assertNull(t("TRUNCATE \"sessions\"")); }
        @Test void setStatement()     { assertNull(t("SET search_path TO public")); }

        @Test void selectIsNotDdl()   { assertNotNull(t("SELECT 1")); }
        @Test void insertIsNotDdl()   { assertNotNull(t("INSERT INTO \"sessions\" (\"id\") VALUES (?)")); }
        @Test void updateIsNotDdl()   { assertNotNull(t("UPDATE \"sessions\" SET \"id\" = ?")); }
        @Test void deleteIsNotDdl()   { assertNotNull(t("DELETE FROM \"sessions\"")); }
    }

    // --- INSERT conversion ---

    @Nested class InsertConversion {

        @Test void convertsToKeyValue() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?)");
            assertTrue(r.sql().contains("(KEY, VALUE) VALUES"));
            assertTrue(r.sql().contains("\"__KEY__\""));
        }

        @Test void jsonObjectHasAllColumns() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?)");
            assertTrue(r.sql().contains("\"id\": ?"));
            assertTrue(r.sql().contains("\"session_data\": ?"));
            assertTrue(r.sql().contains("\"expires\": ?"));
        }

        @Test void parameterCountPreserved() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?)");
            long count = r.sql().chars().filter(c -> c == '?').count();
            assertEquals(3, count, "Must not add extra ? placeholders");
        }

        @Test void plainInsertUsesInsert() {
            var r = t("INSERT INTO \"sessions\" (\"id\") VALUES (?)");
            assertTrue(r.sql().startsWith("INSERT INTO"));
        }

        @Test void onConflictUsesUpsert() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?) " +
                       "ON CONFLICT (\"id\") DO UPDATE SET \"session_data\" = ?, \"expires\" = ?");
            assertTrue(r.sql().startsWith("UPSERT INTO"));
        }

        @Test void updateNotConverted() {
            var r = t("UPDATE \"sessions\" SET \"expires\" = ? WHERE \"id\" = ?");
            assertFalse(r.sql().contains("KEY, VALUE"));
            assertTrue(r.sql().startsWith("UPDATE"));
        }

        @Test void deleteNotConverted() {
            var r = t("DELETE FROM \"sessions\" WHERE \"id\" = ?");
            assertFalse(r.sql().contains("KEY, VALUE"));
            assertTrue(r.sql().startsWith("DELETE"));
        }
    }

    // --- PK strategies ---

    @Nested class PkStrategies {

        @Test void sessionsProvided() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.PROVIDED, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1}, r.pkInfo().pkParamIndices());
        }

        @Test void accountsCoalesceUuid() {
            var r = t("INSERT INTO \"accounts\" (\"account_id\", \"username\", \"active\", \"created\", \"updated\") VALUES (?, ?, ?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.COALESCE_UUID, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1}, r.pkInfo().pkParamIndices());
        }

        @Test void credentialsAlwaysUuid() {
            var r = t("INSERT INTO \"credentials\" (\"id\", \"subject\", \"password\", \"attributes\", \"created\", \"updated\") VALUES (?, ?, ?, ?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.ALWAYS_UUID, r.pkInfo().strategy());
        }

        @Test void linkedAccountsComposite() {
            var r = t("INSERT INTO \"linked_accounts\" (\"account_id\", \"linked_account_id\", \"linked_account_domain_name\", \"created\") VALUES (?, ?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.COMPOSITE, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1, 2, 3}, r.pkInfo().pkParamIndices());
        }

        @Test void tokensUsesTokenHash() {
            var r = t("INSERT INTO \"tokens\" (\"token_hash\", \"id\", \"delegations_id\") VALUES (?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.PROVIDED, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1}, r.pkInfo().pkParamIndices());
        }

        @Test void noncesUsesToken() {
            var r = t("INSERT INTO \"nonces\" (\"token\", \"reference_data\", \"created\", \"ttl\") VALUES (?, ?, ?, ?)");
            assertArrayEquals(new int[]{1}, r.pkInfo().pkParamIndices());
        }

        @Test void databaseClientsComposite() {
            var r = t("INSERT INTO \"database_clients\" (\"client_id\", \"profile_id\", \"owner\", \"created\") VALUES (?, ?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.COMPOSITE, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1, 2}, r.pkInfo().pkParamIndices());
        }

        @Test void databaseServiceProvidersComposite() {
            var r = t("INSERT INTO \"database_service_providers\" (\"id\", \"profile_id\", \"owner\", \"created\") VALUES (?, ?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.COMPOSITE, r.pkInfo().strategy());
            assertArrayEquals(new int[]{1, 2}, r.pkInfo().pkParamIndices());
        }

        @Test void pkColumnsNotFirst() {
            // PK column not at position 1
            var r = t("INSERT INTO \"sessions\" (\"session_data\", \"expires\", \"id\") VALUES (?, ?, ?)");
            assertEquals(SqlPPConnection.PkStrategy.PROVIDED, r.pkInfo().strategy());
            assertArrayEquals(new int[]{3}, r.pkInfo().pkParamIndices()); // id is column 3
        }
    }

    // --- colParamMap ---

    @Nested class ColParamMap {

        @Test void mapsAllColumns() {
            var r = t("INSERT INTO \"sessions\" (\"id\", \"session_data\", \"expires\") VALUES (?, ?, ?)");
            var map = r.pkInfo().colParamMap();
            assertEquals(3, map.size());
            assertEquals(1, map.get("id"));
            assertEquals(2, map.get("session_data"));
            assertEquals(3, map.get("expires"));
        }

        @Test void emptyForNonInsert() {
            var r = t("SELECT * FROM \"sessions\"");
            assertTrue(r.pkInfo().colParamMap().isEmpty());
        }
    }

    // --- All 17 tables ---

    @Test void allTablesRecognized() {
        String[] tables = {
            "delegations", "tokens", "nonces", "sessions", "accounts",
            "credentials", "linked_accounts", "devices", "audit",
            "dynamically_registered_clients", "database_clients",
            "buckets", "database_service_providers", "entities",
            "entity_relations", "account_resource_relations",
            "database_client_resource_relations"
        };
        assertEquals(17, tables.length);
        for (String table : tables) {
            assertTrue(SqlPPConnection.TABLE_META.containsKey(table), "Missing: " + table);
        }
    }
}
