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

package com.tentixo;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.kv.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.data.Session;
import se.curity.identityserver.sdk.datasource.SessionDataAccessProvider;

import java.time.Duration;
import java.time.Instant;

public final class CouchbaseSessionDataAccessProvider implements SessionDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseSessionDataAccessProvider.class);
    public static final String SESSION_COLLECTION_NAME = "curity-sessions";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection collection;

    public CouchbaseSessionDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        this.collection = couchbaseExecutor.getScope().collection(SESSION_COLLECTION_NAME);
    }
    @Override
    public @Nullable Session getSessionById(String id) {
        try {
            return collection.get(id).contentAs(Session.class);
        } catch (DocumentNotFoundException de) {
            _logger.debug("Document not found: " + id);
            return null;
        }
    }

    @Override
    public void insertSession(Session session) {
        collection.insert(session.getId(), session);
    }

    @Override
    public void updateSession(Session session) {
        collection.replace(session.getId(), session);
    }

    @Override
    public void updateSessionExpiration(String id, Instant expiresAt) {
        Instant now = Instant.now();
        Duration duration = Duration.between(now, expiresAt);
        collection.touch(id, duration);
    }

    @Override
    public void deleteSessionState(String id) {
        collection.remove(id);
    }
}
