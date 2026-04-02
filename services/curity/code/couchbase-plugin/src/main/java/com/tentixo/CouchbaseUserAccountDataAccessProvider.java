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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.AccountAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.ResourceAttributes;
import se.curity.identityserver.sdk.attribute.scim.v2.extensions.LinkedAccount;
import se.curity.identityserver.sdk.data.query.ResourceQuery;
import se.curity.identityserver.sdk.data.query.ResourceQueryResult;
import se.curity.identityserver.sdk.data.update.AttributeUpdate;
import se.curity.identityserver.sdk.datasource.UserAccountDataAccessProvider;

import java.util.Collection;
import java.util.Map;

/**
 * The CouchbaseUserAccountDataAccessProvider class provides access to user account data stored in Couchbase.
 */
public class CouchbaseUserAccountDataAccessProvider implements UserAccountDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseUserAccountDataAccessProvider.class);
    public static final String ACCOUNT_COLLECTION_NAME = "curity-accounts";
    private final CouchbaseExecutor _couchbaseExecutor;

    public CouchbaseUserAccountDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
    }

    /**
     * Retrieves the resource attributes for an account by username.
     *
     * @param username              The username of the account to retrieve.
     * @param attributesEnumeration The enumeration of attributes to include in the result.
     * @return The resource attributes for the specified username, or null if not found.
     */
    @Override
    public @Nullable ResourceAttributes<?> getByUserName(String username,
                                                         ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get account by username: {}", username);
        return _couchbaseExecutor.getByParameter(Parameters.USERNAME, username, attributesEnumeration);
    }

    /**
     * Retrieves a resource attribute by email.
     *
     * @param email                 The email address of the account.
     * @param attributesEnumeration The enumeration of attributes to include in the response.
     * @return The resource attributes associated with the given email, or null if not found.
     */
    @Override
    public @Nullable ResourceAttributes<?> getByEmail(String email,
                                                      ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get account by email: {}", email);
        // TODO: Rewrite to work with SCIM parameters, since they are multivalued
        // A scim email looks like this:
        // emails: [{value: john@doe.com, primary: true}, {value: johndoe@gmail.com}]
        return _couchbaseExecutor.getByParameter(Parameters.EMAIL, email, attributesEnumeration);
    }

    /**
     * Retrieves the resource attributes of an account based on the provided phone number.
     *
     * @param phone                 The phone number to search for.
     * @param attributesEnumeration The enumeration of attributes to include in the response.
     * @return The resource attributes of the account matching the provided phone number,
     * or null if no account is found.
     */
    @Override
    public @Nullable ResourceAttributes<?> getByPhone(String phone,
                                                      ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received request to get account by phone: {}", phone);
        // TODO: Rewrite to work with SCIM parameters, since they are multivalued
        return _couchbaseExecutor.getByParameter(Parameters.PHONE, phone, attributesEnumeration);
    }

    /**
     * Creates a new account with the provided account attributes.
     *
     * @param accountAttributes The account attributes to be used for creating the account.
     * @return The created account attributes.
     */
    @Override
    public AccountAttributes create(AccountAttributes accountAttributes) {
        _logger.debug("Received request to create a new account");
        return _couchbaseExecutor.create(accountAttributes);
    }

    /**
     * Updates the resource attributes for a given account.
     *
     * @param accountAttributes     The new account attributes to update.
     * @param attributesEnumeration The enumeration specifying which attributes to update.
     * @return The updated resource attributes.
     */
    @Override
    public ResourceAttributes<?> update(AccountAttributes accountAttributes,
                                        ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received update request for username: {}", accountAttributes.getUserName());
        return _couchbaseExecutor.updateByUsername(accountAttributes.getUserName(), accountAttributes.toMap(),
                attributesEnumeration);
    }

    /**
     * Updates a resource with the given account ID, map, and attribute enumeration.
     *
     * @param accountId             The account ID of the resource to be updated.
     * @param map                   The map containing the updated attribute values.
     * @param attributesEnumeration The enumeration specifying which attributes to update.
     * @return The updated resource or null if the update was not successful.
     */
    @Override
    public @Nullable ResourceAttributes<?> update(String accountId, Map<String, Object> map,
                                                  ResourceQuery.AttributesEnumeration attributesEnumeration) {
        _logger.debug("Received update request for accountId: {}", accountId);
        return _couchbaseExecutor.updateByAccountId(accountId, map, attributesEnumeration);
    }

    /**
     * Updates the resource attributes of an account using the PATCH method.
     *
     * @param accountId             the ID of the account to be patched
     * @param attributeUpdate       the object containing the attribute updates
     * @param attributesEnumeration the enumeration of attributes to be included in the patch request
     * @return the updated resource attributes or null if the patch request fails
     */
    @Override
    public @Nullable ResourceAttributes<?> patch(String accountId, AttributeUpdate attributeUpdate,
                                                 ResourceQuery.AttributesEnumeration attributesEnumeration) {

        _logger.debug("Received patch request with accountId: {}", accountId);
        var dataMap = attributeUpdate.getAttributeReplacements().toMap();
        dataMap.putAll(attributeUpdate.getAttributeAdditions().toMap());
        return _couchbaseExecutor.patchByAccountId(accountId, dataMap, attributesEnumeration);
    }

    /**
     * Links the account (not implemented)
     *
     * @param s  the first string to be linked
     * @param s1 the second string to be linked
     * @param s2 the third string to be linked
     * @param s3 the fourth string to be linked
     * @throws IllegalStateException always throws IllegalStateException when called
     */
    @Override
    public void link(String s, String s1, String s2, String s3) {
        throw new IllegalStateException();
    }

    /**
     * Retrieves the collection of linked accounts for the specified parameters (not implemented)
     *
     * @param s  the first parameter
     * @param s1 the second parameter
     * @return the collection of linked accounts
     * @throws IllegalStateException if the method is called (this is an example method, it always throws an exception)
     */
    @Override
    public Collection<LinkedAccount> listLinks(String s, String s1) {
        throw new IllegalStateException();
    }

    /**
     * Resolves a link using the provided parameters (not implemented)
     *
     * @param s  The first parameter of the link resolution.
     * @param s1 The second parameter of the link resolution.
     * @param s2 The third parameter of the link resolution.
     * @return The resolved AccountAttributes object.
     * @throws IllegalStateException Always throws an exception since this method is not implemented.
     */
    @Override
    public @Nullable AccountAttributes resolveLink(String s, String s1, String s2) {
        throw new IllegalStateException();
    }

    /**
     * Deletes a link with the specified parameters (not implemented)
     *
     * @param s  the first parameter of the link
     * @param s1 the second parameter of the link
     * @param s2 the third parameter of the link
     * @param s3 the fourth parameter of the link
     * @return true if the link is deleted successfully, false otherwise
     */
    @Override
    public boolean deleteLink(String s, String s1, String s2, String s3) {
        throw new IllegalStateException();
    }

    /**
     * Deletes an account with the specified account ID.
     *
     * @param accountId the ID of the account to be deleted
     */
    @Override
    public void delete(String accountId) {
        _logger.debug("Received request to delete account with account id: {}", accountId);
        _couchbaseExecutor.delete(accountId);
    }

    /**
     * Retrieves all resources based on the specified start index and count.
     *
     * @param startIndex the index to start retrieving resources from
     * @param count      the number of resources to retrieve
     * @return a {@link ResourceQueryResult} object containing the retrieved resources
     */
    @Override
    public ResourceQueryResult getAll(long startIndex, long count) {
        _logger.debug("Received request to find all pageable with start index: {}, count: {}", startIndex, count);
        return _couchbaseExecutor.findAllPageable(startIndex, count);
    }
}
