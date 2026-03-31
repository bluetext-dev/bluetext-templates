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

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.*;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.tentixo.configuration.CouchbaseDataAccessProviderConfiguration;
import com.tentixo.configuration.DBSetupRunners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.AccountAttributes;
import se.curity.identityserver.sdk.data.query.ResourceQuery.AttributesEnumeration;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.plugin.ManagedObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tentixo.CouchbaseUserAccountDataAccessProvider.ACCOUNT_COLLECTION_NAME;
import static java.util.Optional.ofNullable;

public class CouchbaseExecutor extends ManagedObject<CouchbaseDataAccessProviderConfiguration> {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseExecutor.class);
    private CouchbaseDataAccessProviderConfiguration configuration;

    private Cluster cluster;

    private Bucket bucket;

    private Scope scope;

    private Collection collection;

    private final String GET_BY_PARAMETER_QUERY = "SELECT `%s`.* FROM `%s`.%s.`%s`" +
                                                  " WHERE %s = \"%s\" AND CONTAINS(META().id, \"node::user::personal_info::\")";
    private final String UPDATE_PASSWORD_QUERY = "UPDATE `%s`.%s.`%s` SET `password` = \"%s\"" +
                                                 " WHERE META().id = \"node::user::personal_info::%s\"";
    private final String FIND_ALL_PAGEABLE_QUERY = "SELECT `%s`.* FROM `%s`.%s.`%s`" +
                                                   " WHERE CONTAINS(META().id,\"node::user::personal_info::\") OFFSET %s LIMIT %s";
    private final String FIND_ALL_QUERY = "SELECT `%s`.* FROM `%s`.%s.`%s`" +
                                          " WHERE META().id = \"node::user::personal_info::%s\"";

    public static final QueryOptions QUERY_OPTIONS = QueryOptions.queryOptions().scanConsistency(QueryScanConsistency.REQUEST_PLUS);

    public CouchbaseExecutor(CouchbaseDataAccessProviderConfiguration configuration) {
        super(configuration);
        init(configuration);
    }

    /**
     * Initializes the CouchbaseDataAccessProvider instance with the provided configuration.
     *
     * @param configuration The configuration object containing the necessary information for initialization.
     */
    private void init(CouchbaseDataAccessProviderConfiguration configuration) {
        try {
            this.configuration = configuration;
            var bucketHost = configuration.getHost();
            var connectionString = configuration.useTls() ? "couchbases://" + bucketHost : "couchbase://" + bucketHost;
            var username = configuration.getUserName();
            var password = configuration.getPassword();

            ClusterOptions options = ClusterOptions.clusterOptions(username, password)
                    .environment(env -> env
                           .jsonSerializer(CurityJsonSerializer.create())

                    );
            this.cluster = Cluster.connect(connectionString,options);


            this.bucket = cluster.bucket(configuration.getBucket());
            if (bucket == null) {
                throw new RuntimeException("Given bucket does not exist: " + configuration.getBucket());
            }
            this.scope = bucket.scope(configuration.getScope());
            if (this.scope == null) {
                bucket.collections().createScope(configuration.getScope());
                this.scope = bucket.scope(configuration.getScope());
            }
            DBSetupRunners setupRunners = new DBSetupRunners();
            setupRunners.run(cluster, bucket, scope);
            this.collection = scope.collection(ACCOUNT_COLLECTION_NAME);



        } catch (CouchbaseException e) {
            _logger.error("Init error! {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

        /**
     * Closes the connection to the cluster.
     */
    @Override
    public void close() {
        bucket = null;
        scope = null;
        collection = null;
        cluster.close();
        cluster = null;
    }

    /**
     * Executes a query in the cluster and returns the result as a list of maps.
     * Each map represents a row in the result.
     *
     * @param query the query to execute
     * @return a list of maps representing the rows in the result
     */
    public List<Map<String, Object>> executeQuery(String query) {
        final var result = cluster.query(query, QUERY_OPTIONS);
        return result.rowsAsObject()
                .stream()
                .map(JsonObject::toMap)
                .toList();
    }

    /**
     * Executes the specified query and returns the first result as a {@code Map<String, Object>}.
     *
     * @param query the SQL query to be executed
     * @return a {@code Map<String, Object>} representing the first result of the query,
     * or an empty {@code Map<String, Object>} if no result is found
     */
    public Map<String, Object> executeQueryForSingleResult(String query) {
        return executeQuery(query).stream()
                .findFirst()
                .orElse(new HashMap<>());
    }

    /**
     * Retrieves an AccountAttributes object based on the specified parameter and value.
     *
     * @param parameter             The parameter to filter the search by.
     * @param value                 The value to search for.
     * @param attributesEnumeration The enumeration of attributes to include in the result.
     * @return The AccountAttributes object that matches the specified parameter and value, or null if no match is found.
     */
    @Nullable
    public AccountAttributes getByParameter(Parameters parameter, String value,
                                            AttributesEnumeration attributesEnumeration) {
        var result = executeQueryForSingleResult(
                String.format(GET_BY_PARAMETER_QUERY, ACCOUNT_COLLECTION_NAME, configuration.getBucket(),
                        configuration.getScope(), ACCOUNT_COLLECTION_NAME, getParameterName(parameter), value));
        ofNullable(attributesEnumeration)
                .ifPresent(enumeration -> {
                    Set<String> attributesToReturn = enumeration.getAttributes();
                    result.keySet().retainAll(attributesToReturn);
                });

        if (result.isEmpty()) {
            return null;
        }

        return wrapIntoAttributes(result);
    }

    /**
     * Retrieves the parameter name based on the configuration settings.
     * If the configuration has set to use SCIM parameter names, it will return the SCIM name of the parameter.
     * Otherwise, it will return the regular name of the parameter.
     *
     * @param parameter The parameter for which to retrieve the name.
     * @return The name of the parameter based on the configuration settings.
     */
    private String getParameterName(Parameters parameter) {
        if (this.configuration.getUseScimParameterNames()) {
            return parameter.getScimName();
        }
        return parameter.getName();
    }

    /**
     * Updates the password for a given username.
     *
     * @param username the username for which to update the password
     * @param password the new password
     */
    public void updatePassword(String username, String password) {
        executeQuery(String.format(UPDATE_PASSWORD_QUERY, configuration.getBucket(), configuration.getScope(),
                ACCOUNT_COLLECTION_NAME, password, username));
    }

    /**
     * Creates a new AccountAttributes with the given accountAttributes data.
     *
     * @param accountAttributes The AccountAttributes object containing the data for the new account.
     * @return The created AccountAttributes object.
     */
    public AccountAttributes create(AccountAttributes accountAttributes) {
        this.collection.upsert("node::user::personal_info::" + accountAttributes.getUserName(), accountAttributes.toMap());
        var createdEntity =
                this.collection.get("node::user::personal_info::" + accountAttributes.getUserName())
                        .contentAsObject().toMap();
        return AccountAttributes.fromMap(createdEntity);
    }

    /**
     * Deletes the entry corresponding to the given account ID from the collection.
     *
     * @param accountId the ID of the account to be deleted
     */
    public void delete(String accountId) {
        this.collection.remove("node::user::personal_info::" + accountId);
    }

    /**
     * Retrieves a pageable list of resources from the database.
     *
     * @param offset The starting index of the resources to retrieve.
     * @param limit  The maximum number of resources to retrieve.
     * @return The result of the resource query, including the list of resources,
     * the total number of resources, the offset, and the limit.
     */
    public ResourceQueryResult findAllPageable(long offset, long limit) {
        var rawResult = executeQuery(
                String.format(FIND_ALL_PAGEABLE_QUERY, ACCOUNT_COLLECTION_NAME, configuration.getBucket(),
                        configuration.getScope(), ACCOUNT_COLLECTION_NAME, offset, limit));
        var accountAttributes =
                rawResult
                        .stream()
                        .map(this::wrapIntoAttributes)
                        .toList();
        return new ResourceQueryResult(accountAttributes, accountAttributes.size(), offset, limit);
    }

    /**
     * Updates the account attributes for a given username.
     *
     * @param username              the username of the account to be updated
     * @param dataToUpdate          a map containing the attributes and their updated values
     * @param attributesEnumeration the enumeration specifying the attributes to be updated
     * @return the updated AccountAttributes object
     */
    public AccountAttributes updateByUsername(String username, Map<String, Object> dataToUpdate,
                                              AttributesEnumeration attributesEnumeration) {
        return update(username, dataToUpdate, attributesEnumeration);
    }

    /**
     * Updates the account attributes by accountId.
     *
     * @param accountId             the accountId of the account to be updated
     * @param dataToUpdate          a map containing the data to be updated
     * @param attributesEnumeration an enumeration specifying the attributes to be updated
     * @return the updated AccountAttributes object
     */
    public AccountAttributes updateByAccountId(String accountId, Map<String, Object> dataToUpdate,
                                               AttributesEnumeration attributesEnumeration) {
        return update(accountId, dataToUpdate, attributesEnumeration);
    }

    /**
     * Updates the account attributes by account ID with the given data to patch and attributes enumeration.
     *
     * @param accountId             The ID of the account to patch.
     * @param dataToPatch           The map containing the data to patch.
     * @param attributesEnumeration The enumeration of attributes to patch.
     * @return The updated AccountAttributes object.
     */
    public AccountAttributes patchByAccountId(String accountId, Map<String, Object> dataToPatch,
                                              AttributesEnumeration attributesEnumeration) {
        return update(accountId, dataToPatch, attributesEnumeration);
    }

    /**
     * Updates the account attributes for a specific user.
     *
     * @param username              The username of the user.
     * @param dataToUpdate          The map containing the attributes to update.
     * @param attributesEnumeration The enumeration of attributes to return.
     * @return The updated account attributes.
     */
    private AccountAttributes update(String username, Map<String, Object> dataToUpdate,
                                     AttributesEnumeration attributesEnumeration) {
        var entityToUpdate =
                executeQueryForSingleResult(
                        String.format(FIND_ALL_QUERY, ACCOUNT_COLLECTION_NAME, configuration.getBucket(),
                                configuration.getScope(), ACCOUNT_COLLECTION_NAME, username));
        entityToUpdate.entrySet()
                .forEach(entry -> {
                    if (dataToUpdate.containsKey(entry.getKey())) {
                        entry.setValue(dataToUpdate.get(entry.getKey()));
                    }
                });
        this.collection.replace("node::user::personal_info::" + username, entityToUpdate);
        var attributesToReturn = attributesEnumeration.getAttributes();
        var updatedEntity = executeQueryForSingleResult(
                String.format(FIND_ALL_QUERY, ACCOUNT_COLLECTION_NAME, configuration.getBucket(),
                        configuration.getScope(), ACCOUNT_COLLECTION_NAME, username));
        updatedEntity.keySet().retainAll(attributesToReturn);
        return AccountAttributes.fromMap(updatedEntity);
    }

    /**
     * Wraps the given map into an AccountAttributes object.
     *
     * @param map The map containing the attribute values.
     * @return The AccountAttributes object representing the map.
     */
    private AccountAttributes wrapIntoAttributes(Map<String, Object> map) {
        return AccountAttributes.fromMap(map);
    }

    public Collection getCollection() {
        return collection;
    }
    public Scope getScope() {
        return scope;
    }

}
