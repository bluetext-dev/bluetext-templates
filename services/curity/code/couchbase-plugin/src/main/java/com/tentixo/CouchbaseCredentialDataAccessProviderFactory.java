package com.tentixo;

import se.curity.identityserver.sdk.datasource.CredentialDataAccessProviderFactory;
import se.curity.identityserver.sdk.datasource.CredentialManagementDataAccessProvider;

public class CouchbaseCredentialDataAccessProviderFactory implements CredentialDataAccessProviderFactory {

    private final CouchbaseExecutor _couchbaseExecutor;

    public CouchbaseCredentialDataAccessProviderFactory(CouchbaseExecutor couchbaseExecutor) {
        _couchbaseExecutor = couchbaseExecutor;
    }

    @Override
    public CredentialManagementDataAccessProvider create() {
        return new CouchbaseCredentialDataAccessProvider(_couchbaseExecutor);
    }
}
