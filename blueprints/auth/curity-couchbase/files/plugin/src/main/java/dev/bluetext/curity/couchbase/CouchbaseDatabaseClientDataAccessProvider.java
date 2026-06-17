/*
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.bluetext.curity.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.client.database.DatabaseClientAttributes;
import se.curity.identityserver.sdk.datasource.DatabaseClientDataAccessProvider;
import se.curity.identityserver.sdk.datasource.pagination.PaginatedDataAccessResult;
import se.curity.identityserver.sdk.datasource.pagination.PaginationRequest;
import se.curity.identityserver.sdk.datasource.query.DatabaseClientAttributesFiltering;
import se.curity.identityserver.sdk.datasource.query.DatabaseClientAttributesSorting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CouchbaseDatabaseClientDataAccessProvider implements DatabaseClientDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseDatabaseClientDataAccessProvider.class);
    public static final String DB_CLIENT_COLLECTION_NAME = "curity-database-clients";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection _collection;

    public CouchbaseDatabaseClientDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        _collection = couchbaseExecutor.getScope().collection(DB_CLIENT_COLLECTION_NAME);
    }

    private static String clientKey(String profileId, String clientId) {
        return "dbclient::" + profileId + "::" + clientId;
    }

    @Override
    public @Nullable DatabaseClientAttributes getClientById(String clientId, String profileId) {
        _logger.debug("getClientById clientId={}, profileId={}", clientId, profileId);
        try {
            Map<String, Object> doc = _collection.get(clientKey(profileId, clientId)).contentAsObject().toMap();
            return (DatabaseClientAttributes) DatabaseClientAttributes.fromMap(doc);
        } catch (DocumentNotFoundException e) {
            return null;
        }
    }

    @Override
    public DatabaseClientAttributes create(DatabaseClientAttributes attributes, String profileId) {
        _logger.debug("create clientId={}, profileId={}", attributes.getClientId(), profileId);
        Map<String, Object> data = attributes.toMap();
        data.put("profileId", profileId);
        _collection.insert(clientKey(profileId, attributes.getClientId()), data);
        return attributes;
    }

    @Override
    public @Nullable DatabaseClientAttributes update(DatabaseClientAttributes attributes, String profileId) {
        _logger.debug("update clientId={}, profileId={}", attributes.getClientId(), profileId);
        Map<String, Object> data = attributes.toMap();
        data.put("profileId", profileId);
        try {
            _collection.replace(clientKey(profileId, attributes.getClientId()), data);
            return attributes;
        } catch (DocumentNotFoundException e) {
            return null;
        }
    }

    @Override
    public boolean delete(String clientId, String profileId) {
        _logger.debug("delete clientId={}, profileId={}", clientId, profileId);
        try {
            _collection.remove(clientKey(profileId, clientId));
            return true;
        } catch (DocumentNotFoundException e) {
            return false;
        }
    }

    @Override
    public PaginatedDataAccessResult<DatabaseClientAttributes> getAllClientsBy(
            String profileId,
            @Nullable DatabaseClientAttributesFiltering filters,
            @Nullable PaginationRequest paginationRequest,
            @Nullable DatabaseClientAttributesSorting sortRequest,
            boolean activeClientsOnly) {
        _logger.debug("getAllClientsBy profileId={}, activeOnly={}", profileId, activeClientsOnly);
        var config = _couchbaseExecutor.getConfiguration();

        StringBuilder query = new StringBuilder(String.format(
                "SELECT `%s`.* FROM `%s`.%s.`%s` WHERE profileId = $profileId",
                DB_CLIENT_COLLECTION_NAME, config.getBucket(), config.getScope(), DB_CLIENT_COLLECTION_NAME));
        JsonObject params = JsonObject.create().put("profileId", profileId);

        if (activeClientsOnly) {
            query.append(" AND (status IS MISSING OR status = \"active\")");
        }

        long limit = 50;
        long offset = 0;
        if (paginationRequest != null) {
            limit = paginationRequest.getCount();
            String cursor = paginationRequest.getCursor();
            if (cursor != null && !cursor.isEmpty()) {
                try { offset = Long.parseLong(cursor); } catch (NumberFormatException ignored) {}
            }
        }
        query.append(String.format(" LIMIT %d OFFSET %d", limit, offset));

        List<Map<String, Object>> rows = _couchbaseExecutor.executeParameterizedQuery(query.toString(), params);
        List<DatabaseClientAttributes> clients = new ArrayList<>();
        for (var row : rows) {
            clients.add((DatabaseClientAttributes) DatabaseClientAttributes.fromMap(row));
        }

        String nextCursor = rows.size() < limit ? null : String.valueOf(offset + rows.size());
        return new PaginatedDataAccessResult<>(clients, nextCursor);
    }

    @Override
    public long getClientCountBy(String profileId,
                                  @Nullable DatabaseClientAttributesFiltering filters,
                                  boolean activeClientsOnly) {
        _logger.debug("getClientCountBy profileId={}, activeOnly={}", profileId, activeClientsOnly);
        var config = _couchbaseExecutor.getConfiguration();

        StringBuilder query = new StringBuilder(String.format(
                "SELECT COUNT(1) AS cnt FROM `%s`.%s.`%s` WHERE profileId = $profileId",
                config.getBucket(), config.getScope(), DB_CLIENT_COLLECTION_NAME));
        JsonObject params = JsonObject.create().put("profileId", profileId);

        if (activeClientsOnly) {
            query.append(" AND (status IS MISSING OR status = \"active\")");
        }

        List<Map<String, Object>> rows = _couchbaseExecutor.executeParameterizedQuery(query.toString(), params);
        if (rows.isEmpty()) return 0;
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number ? ((Number) cnt).longValue() : 0;
    }
}
