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

package com.tentixo.token;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.MutateInSpec;
import com.tentixo.CouchbaseExecutor;
import com.tentixo.configuration.CouchbaseDataAccessProviderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.datasource.NonceDataAccessProvider;

import java.time.Instant;
import java.util.List;

/**
 * https://curity.io/docs/idsvr-java-plugin-sdk/latest/se/curity/identityserver/sdk/datasource/NonceDataAccessProvider.html
 */
public final class CouchbaseNonceDataAccessProvider implements NonceDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseNonceDataAccessProvider.class);
    public static final String NONCE_COLLECTION_NAME = "curity-nonces";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection collection;
    private final CouchbaseDataAccessProviderConfiguration _configuration;

    public CouchbaseNonceDataAccessProvider(CouchbaseDataAccessProviderConfiguration _configuration, CouchbaseExecutor couchbaseExecutor) {
        this._configuration = _configuration;
        this._couchbaseExecutor = couchbaseExecutor;
        this.collection = couchbaseExecutor.getScope().collection(NONCE_COLLECTION_NAME);
    }
    @Override
    public @Nullable String get(String nonce) {
        Nonce nonceObject;
        try {
            nonceObject = collection.get(nonce).contentAs(Nonce.class);
        } catch ( DocumentNotFoundException de) {
            _logger.debug("Document not found: " + nonce);
            return null;
        }
        if (!NonceStatus.issued.name().equals(nonceObject.getNonceStatus())) {
            return null;
        }

        var createdAt = nonceObject.getCreatedAt();
        var ttl = nonceObject.getNonceTtl();
        var now = Instant.now().getEpochSecond();

        _logger.trace("Nonce createdAt: {}, ttl: {}, now: {}", createdAt, ttl, now);


        if (createdAt + ttl <= now) {
            expireNonce(nonce);
            return null;
        }
        return nonceObject.getNonceValue();
    }

    @Override
    public void save(String nonce, String value, long createdAt, long ttl) {
        Nonce nonceObject = new Nonce();
        nonceObject.setNonce(nonce);
        nonceObject.setNonceValue(value);
        nonceObject.setCreatedAt(createdAt);
        nonceObject.setNonceTtl(ttl);
        nonceObject.setNonceStatus(NonceStatus.issued.name());
        nonceObject.setDeleteableAt(createdAt + ttl + _configuration.getNoncesTtlRetainDuration());
        collection.insert(nonce, nonceObject, InsertOptions.insertOptions().expiry(Instant.ofEpochSecond(nonceObject.getDeleteableAt())));
    }

    @Override
    public void consume(String nonce, long consumedAt) {
        consumeNonce(nonce, consumedAt);
    }

    private void expireNonce(String nonce) {
        changeStatus(nonce, NonceStatus.expired, null);
    }

    private void consumeNonce(String nonce, Long consumedAt) {
        changeStatus(nonce, NonceStatus.consumed, consumedAt);
    }

    // This method doesn't change the deletableAt attribute, if already present
    // - The time-to-live of a nonce is immutable (i.e. not extendable), so the deletableAt is never increased.
    // - Eventually we could reduce the deletableAt when the nonce is consumed,
    // making it `deletableAt = consumedAt + retainDuration`. However we opted out for not doing it since there is
    // no clear advantage and introduces more complexity.
    // Also, if the deletableAt was enabled when the nonce was created and disabled when the nonce is updated,
    // then the original deletableAt is kept.
    // We also don't add a deletableAt when the nonce is updated.
    private void changeStatus(String nonce, NonceStatus status, Long maybeConsumedAt) {
        if (status == NonceStatus.consumed) {
            if (maybeConsumedAt == null) {
                throw new IllegalArgumentException("consumedAt cannot be null");
            }
            collection.mutateIn(nonce, List.of(
                    MutateInSpec.replace("nonceStatus", status.name()),
                    MutateInSpec.replace("consumedAt", maybeConsumedAt)
            ));
        } else {
            collection.mutateIn(nonce, List.of(
                    MutateInSpec.replace("nonceStatus", status.name())
            ));
        }
    }
}
