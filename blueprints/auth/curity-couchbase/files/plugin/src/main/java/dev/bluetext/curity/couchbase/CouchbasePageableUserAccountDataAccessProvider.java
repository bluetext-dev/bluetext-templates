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

import com.couchbase.client.java.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.AccountAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.LinkedAccount;
import se.curity.identityserver.sdk.data.query.ResourceQuery;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.data.update.AttributeUpdate;
import se.curity.identityserver.sdk.datasource.PageableUserAccountDataAccessProvider;
import se.curity.identityserver.sdk.datasource.pagination.PaginatedDataAccessResult;
import se.curity.identityserver.sdk.datasource.pagination.PaginationRequest;
import se.curity.identityserver.sdk.datasource.query.AttributesFiltering;
import se.curity.identityserver.sdk.datasource.query.AttributesSorting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static dev.bluetext.curity.couchbase.CouchbaseUserAccountDataAccessProvider.ACCOUNT_COLLECTION_NAME;

/**
 * Pageable user account data access provider that delegates base operations to
 * {@link CouchbaseUserAccountDataAccessProvider} and adds pagination/filtering support.
 */
public final class CouchbasePageableUserAccountDataAccessProvider
        implements PageableUserAccountDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(
            CouchbasePageableUserAccountDataAccessProvider.class);
    private final CouchbaseUserAccountDataAccessProvider _delegate;
    private final CouchbaseExecutor _couchbaseExecutor;

    public CouchbasePageableUserAccountDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
        _delegate = new CouchbaseUserAccountDataAccessProvider(couchbaseExecutor);
    }

    // --- Pageable-specific methods ---

    @Override
    public PaginatedDataAccessResult<AccountAttributes> getAllBy(
            boolean activeAccountsOnly,
            @Nullable PaginationRequest paginationRequest,
            @Nullable AttributesSorting sortRequest,
            @Nullable AttributesFiltering filterRequest) {
        _logger.debug("getAllBy activeOnly={}", activeAccountsOnly);
        var config = _couchbaseExecutor.getConfiguration();

        StringBuilder query = new StringBuilder(String.format(
                "SELECT `%s`.* FROM `%s`.%s.`%s` WHERE CONTAINS(META().id, \"node::user::personal_info::\")",
                ACCOUNT_COLLECTION_NAME, config.getBucket(), config.getScope(), ACCOUNT_COLLECTION_NAME));

        if (activeAccountsOnly) {
            query.append(" AND (active IS MISSING OR active = true)");
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

        List<Map<String, Object>> rows = _couchbaseExecutor.executeQuery(query.toString());
        List<AccountAttributes> accounts = new ArrayList<>();
        for (var row : rows) {
            accounts.add(AccountAttributes.fromMap(row));
        }

        String nextCursor = rows.size() < limit ? null : String.valueOf(offset + rows.size());
        return new PaginatedDataAccessResult<>(accounts, nextCursor);
    }

    @Override
    public long getCountBy(boolean activeAccountsOnly, @Nullable AttributesFiltering filterRequest) {
        _logger.debug("getCountBy activeOnly={}", activeAccountsOnly);
        var config = _couchbaseExecutor.getConfiguration();

        StringBuilder query = new StringBuilder(String.format(
                "SELECT COUNT(1) AS cnt FROM `%s`.%s.`%s` WHERE CONTAINS(META().id, \"node::user::personal_info::\")",
                config.getBucket(), config.getScope(), ACCOUNT_COLLECTION_NAME));

        if (activeAccountsOnly) {
            query.append(" AND (active IS MISSING OR active = true)");
        }

        List<Map<String, Object>> rows = _couchbaseExecutor.executeQuery(query.toString());
        if (rows.isEmpty()) return 0;
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number ? ((Number) cnt).longValue() : 0;
    }

    // --- Delegated base methods ---

    @Override
    public @Nullable ResourceAttributes<?> getByUserName(String userName,
                                                          ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.getByUserName(userName, attributesEnumeration);
    }

    @Override
    public @Nullable ResourceAttributes<?> getByEmail(String email,
                                                       ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.getByEmail(email, attributesEnumeration);
    }

    @Override
    public @Nullable ResourceAttributes<?> getByPhone(String phone,
                                                       ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.getByPhone(phone, attributesEnumeration);
    }

    @Override
    public AccountAttributes create(AccountAttributes account) {
        return _delegate.create(account);
    }

    @Override
    public ResourceAttributes<?> update(AccountAttributes account,
                                        ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.update(account, attributesEnumeration);
    }

    @Override
    public @Nullable ResourceAttributes<?> update(String accountId, Map<String, Object> updateMap,
                                                   ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.update(accountId, updateMap, attributesEnumeration);
    }

    @Override
    public @Nullable ResourceAttributes<?> patch(String accountId, AttributeUpdate attributeUpdate,
                                                  ResourceQuery.AttributesEnumeration attributesEnumeration) {
        return _delegate.patch(accountId, attributeUpdate, attributesEnumeration);
    }

    @Override
    public void link(String linkingAccountManager, String localAccountId,
                     String foreignDomainName, String foreignUserName) {
        _delegate.link(linkingAccountManager, localAccountId, foreignDomainName, foreignUserName);
    }

    @Override
    public Collection<LinkedAccount> listLinks(String linkingAccountManager, String localAccountId) {
        return _delegate.listLinks(linkingAccountManager, localAccountId);
    }

    @Override
    public @Nullable AccountAttributes resolveLink(String linkingAccountManager,
                                                    String foreignDomainName, String foreignAccountId) {
        return _delegate.resolveLink(linkingAccountManager, foreignDomainName, foreignAccountId);
    }

    @Override
    public boolean deleteLink(String linkingAccountManager, String localAccountId,
                               String foreignDomainName, String foreignUserName) {
        return _delegate.deleteLink(linkingAccountManager, localAccountId, foreignDomainName, foreignUserName);
    }

    @Override
    public void delete(String accountId) {
        _delegate.delete(accountId);
    }

    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        return _delegate.getAll(startIndex, count);
    }
}
