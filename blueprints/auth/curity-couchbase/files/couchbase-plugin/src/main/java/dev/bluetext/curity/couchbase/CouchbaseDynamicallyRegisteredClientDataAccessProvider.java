/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.bluetext.curity.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.DynamicallyRegisteredClientAttributes;
import se.curity.identityserver.sdk.datasource.DynamicallyRegisteredClientDataAccessProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CouchbaseDynamicallyRegisteredClientDataAccessProvider
        implements DynamicallyRegisteredClientDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(
            CouchbaseDynamicallyRegisteredClientDataAccessProvider.class);
    public static final String DCR_COLLECTION_NAME = "curity-dynamically-registered-clients";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection _collection;

    public CouchbaseDynamicallyRegisteredClientDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        _collection = couchbaseExecutor.getScope().collection(DCR_COLLECTION_NAME);
    }

    private static String dcrKey(String clientId) {
        return "dcr::" + clientId;
    }

    @Override
    public @Nullable DynamicallyRegisteredClientAttributes getByClientId(String clientId) {
        _logger.debug("getByClientId clientId={}", clientId);
        try {
            Map<String, Object> doc = _collection.get(dcrKey(clientId)).contentAsObject().toMap();
            return DynamicallyRegisteredClientAttributes.fromMap(doc);
        } catch (DocumentNotFoundException e) {
            return null;
        }
    }

    @Override
    public void create(DynamicallyRegisteredClientAttributes attributes) {
        _logger.debug("create clientId={}", attributes.getClientId());
        _collection.insert(dcrKey(attributes.getClientId()), attributes.toMap());
    }

    @Override
    public void update(DynamicallyRegisteredClientAttributes attributes) {
        _logger.debug("update clientId={}", attributes.getClientId());
        _collection.replace(dcrKey(attributes.getClientId()), attributes.toMap());
    }

    @Override
    public void delete(String clientId) {
        _logger.debug("delete clientId={}", clientId);
        try {
            _collection.remove(dcrKey(clientId));
        } catch (DocumentNotFoundException e) {
            _logger.debug("DCR client not found for deletion: {}", clientId);
        }
    }
}
