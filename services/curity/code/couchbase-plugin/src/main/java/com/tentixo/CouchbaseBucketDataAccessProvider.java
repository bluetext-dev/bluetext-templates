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

package com.tentixo;

import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import se.curity.identityserver.sdk.datasource.BucketDataAccessProvider;

import java.time.Instant;
import java.util.Map;

public final class CouchbaseBucketDataAccessProvider implements BucketDataAccessProvider {
    private static final Logger _logger = LoggerFactory.getLogger(CouchbaseBucketDataAccessProvider.class);

    private final CouchbaseBucketDataAccessProvider _configuration;

    private static final Marker MASK_MARKER= MarkerFactory.getMarker("MASK");
    public static final String BUCKET_COLLECTION_NAME = "curity-buckets";
    private final CouchbaseExecutor _couchbaseExecutor;
    private final Collection collection;

    public CouchbaseBucketDataAccessProvider(CouchbaseBucketDataAccessProvider configuration, CouchbaseExecutor couchbaseExecutor) {
        _configuration = configuration;
        this._couchbaseExecutor = couchbaseExecutor;
        this.collection = couchbaseExecutor.getScope().collection(BUCKET_COLLECTION_NAME);
    }

    @Override
    public Map<String, Object> getAttributes(String subject, String purpose) {
        _logger.debug(MASK_MARKER, "getAttributes with subject: {} , purpose : {}", subject, purpose);
        String key = getBucketKey(subject, purpose);
        try {
            return collection.get(key).contentAsObject().toMap();
        } catch (DocumentNotFoundException e) {
            return Map.of();
        }
    }

    @Override
    public se.curity.identityserver.sdk.data.GetBucketResult getBucket(String subject, String purpose) {
        _logger.debug(MASK_MARKER, "getBucket with subject: {} , purpose : {}", subject, purpose);
        var attributes = getAttributes(subject, purpose);
        return new se.curity.identityserver.sdk.data.GetBucketResult.Success(attributes, Instant.MAX);
    }

    @Override
    public Map<String, Object> storeAttributes(String subject, String purpose, Map<String, Object> dataMap) {
        Bucket b = new Bucket();
        b.subject = subject;
        b.purpose = purpose;
        b.attributes = dataMap;

        _logger.debug(MASK_MARKER,
                "storeAttributes with subject: {} , purpose : {} and data : {}",
                subject,
                purpose,
                dataMap
        );

        var now = Instant.now().getEpochSecond();
        b.updated = now;
        b.created = now;

        String key = getBucketKey(subject, purpose);

        collection.insert(key, dataMap);

        return dataMap;
    }

    @Override
    public Map<String, Object> storeAttributes(String subject, String purpose, Map<String, Object> dataMap, Instant expiry) {
        _logger.debug(MASK_MARKER, "storeAttributes with subject: {} , purpose : {} , expiry: {}", subject, purpose, expiry);
        return storeAttributes(subject, purpose, dataMap);
    }

    private static String getBucketKey(String subject, String purpose) {
        return String.format("subject::%s::purpose::%s", subject, purpose);
    }

    @Override
    public boolean clearBucket(String subject, String purpose) {
        String key = getBucketKey(subject, purpose);
        try {
            collection.remove(key);
            return true;
        } catch (CouchbaseException ce) {
            return false;
        }
    }

}

class Bucket {
    String subject, purpose;
    Long updated, created;
    Map<String, Object> attributes;
}
