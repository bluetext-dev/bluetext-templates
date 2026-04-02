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
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.kv.MutateInSpec;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.tentixo.CouchbaseExecutor;
import com.tentixo.configuration.CouchbaseDataAccessProviderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.data.authorization.Delegation;
import se.curity.identityserver.sdk.data.authorization.DelegationStatus;
import se.curity.identityserver.sdk.data.query.ResourceQuery;
import se.curity.identityserver.sdk.datasource.DelegationDataAccessProvider;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static com.tentixo.CouchbaseExecutor.QUERY_OPTIONS;

/**
 * https://curity.io/docs/idsvr-java-plugin-sdk/latest/se/curity/identityserver/sdk/datasource/DelegationDataAccessProvider.html
 */
public final class CouchbaseDelegationDataAccessProvider implements DelegationDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseDelegationDataAccessProvider.class);
    public static final String DELEGATION_COLLECTION_NAME = "curity-delegations";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Scope scope;
    public final com.couchbase.client.java.Collection collection;
    private final CouchbaseDataAccessProviderConfiguration _configuration;

    public CouchbaseDelegationDataAccessProvider(CouchbaseDataAccessProviderConfiguration _configuration, CouchbaseExecutor couchbaseExecutor) {
        this._configuration = _configuration;
        this._couchbaseExecutor = couchbaseExecutor;
        this.scope = _couchbaseExecutor.getScope();
        this.collection = scope.collection(DELEGATION_COLLECTION_NAME);
  }

    @Override
    public @Nullable Delegation getById(String id) {
        Delegation delegation;
        try {
            delegation = collection.get(id).contentAs(Delegation.class);
        }  catch (DocumentNotFoundException de) {
            _logger.debug("Document not found: " + id);
            return null;
        }

        // Only valid (i.e. status == issue) delegations are retrieved here
        // to mimic the JDBC DAP behavior.
        var status = delegation.getStatus().name();
        if (!DelegationStatus.issued.name().equals(status)) {
            return null;
        }
        return delegation;
    }

    @Override
    public void create(Delegation delegation) {
        collection.insert(delegation.getId(), delegation);
    }

    @Override
    public long setStatus(String id, DelegationStatus status) {
        try {
            collection.mutateIn(id, List.of(MutateInSpec.replace("status", status)));
            return 1;
        }catch (CouchbaseException ce) {
            _logger.error(ce.getMessage());
            return 0;
        }
    }

    private final String GET_DELEGATION_BY_PARAMETER_QUERY = "SELECT `%s`.* FROM `%s`" +
            " WHERE %s = \"%s\"";

    private final String GET_DELEGATION_BY_PARAMETER_QUERY_PAGINATED = "SELECT `%s`.* FROM `%s`" +
            " WHERE %s = \"%s\" LIMIT %s OFFSET %s";

    private Stream<Delegation> queryDelegationByParam(String paramName, String value, long startIndex, long count) {

        String query;
        if (startIndex > 0 && count > 0) {
            query = String.format(GET_DELEGATION_BY_PARAMETER_QUERY_PAGINATED, collection.name(), collection.name(),
                    paramName, value, count, startIndex);
        } else {
            query = String.format(GET_DELEGATION_BY_PARAMETER_QUERY, collection.name(), collection.name(),
                    paramName, value);
        }

        return queryDelegation(query);
    }

    private Stream<Delegation> queryDelegation(String query) {
        return scope.query(query, QUERY_OPTIONS).rowsAs(Delegation.class).stream();
    }


    private Stream<Delegation> gueryDelegationByParam(String paramName, String value) {
        return queryDelegationByParam(paramName, value, -1, -1);
    }

    @Override
    public @Nullable Delegation getByAuthorizationCodeHash(String authorizationCodeHash) {
        return gueryDelegationByParam("authorizationCodeHash", authorizationCodeHash).findFirst().orElse(null);
    }


    @Override
    public Collection<? extends Delegation> getByOwner(String owner, long startIndex, long count) {
        return queryDelegationByParam("owner", owner, startIndex, count).toList();
    }

    @Override
    public Collection<? extends Delegation> getAllActive(long startIndex, long count) {
        return queryDelegationByParam("status", DelegationStatus.issued.name(), startIndex, count).toList();
    }

    private final String COUNT_DELEGATION_BY_PARAMETER_QUERY = "SELECT COUNT(1) FROM `%s`" +
            " WHERE %s = \"%s\"";

    @Override
    public long getCountAllActive() {
        String query = String.format(COUNT_DELEGATION_BY_PARAMETER_QUERY, collection.name(), "status", DelegationStatus.issued.name());
        return scope.query(query, QUERY_OPTIONS).rowsAs(Long.class).getFirst();
    }

    @Override
    public long getCountByOwner(String owner) {
        String query = String.format(COUNT_DELEGATION_BY_PARAMETER_QUERY, collection.name(), "owner", owner);
        return scope.query(query, QUERY_OPTIONS).rowsAs(Long.class).getFirst();
    }

    @Override
    public Collection<? extends Delegation> getAll(ResourceQuery query) {
        String queryString = String.format("SELECT `%s`.* FROM `%s`", DELEGATION_COLLECTION_NAME, DELEGATION_COLLECTION_NAME);
        return queryDelegation(queryString).toList();

    }
}
