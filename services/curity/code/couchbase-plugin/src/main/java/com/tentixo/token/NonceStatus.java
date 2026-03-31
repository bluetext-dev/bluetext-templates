package com.tentixo.token;

public enum NonceStatus {
    // The string entries in the DB needs to be lowercase
    // there are integration tests that depend on that
    issued, consumed, expired
}
