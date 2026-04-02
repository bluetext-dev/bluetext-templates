package com.tentixo.token;

public class Nonce {
    private String nonce, nonceStatus, nonceValue;
    private long createdAt, nonceTtl, consumedAt, deleteableAt;

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getNonceStatus() {
        return nonceStatus;
    }

    public void setNonceStatus(String nonceStatus) {
        this.nonceStatus = nonceStatus;
    }

    public String getNonceValue() {
        return nonceValue;
    }

    public void setNonceValue(String nonceValue) {
        this.nonceValue = nonceValue;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getNonceTtl() {
        return nonceTtl;
    }

    public void setNonceTtl(long nonceTtl) {
        this.nonceTtl = nonceTtl;
    }

    public long getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(long consumedAt) {
        this.consumedAt = consumedAt;
    }

    public long getDeleteableAt() {
        return deleteableAt;
    }

    public void setDeleteableAt(long deleteableAt) {
        this.deleteableAt = deleteableAt;
    }
}


