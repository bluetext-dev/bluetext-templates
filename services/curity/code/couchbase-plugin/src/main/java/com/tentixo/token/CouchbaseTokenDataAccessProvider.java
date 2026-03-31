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

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.MutateInSpec;
import com.tentixo.CouchbaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.data.authorization.Token;
import se.curity.identityserver.sdk.data.authorization.TokenStatus;
import se.curity.identityserver.sdk.datasource.TokenDataAccessProvider;

import java.time.Instant;
import java.util.List;

public final class CouchbaseTokenDataAccessProvider implements TokenDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseTokenDataAccessProvider.class);
    public static final String TOKEN_COLLECTION_NAME = "curity-tokens";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection collection;

    public CouchbaseTokenDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        this.collection = couchbaseExecutor.getScope().collection(TOKEN_COLLECTION_NAME);
    }

    @Override
    public @Nullable Token getByHash(String tokenHash) {
        try {
            return collection.get(tokenHash).contentAs(Token.class);
        } catch (DocumentNotFoundException de) {
            _logger.debug("Document not found: " + tokenHash);
            return null;
        }
    }

    @Override
    public void create(Token token) {
        long expiration = token.getExpires();
        Instant expInstant = Instant.ofEpochSecond(expiration);
        collection.insert(token.getTokenHash(), token, InsertOptions.insertOptions().expiry(expInstant));
    }

    @Override
    public @Nullable String getStatus(String tokenHash) {
        Token token;
        try {
            token = collection.get(tokenHash).contentAs(Token.class);
        } catch (DocumentNotFoundException de) {
            _logger.debug("Document not found: " + tokenHash);
            return null;
        }
        return token.getStatus().toString();
    }

    @Override
    public long setStatusByTokenHash(String tokenHash, TokenStatus newStatus) {
        try {
            collection.mutateIn(tokenHash, List.of(MutateInSpec.replace("token", newStatus.name())));
            return 1;
        } catch (CouchbaseException ce) {
            _logger.error(ce.getMessage());
            return 0;
        }
    }

    @Override
    public long setStatus(String tokenId, TokenStatus newStatus) {
        // TODO: This is a deprecated method and should not be used.
        throw new UnsupportedOperationException();
    }

}
