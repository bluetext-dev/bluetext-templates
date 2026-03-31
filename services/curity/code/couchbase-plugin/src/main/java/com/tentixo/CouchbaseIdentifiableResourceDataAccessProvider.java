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
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.datasource.IdentifiableResourceDataAccessProvider;

/**
 * https://curity.io/docs/idsvr-java-plugin-sdk/latest/se/curity/identityserver/sdk/datasource/IdentifiableResourceDataAccessProvider.html
 */
public final class CouchbaseIdentifiableResourceDataAccessProvider implements IdentifiableResourceDataAccessProvider {
    @Override
    public ResourceAttributes<?> fromAttributes(@Nullable Iterable iterable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceAttributes<?> getById(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        throw new UnsupportedOperationException();
    }
}
