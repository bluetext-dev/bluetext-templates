package dev.bluetext.curity.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinkedAccountRecordTest {

    @Test
    void keyFormat() {
        String key = LinkedAccountRecord.key("manager1", "local123", "example.com", "foreignUser");
        assertEquals("link::manager1::local123::example.com::foreignUser", key);
    }

    @Test
    void constructorAndGetters() {
        var record = new LinkedAccountRecord("mgr", "local", "domain", "foreign");
        assertEquals("mgr", record.getLinkingAccountManager());
        assertEquals("local", record.getLocalAccountId());
        assertEquals("domain", record.getForeignDomainName());
        assertEquals("foreign", record.getForeignUserName());
    }

    @Test
    void defaultConstructorAllowsSetters() {
        var record = new LinkedAccountRecord();
        record.setLinkingAccountManager("mgr");
        record.setLocalAccountId("local");
        record.setForeignDomainName("domain");
        record.setForeignUserName("foreign");
        assertEquals("mgr", record.getLinkingAccountManager());
        assertEquals("local", record.getLocalAccountId());
        assertEquals("domain", record.getForeignDomainName());
        assertEquals("foreign", record.getForeignUserName());
    }
}
