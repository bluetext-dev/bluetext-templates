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
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.DeviceAttributes;
import se.curity.identityserver.sdk.data.query.ResourceQuery;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.datasource.DeviceDataAccessProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.bluetext.curity.couchbase.CouchbaseExecutor.QUERY_OPTIONS;

public final class CouchbaseDeviceDataAccessProvider implements DeviceDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseDeviceDataAccessProvider.class);
    public static final String DEVICE_COLLECTION_NAME = "curity-devices";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection _collection;
    private final Scope _scope;

    public CouchbaseDeviceDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        _collection = couchbaseExecutor.getScope().collection(DEVICE_COLLECTION_NAME);
        _scope = couchbaseExecutor.getScope();
    }

    private static String deviceKey(String deviceId) {
        return "device::" + deviceId;
    }

    @Override
    public @Nullable DeviceAttributes getBy(String deviceId, String accountId) {
        _logger.debug("getBy deviceId={}, accountId={}", deviceId, accountId);
        try {
            Map<String, Object> doc = _collection.get(deviceKey(deviceId)).contentAsObject().toMap();
            if (accountId.equals(doc.get("accountId"))) {
                return (DeviceAttributes) DeviceAttributes.fromMap(doc);
            }
            return null;
        } catch (DocumentNotFoundException e) {
            return null;
        }
    }

    @Override
    public @Nullable ResourceAttributes<?> getBy(String deviceId, String accountId,
                                                  ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("getBy deviceId={}, accountId={} with enumeration", deviceId, accountId);
        DeviceAttributes attrs = getBy(deviceId, accountId);
        if (attrs == null) return null;
        return filterAttributes(attrs.toMap(), attributesEnumeration);
    }

    @Override
    public @Nullable DeviceAttributes getById(String deviceId) {
        _logger.debug("getById deviceId={}", deviceId);
        try {
            Map<String, Object> doc = _collection.get(deviceKey(deviceId)).contentAsObject().toMap();
            return (DeviceAttributes) DeviceAttributes.fromMap(doc);
        } catch (DocumentNotFoundException e) {
            return null;
        }
    }

    @Override
    public @Nullable ResourceAttributes<?> getById(String deviceId,
                                                    ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("getById deviceId={} with enumeration", deviceId);
        DeviceAttributes attrs = getById(deviceId);
        if (attrs == null) return null;
        return filterAttributes(attrs.toMap(), attributesEnumeration);
    }

    @Override
    public List<DeviceAttributes> getByAccountId(String accountId) {
        _logger.debug("getByAccountId accountId={}", accountId);
        var config = _couchbaseExecutor.getConfiguration();
        String query = String.format(
                "SELECT `%s`.* FROM `%s`.%s.`%s` WHERE accountId = $accountId",
                DEVICE_COLLECTION_NAME, config.getBucket(), config.getScope(), DEVICE_COLLECTION_NAME);
        var params = JsonObject.create().put("accountId", accountId);
        List<Map<String, Object>> rows = _couchbaseExecutor.executeParameterizedQuery(query, params);
        List<DeviceAttributes> devices = new ArrayList<>();
        for (var row : rows) {
            devices.add((DeviceAttributes) DeviceAttributes.fromMap(row));
        }
        return devices;
    }

    @Override
    public List<? extends ResourceAttributes<?>> getByAccountId(String accountId,
                                                                 ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("getByAccountId accountId={} with enumeration", accountId);
        return getByAccountId(accountId);
    }

    @Override
    public void create(DeviceAttributes deviceAttributes) {
        _logger.debug("create deviceId={}", deviceAttributes.getDeviceId());
        _collection.insert(deviceKey(deviceAttributes.getDeviceId()), deviceAttributes.toMap());
    }

    @Override
    public void update(DeviceAttributes deviceAttributes) {
        _logger.debug("update deviceId={}", deviceAttributes.getDeviceId());
        _collection.replace(deviceKey(deviceAttributes.getDeviceId()), deviceAttributes.toMap());
    }

    @Override
    public void delete(String id) {
        _logger.debug("delete id={}", id);
        try {
            _collection.remove(deviceKey(id));
        } catch (DocumentNotFoundException e) {
            _logger.debug("Device not found for deletion: {}", id);
        }
    }

    @Override
    public void delete(String deviceId, String accountId) {
        _logger.debug("delete deviceId={}, accountId={}", deviceId, accountId);
        DeviceAttributes device = getBy(deviceId, accountId);
        if (device != null) {
            _collection.remove(deviceKey(deviceId));
        }
    }

    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        _logger.debug("getAll startIndex={}, count={}", startIndex, count);
        var config = _couchbaseExecutor.getConfiguration();
        String query = String.format(
                "SELECT `%s`.* FROM `%s`.%s.`%s` OFFSET %d LIMIT %d",
                DEVICE_COLLECTION_NAME, config.getBucket(), config.getScope(),
                DEVICE_COLLECTION_NAME, startIndex, count);
        List<Map<String, Object>> rows = _couchbaseExecutor.executeQuery(query);
        List<DeviceAttributes> devices = new ArrayList<>();
        for (var row : rows) {
            devices.add((DeviceAttributes) DeviceAttributes.fromMap(row));
        }
        return new ResourceQueryResult(devices, devices.size(), startIndex, count);
    }

    private DeviceAttributes filterAttributes(Map<String, Object> map,
                                              ResourceQuery.AttributesEnumeration enumeration) {
        if (enumeration != null) {
            Set<String> allowed = enumeration.getAttributes();
            map.keySet().retainAll(allowed);
        }
        return (DeviceAttributes) DeviceAttributes.fromMap(map);
    }
}
