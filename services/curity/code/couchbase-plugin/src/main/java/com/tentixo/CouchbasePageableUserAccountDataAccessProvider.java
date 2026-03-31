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

import java.util.Collection;
import java.util.Map;

/**
 * https://curity.io/docs/idsvr-java-plugin-sdk/latest/se/curity/identityserver/sdk/datasource/PageableUserAccountDataAccessProvider.html
 */
public final class CouchbasePageableUserAccountDataAccessProvider implements PageableUserAccountDataAccessProvider {
    @Override
    public PaginatedDataAccessResult<AccountAttributes> getAllBy(boolean activeAccountsOnly, @Nullable PaginationRequest paginationRequest, @Nullable AttributesSorting sortRequest, @Nullable AttributesFiltering filterRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCountBy(boolean activeAccountsOnly, @Nullable AttributesFiltering filterRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable ResourceAttributes<?> getByUserName(String userName, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable ResourceAttributes<?> getByEmail(String email, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable ResourceAttributes<?> getByPhone(String phone, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AccountAttributes create(AccountAttributes account) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceAttributes<?> update(AccountAttributes account, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable ResourceAttributes<?> update(String accountId, Map<String, Object> updateMap, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable ResourceAttributes<?> patch(String accountId, AttributeUpdate attributeUpdate, ResourceQuery.AttributesEnumeration attributesEnumeration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void link(String linkingAccountManager, String localAccountId, String foreignDomainName, String foreignUserName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<LinkedAccount> listLinks(String linkingAccountManager, String localAccountId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable AccountAttributes resolveLink(String linkingAccountManager, String foreignDomainName, String foreignAccountId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteLink(String linkingAccountManager, String localAccountId, String foreignDomainName, String foreignAccountId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String accountId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        throw new UnsupportedOperationException();
    }
}
