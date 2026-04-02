package com.tentixo.configuration;

import java.util.Map;

public class IndexInfo {
    public final String name;
    public final String bucket;
    public final String status;
    public final String lastScanTime;

    public IndexInfo(String name, String bucket, String status, String lastScanTime) {
        this.name = name;
        this.bucket = bucket;
        this.status = status;
        this.lastScanTime = lastScanTime;
    }

    public static IndexInfo create(Map<?, ?> asMap) {
        String name = (String) asMap.get("index");
        String bucket = (String) asMap.get("bucket");
        String status = (String) asMap.get("status");
        String lastScanTime = (String) asMap.get("lastScanTime");
        return new IndexInfo(name, bucket, status, lastScanTime);
    }

    public String getName() {
        return name;
    }

    public String getBucket() {
        return bucket;
    }

    public String getStatus() {
        return status;
    }

    public String getQualified() {
        return bucket + ":" + name;
    }

    @Override
    public String toString() {
        return "IndexInfo{" +
                "name='" + name + '\'' +
                ", bucket='" + bucket + '\'' +
                ", status='" + status + '\'' +
                ", lastScanTime='" + lastScanTime + '\'' +
                '}';
    }
}

