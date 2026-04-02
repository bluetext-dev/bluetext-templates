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

package dev.bluetext.curity.couchbase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.attribute.*;
import se.curity.identityserver.sdk.datasource.CredentialStoringDataAccessProvider;

/**
 * Couchbase credential data access provider — SDK 11.x CredentialStoringDataAccessProvider.
 * Curity handles password hashing; this provider stores and retrieves the hashed password.
 */
public class CouchbaseCredentialDataAccessProvider implements CredentialStoringDataAccessProvider {

    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseCredentialDataAccessProvider.class);
    private final CouchbaseExecutor _couchbaseExecutor;

    public CouchbaseCredentialDataAccessProvider(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
    }

    @Override
    public GetResult get(SubjectAttributes subjectAttributes) {
        var username = subjectAttributes.getSubject();
        _logger.debug("get credential for username: {}", username);
        Attributes accountAttributes = _couchbaseExecutor.getByParameter(Parameters.USERNAME, username, null);
        if (accountAttributes == null) {
            return new GetResult(subjectAttributes, "");
        }
        var password = accountAttributes.get(AccountAttributes.PASSWORD);
        return new GetResult(subjectAttributes, password != null ? password.getValueOfType(String.class) : "");
    }

    @Override
    public void store(SubjectAttributes subjectAttributes, String hashedPassword) {
        var username = subjectAttributes.getSubject();
        _logger.debug("store credential for username: {}", username);
        _couchbaseExecutor.updatePassword(username, hashedPassword);
    }

    @Override
    public boolean delete(SubjectAttributes subjectAttributes) {
        _logger.debug("delete credential for username: {}", subjectAttributes.getSubject());
        try {
            _couchbaseExecutor.delete(subjectAttributes.getSubject());
            return true;
        } catch (Exception e) {
            _logger.error("Failed to delete credential for username: {}", subjectAttributes.getSubject(), e);
            return false;
        }
    }
}
