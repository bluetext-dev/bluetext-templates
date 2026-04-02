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

package com.tentixo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.DeviceAttributes;
import se.curity.identityserver.sdk.data.query.ResourceQuery;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.datasource.DeviceDataAccessProvider;

import java.util.List;

public final class CouchbaseDeviceDataAccessProvider implements DeviceDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseDeviceDataAccessProvider.class);

    private final CouchbaseDeviceDataAccessProvider _configuration;

    @SuppressWarnings("unused") // used through DI
    public CouchbaseDeviceDataAccessProvider(CouchbaseDeviceDataAccessProvider configuration) {
        _configuration = configuration;
    }

    @Override
    public DeviceAttributes getBy(String deviceId, String accountId) {
        _logger.debug("Received request to get device by deviceId :{} and accountId: {}", deviceId, accountId);
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceAttributes<?> getBy(String deviceId, String accountId,
                                       ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get device by deviceId :{} and accountId: {}", deviceId, accountId);
        throw new UnsupportedOperationException();
    }

    @Override
    public DeviceAttributes getById(String deviceId) {
        _logger.debug("Received request to get device by deviceId :{}", deviceId);
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceAttributes<?> getById(String deviceId, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get device by deviceId :{}", deviceId);
        throw new UnsupportedOperationException();
    }

    @Override
    public List<DeviceAttributes> getByAccountId(String accountId) {
        _logger.debug("Received request to get devices by accountId :{}", accountId);
        throw new UnsupportedOperationException();
    }

    @Override
    public List<? extends ResourceAttributes<?>> getByAccountId(String accountId, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get devices by accountId :{}", accountId);
        throw new UnsupportedOperationException();
    }

    @Override
    public void create(DeviceAttributes deviceAttributes) {
        _logger.debug("Received request to create device by deviceId :{}", deviceAttributes.getDeviceId());
        throw new UnsupportedOperationException();
    }

    @Override
    public void update(DeviceAttributes deviceAttributes) {
        _logger.debug("Received request to update device by deviceId :{}", deviceAttributes.getDeviceId());
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String id) {
        _logger.debug("Received request to update device by id :{}", id);
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String deviceId, String accountId) {
        _logger.debug("Received request to delete device by deviceId :{} and accountId: {}", deviceId, accountId);
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        _logger.debug("Received request to get all devices with startIndex :{} and count: {}", startIndex, count);
        throw new UnsupportedOperationException();
    }
}
