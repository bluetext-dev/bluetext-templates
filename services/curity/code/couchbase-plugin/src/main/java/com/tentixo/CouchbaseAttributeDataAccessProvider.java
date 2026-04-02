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

import com.tentixo.configuration.CouchbaseDataAccessProviderConfiguration;
import se.curity.identityserver.sdk.attribute.AttributeTableView;
import se.curity.identityserver.sdk.attribute.Attributes;
import se.curity.identityserver.sdk.datasource.AttributeDataAccessProvider;

import static com.tentixo.CouchbaseUserAccountDataAccessProvider.ACCOUNT_COLLECTION_NAME;
import static java.util.Collections.singletonList;

/**
 * Provides data access for retrieving attributes from Couchbase.
 */
public class CouchbaseAttributeDataAccessProvider implements AttributeDataAccessProvider {

    private final CouchbaseExecutor _couchbaseExecutor;
    private final CouchbaseDataAccessProviderConfiguration _configuration;
    private static final String SUBJECT_MARK = ":subject";
    private static final String BUCKET_MARK = ":bucket";
    private static final String SCOPE_MARK = ":scope";
    private static final String COLLECTION_MARK = ":collection";

    public CouchbaseAttributeDataAccessProvider(CouchbaseDataAccessProviderConfiguration configuration,
                                                CouchbaseExecutor couchbaseExecutor) {
        _configuration = configuration;
        _couchbaseExecutor = couchbaseExecutor;
    }

    /**
     * Retrieves attributes for a given subject from Couchbase.
     *
     * @param subject The subject for which attributes are retrieved.
     * @return An AttributeTableView object containing the retrieved attributes.
     */
    @Override
    public AttributeTableView getAttributes(String subject) {
        var query = _configuration.getClaimQuery()
                .replace(BUCKET_MARK, _configuration.getBucket())
                .replace(SCOPE_MARK, _configuration.getScope())
                .replace(COLLECTION_MARK, ACCOUNT_COLLECTION_NAME)
                .replace(SUBJECT_MARK, subject);
        return AttributeTableView.ofAttributes(
                singletonList(Attributes.fromMap(
                        _couchbaseExecutor.executeQueryForSingleResult(query)))
        );
    }
}
