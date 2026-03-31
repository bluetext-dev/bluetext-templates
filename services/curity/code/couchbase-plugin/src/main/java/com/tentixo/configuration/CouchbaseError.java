package com.tentixo.configuration;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.context.ErrorContext;

import java.util.*;

public class CouchbaseError {
    private static final String UNKNOWN = "UNKNOWN";

    private final List<ErrorEntry> errorEntries;

    public CouchbaseError(List<ErrorEntry> errorEntries) {
        this.errorEntries = errorEntries;
    }

    public static CouchbaseError create(CouchbaseException exception) {
        ErrorContext context = exception.context();
        if (context == null) {
            return unknown(exception);
        }
        Map<String, Object> exported = new HashMap<>();
        context.injectExportableParams(exported);
        Object errors = exported.get("errors");
        if (!(errors instanceof List<?>)) {
            return unknown(exception);
        }
        List<ErrorEntry> entries = new ArrayList<>();
        for (Object errorObject : (List<?>) errors) {
            if (!(errorObject instanceof Map<?, ?>)) {
                return unknown(exception);
            }
            Map<?, ?> errorMap = (Map<?, ?>) errorObject;
            Object errorCode = errorMap.get("code");
            if (errorCode == null) {
                continue;
            }
            entries.add(new ErrorEntry(errorCode.toString(), String.valueOf(errorMap.get("message"))));
        }
        return new CouchbaseError(entries);
    }

    private static CouchbaseError unknown(CouchbaseException exception) {
        return new CouchbaseError(Collections.singletonList(new ErrorEntry(UNKNOWN, exception.toString())));
    }

    public List<ErrorEntry> getErrorEntries() {
        return errorEntries;
    }

    public ErrorEntry getFirstError() {
        return errorEntries.stream().findFirst().orElse(null);
    }

    public static class ErrorEntry {
        private final String errorCode;
        private final String message;

        private ErrorEntry(String errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
