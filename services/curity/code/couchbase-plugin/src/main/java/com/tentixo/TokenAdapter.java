package com.tentixo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.data.StringOrArray;
import se.curity.identityserver.sdk.data.authorization.Token;
import se.curity.identityserver.sdk.data.authorization.TokenStatus;

import java.util.Map;

public class TokenAdapter implements Token {

    private String tokenHash;

    private String id;

    private String delegationsId;

    private String purpose;

    private String usage;

    private String format;

    private String scope;

    private long created;

    private long expires;

    TokenStatus status;

    private String issuer;

    private String subject;

    private StringOrArray audience;

    private long notBefore;

    private Map<String, Object> data;
    public TokenAdapter(){

    }
    public TokenAdapter(String id, String tokenHash, String delegationsId,  String purpose, String usage, String format, String scope, long created, long expires,
                        TokenStatus status, String issuer, String subject, StringOrArray audience, long notBefore, Map<String, Object> data) {
        this.tokenHash = tokenHash;
        this.id = id;
        this.delegationsId = delegationsId;
        this.purpose = purpose;
        this.usage = usage;
        this.format = format;
        this.scope = scope;
        this.created = created;
        this.expires = expires;
        this.status = status;
        this.issuer = issuer;
        this.subject = subject;
        this.audience = audience;
        this.notBefore = notBefore;
        this.data = data;
    }

    @Override
    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    @Override
    @Nullable
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getDelegationsId() {
        return delegationsId;
    }

    public void setDelegationsId(String delegationsId) {
        this.delegationsId = delegationsId;
    }

    @Override
    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    @Override
    public String getUsage() {
        return usage;
    }

    public void setUsage(String usage) {
        this.usage = usage;
    }

    @Override
    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    @Nullable
    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    @Override
    public long getExpires() {
        return expires;
    }

    public void setExpires(long expires) {
        this.expires = expires;
    }

    @Override
    public TokenStatus getStatus() {
        return status;
    }

    public void setStatus(TokenStatus status) {
        this.status = status;
    }

    @Override
    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    @Override
    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    @Override
    public StringOrArray getAudience() {
        return audience;
    }

    public void setAudience(StringOrArray audience) {
        this.audience = audience;
    }

    @Override
    public long getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(long notBefore) {
        this.notBefore = notBefore;
    }

    @Override
    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public TokenStatus getEnumActiveStatus() {
        return TokenStatus.issued;
    }

    @Override
    public boolean isActive() {
        return Token.super.isActive();
    }



}
