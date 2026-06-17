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

package dev.bluetext.curity.couchbase.descriptor;

import dev.bluetext.curity.couchbase.*;
import dev.bluetext.curity.couchbase.configuration.CouchbaseDataAccessProviderConfiguration;
import dev.bluetext.curity.couchbase.token.CouchbaseDelegationDataAccessProvider;
import dev.bluetext.curity.couchbase.token.CouchbaseNonceDataAccessProvider;
import dev.bluetext.curity.couchbase.token.CouchbaseTokenDataAccessProvider;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.datasource.*;
import se.curity.identityserver.sdk.plugin.ManagedObject;
import se.curity.identityserver.sdk.plugin.descriptor.DataAccessProviderPluginDescriptor;

import java.util.Optional;

/**
 * Plugin descriptor for the Couchbase data access provider.
 * Registers all provider implementations with the Curity runtime.
 */


/**
 * An entry point for a Couchbase data access provider
 */
public class CouchbaseDataAccessProviderDescriptor
        implements DataAccessProviderPluginDescriptor<CouchbaseDataAccessProviderConfiguration> {
    @Override
    public String getPluginImplementationType() {
        return "couchbase";
    }

    /**
     * Retrieves the data access provider class for attributes in Couchbase.
     *
     * @return The class representing the data access provider for attributes in Couchbase.
     */
    @Override
    public Class<CouchbaseAttributeDataAccessProvider> getAttributeDataAccessProvider() {
        return CouchbaseAttributeDataAccessProvider.class;
    }

    /**
     * Retrieves the configuration type for the Couchbase data access provider.
     *
     * @return The configuration type for the Couchbase data access provider.
     */
    @Override
    public Class<CouchbaseDataAccessProviderConfiguration> getConfigurationType() {
        return CouchbaseDataAccessProviderConfiguration.class;
    }

    @Override
    public Class<? extends CredentialDataAccessProviderFactory> getCredentialDataAccessProviderFactory() {
        return CouchbaseCredentialDataAccessProviderFactory.class;
    }

    @Override
    public Class<CouchbasePageableUserAccountDataAccessProvider> getUserAccountDataAccessProvider() {
        return CouchbasePageableUserAccountDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends NonceDataAccessProvider> getNonceDataAccessProvider() {
        return CouchbaseNonceDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends SessionDataAccessProvider> getSessionDataAccessProvider() {
        return CouchbaseSessionDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends DelegationDataAccessProvider> getDelegationDataAccessProvider() {
        return CouchbaseDelegationDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends TokenDataAccessProvider> getTokenDataAccessProvider() {
        return CouchbaseTokenDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends BucketDataAccessProvider> getBucketDataAccessProvider() {
        return CouchbaseBucketDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends DeviceDataAccessProvider> getDeviceDataAccessProvider() {
        return CouchbaseDeviceDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends DynamicallyRegisteredClientDataAccessProvider> getDynamicallyRegisteredClientDataAccessProvider() {
        return CouchbaseDynamicallyRegisteredClientDataAccessProvider.class;
    }

    @Override
    public @Nullable Class<? extends DatabaseClientDataAccessProvider> getDatabaseClientDataAccessProvider() {
        return CouchbaseDatabaseClientDataAccessProvider.class;
    }

    /**
     * Creates a new managed object for the Couchbase executor based on the provided configuration.
     *
     * @param configuration The configuration object containing the settings for the Couchbase data access provider.
     * @return An optional managed object of type ManagedObject<CouchbaseDataAccessProviderConfiguration>.
     */
    @Override
    public Optional<? extends ManagedObject<CouchbaseDataAccessProviderConfiguration>> createManagedObject(
            CouchbaseDataAccessProviderConfiguration configuration) {
        return Optional.of(new CouchbaseExecutor(configuration));
    }
}
