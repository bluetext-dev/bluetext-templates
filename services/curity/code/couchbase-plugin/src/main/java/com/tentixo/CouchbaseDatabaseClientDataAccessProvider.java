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
import se.curity.identityserver.sdk.attribute.client.database.DatabaseClientAttributes;
import se.curity.identityserver.sdk.datasource.DatabaseClientDataAccessProvider;
import se.curity.identityserver.sdk.datasource.pagination.PaginatedDataAccessResult;
import se.curity.identityserver.sdk.datasource.pagination.PaginationRequest;
import se.curity.identityserver.sdk.datasource.query.DatabaseClientAttributesFiltering;
import se.curity.identityserver.sdk.datasource.query.DatabaseClientAttributesSorting;

/**
 * https://curity.io/docs/idsvr-java-plugin-sdk/latest/se/curity/identityserver/sdk/datasource/DatabaseClientDataAccessProvider.html
 */
public final class CouchbaseDatabaseClientDataAccessProvider implements DatabaseClientDataAccessProvider {
    @Override
    public @Nullable DatabaseClientAttributes getClientById(String clientId, String profileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DatabaseClientAttributes create(DatabaseClientAttributes attributes, String profileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable DatabaseClientAttributes update(DatabaseClientAttributes attributes, String profileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete(String clientId, String profileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PaginatedDataAccessResult<DatabaseClientAttributes> getAllClientsBy(String profileId, @Nullable DatabaseClientAttributesFiltering filters, @Nullable PaginationRequest paginationRequest, @Nullable DatabaseClientAttributesSorting sortRequest, boolean activeClientsOnly) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getClientCountBy(String profileId, @Nullable DatabaseClientAttributesFiltering filters, boolean activeClientsOnly) {
        throw new UnsupportedOperationException();
    }
}
