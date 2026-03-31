package com.tentixo.token;

import com.fasterxml.jackson.annotation.JsonIgnore;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.attribute.AuthenticationAttributes;
import se.curity.identityserver.sdk.data.authorization.*;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class DelegationAdapter implements Delegation {
    private String version;
    private String id;
    private DelegationStatus status;
    private String owner;
    private long created;
    private long expires;
    private String clientId;
    private String redirectUri;
    private String authorizationCodeHash;
    private AuthenticationAttributes authenticationAttributes;
    private DelegationConsentResult consentResult;
    private String scope;
    private Map<String, Object> claimMap;
    private Map<String, Object> customClaimValues;
    private Map<String, Object> claims;
    private String mtlsClientCertificate;
    private String mtlsClientCertificateX5TS256;
    private String mtlsClientCertificateDN;

    public DelegationAdapter() {

    }

    public DelegationAdapter(String version, String id, DelegationStatus status, String owner, long created, long expires,
                              String clientId, String redirectUri, String authorizationCodeHash,
                              AuthenticationAttributes authenticationAttributes, DelegationConsentResult consentResult,
                              String scope, Map<String, Object> claimMap, Map<String, Object> customClaimValues,
                              Map<String, Object> claims, String mtlsClientCertificate,
                              String mtlsClientCertificateX5TS256, String mtlsClientCertificateDN) {
        this.version = version;
        this.id = id;
        this.status = status;
        this.owner = owner;
        this.created = created;
        this.expires = expires;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.authorizationCodeHash = authorizationCodeHash;
        this.authenticationAttributes = authenticationAttributes;
        this.consentResult = consentResult;
        this.scope = scope;
        this.claimMap = claimMap;
        this.customClaimValues = customClaimValues;
        this.claims = claims;
        this.mtlsClientCertificate = mtlsClientCertificate;
        this.mtlsClientCertificateX5TS256 = mtlsClientCertificateX5TS256;
        this.mtlsClientCertificateDN = mtlsClientCertificateDN;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public DelegationStatus getStatus() {
        return status;
    }

    @Override
    public String getOwner() {
        return owner;
    }

    @Override
    public long getCreated() {
        return created;
    }

    @Override
    public long getExpires() {
        return expires;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Nullable
    @Override
    public String getRedirectUri() {
        return redirectUri;
    }

    @Nullable
    @Override
    public String getAuthorizationCodeHash() {
        return authorizationCodeHash;
    }

    @Override
    public String getScope() {
        return scope;
    }

    @Override
    public AuthenticationAttributes getAuthenticationAttributes() {
        return authenticationAttributes;
    }

    @Nullable
    @Override
    public DelegationConsentResult getConsentResult() {
        return consentResult;
    }

    @Override
    public Map<String, Object> getCustomClaimValues() {
        return customClaimValues;
    }

    @Override
    public Map<String, Object> getClaims() {
        return claims;
    }

    @Override
    public Map<String, Object> getClaimMap() {
        return claimMap;
    }

    @Nullable
    @Override
    public String getMtlsClientCertificate() {
        return mtlsClientCertificate;
    }

    @Nullable
    @Override
    public String getMtlsClientCertificateX5TS256() {
        return mtlsClientCertificateX5TS256;
    }

    @Nullable
    @Override
    public String getMtlsClientCertificateDN() {
        return mtlsClientCertificateDN;
    }

    // Empty set because the property is deprecated
    @Override
    public Set<ScopeClaim> getScopeClaims() {
        return Collections.emptySet();
    }

    @Override
    @JsonIgnore
    public DelegationStatus getEnumActiveStatus() {
        return DelegationStatus.issued;
    }

    @Override
    @JsonIgnore
    public boolean isActive() {
        return Delegation.super.isActive();
    }
}

