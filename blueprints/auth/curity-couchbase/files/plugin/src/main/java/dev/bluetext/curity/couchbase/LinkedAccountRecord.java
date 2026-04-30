package dev.bluetext.curity.couchbase;

/**
 * Document stored in the linked accounts collection.
 */
public class LinkedAccountRecord {

    private String linkingAccountManager;
    private String localAccountId;
    private String foreignDomainName;
    private String foreignUserName;

    public LinkedAccountRecord() {}

    public LinkedAccountRecord(String linkingAccountManager, String localAccountId,
                               String foreignDomainName, String foreignUserName) {
        this.linkingAccountManager = linkingAccountManager;
        this.localAccountId = localAccountId;
        this.foreignDomainName = foreignDomainName;
        this.foreignUserName = foreignUserName;
    }

    public String getLinkingAccountManager() { return linkingAccountManager; }
    public void setLinkingAccountManager(String linkingAccountManager) { this.linkingAccountManager = linkingAccountManager; }

    public String getLocalAccountId() { return localAccountId; }
    public void setLocalAccountId(String localAccountId) { this.localAccountId = localAccountId; }

    public String getForeignDomainName() { return foreignDomainName; }
    public void setForeignDomainName(String foreignDomainName) { this.foreignDomainName = foreignDomainName; }

    public String getForeignUserName() { return foreignUserName; }
    public void setForeignUserName(String foreignUserName) { this.foreignUserName = foreignUserName; }

    public static String key(String linkingAccountManager, String localAccountId,
                             String foreignDomainName, String foreignUserName) {
        return String.format("link::%s::%s::%s::%s",
                linkingAccountManager, localAccountId, foreignDomainName, foreignUserName);
    }
}
