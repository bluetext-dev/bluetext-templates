package dev.bluetext.jdbc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * ResultSetMetaData derived from N1QL query results.
 * Column names come from the JSON keys; types are inferred from values.
 */
public class QueryResultSetMetaData implements ResultSetMetaData {

    private final List<String> columnNames;
    private final JsonObject sampleRow; // first row for type inference, may be null

    public QueryResultSetMetaData(List<String> columnNames, JsonObject sampleRow) {
        this.columnNames = columnNames;
        this.sampleRow = sampleRow;
    }

    @Override public int getColumnCount() { return columnNames.size(); }
    @Override public String getColumnName(int column) throws SQLException { return col(column); }
    @Override public String getColumnLabel(int column) throws SQLException { return col(column); }

    @Override
    public int getColumnType(int column) throws SQLException {
        if (sampleRow == null) return Types.VARCHAR;
        JsonElement val = sampleRow.get(col(column));
        if (val == null || val.isJsonNull()) return Types.NULL;
        if (val.isJsonPrimitive()) {
            JsonPrimitive p = val.getAsJsonPrimitive();
            if (p.isBoolean()) return Types.BOOLEAN;
            if (p.isNumber()) {
                String s = p.getAsString();
                return s.contains(".") ? Types.DOUBLE : Types.BIGINT;
            }
            return Types.VARCHAR;
        }
        return Types.VARCHAR;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        int t = getColumnType(column);
        return switch (t) {
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.BIGINT -> "BIGINT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.NULL -> "NULL";
            default -> "VARCHAR";
        };
    }

    @Override public String getColumnClassName(int column) throws SQLException { return "java.lang.String"; }
    @Override public String getSchemaName(int column) { return ""; }
    @Override public String getTableName(int column) { return ""; }
    @Override public String getCatalogName(int column) { return ""; }
    @Override public int getColumnDisplaySize(int column) { return 256; }
    @Override public int getPrecision(int column) { return 0; }
    @Override public int getScale(int column) { return 0; }
    @Override public boolean isAutoIncrement(int column) { return false; }
    @Override public boolean isCaseSensitive(int column) { return true; }
    @Override public boolean isSearchable(int column) { return true; }
    @Override public boolean isCurrency(int column) { return false; }
    @Override public int isNullable(int column) { return columnNullable; }
    @Override public boolean isSigned(int column) { return true; }
    @Override public boolean isReadOnly(int column) { return true; }
    @Override public boolean isWritable(int column) { return false; }
    @Override public boolean isDefinitelyWritable(int column) { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }

    private String col(int index) throws SQLException {
        if (index < 1 || index > columnNames.size()) throw new SQLException("Column index out of range: " + index);
        return columnNames.get(index - 1);
    }
}
