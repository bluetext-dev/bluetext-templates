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
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.DynamicallyRegisteredClientAttributes;
import se.curity.identityserver.sdk.datasource.DynamicallyRegisteredClientDataAccessProvider;

public class CouchbaseDynamicallyRegisteredClientDataAccessProvider implements DynamicallyRegisteredClientDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseDynamicallyRegisteredClientDataAccessProvider.class);

    private final CouchbaseDynamicallyRegisteredClientDataAccessProvider _configuration;

    @SuppressWarnings("unused") // used through DI
    public CouchbaseDynamicallyRegisteredClientDataAccessProvider(CouchbaseDynamicallyRegisteredClientDataAccessProvider configuration) {
        _configuration = configuration;
    }

    @Override
    public DynamicallyRegisteredClientAttributes getByClientId(String clientId) {
        _logger.debug("Getting dynamic client with id: {}", clientId);
        throw new UnsupportedOperationException();
    }

    @Override
    public void create(DynamicallyRegisteredClientAttributes dynamicallyRegisteredClientAttributes) {
        _logger.debug("Received request to CREATE dynamic client with id : {}",
                dynamicallyRegisteredClientAttributes.getClientId());
        throw new UnsupportedOperationException();
    }

    @Override
    public void update(DynamicallyRegisteredClientAttributes dynamicallyRegisteredClientAttributes) {
        _logger.debug("Received request to UPDATE dynamic client for client : {}",
                dynamicallyRegisteredClientAttributes.getClientId());
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String clientId) {
        _logger.debug("Received request to DELETE dynamic client : {}", clientId);
        throw new UnsupportedOperationException();
    }
}
