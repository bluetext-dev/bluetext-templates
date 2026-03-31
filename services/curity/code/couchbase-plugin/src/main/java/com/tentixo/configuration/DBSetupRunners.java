package com.tentixo.configuration;

import com.couchbase.client.core.error.CollectionExistsException;
import com.couchbase.client.core.error.IndexNotFoundException;
import com.couchbase.client.core.error.InternalServerFailureException;
import com.couchbase.client.core.retry.reactor.Retry;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.manager.collection.CollectionManager;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.manager.query.CreatePrimaryQueryIndexOptions;
import com.couchbase.client.java.manager.query.WatchQueryIndexesOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static com.couchbase.client.core.util.CbThrowables.findCause;
import static com.couchbase.client.core.util.CbThrowables.hasCause;
import static com.tentixo.CouchbaseBucketDataAccessProvider.BUCKET_COLLECTION_NAME;
import static com.tentixo.CouchbaseSessionDataAccessProvider.SESSION_COLLECTION_NAME;
import static com.tentixo.CouchbaseUserAccountDataAccessProvider.ACCOUNT_COLLECTION_NAME;
import static com.tentixo.token.CouchbaseDelegationDataAccessProvider.DELEGATION_COLLECTION_NAME;
import static com.tentixo.token.CouchbaseNonceDataAccessProvider.NONCE_COLLECTION_NAME;
import static com.tentixo.token.CouchbaseTokenDataAccessProvider.TOKEN_COLLECTION_NAME;


public class DBSetupRunners  {

    private static final Logger logger = LoggerFactory.getLogger(DBSetupRunners.class);

    private static final WatchQueryIndexesOptions WATCH_PRIMARY = WatchQueryIndexesOptions
            .watchQueryIndexesOptions()
            .watchPrimary(true);
    private static final String DEFAULT_INDEX_NAME = "#primary";
    private CreatePrimaryQueryIndexOptions options;


    public void run(Cluster cluster, Bucket bucket, Scope scope) {
        String defaultBucket = bucket.name();
        String defaultScope = scope.name();
        try {
            cluster.queryIndexes().createPrimaryIndex(defaultBucket);
            logger.info("Created primary index" + defaultBucket);
        } catch (Exception e) {
            logger.info("Primary index already exists on bucket " + defaultBucket);
        }

        var collections = Arrays.asList(BUCKET_COLLECTION_NAME, SESSION_COLLECTION_NAME, ACCOUNT_COLLECTION_NAME, DELEGATION_COLLECTION_NAME, NONCE_COLLECTION_NAME, TOKEN_COLLECTION_NAME);

        collections.stream().forEach(col -> createCollection(bucket, defaultScope, col));
        collections.stream().forEach(col -> setupPrimaryIndex(cluster, defaultBucket, defaultScope, col));
    }

    private void setupPrimaryIndex(Cluster cluster, String bucketName, String scope, String collectionName) {

        Mono.fromRunnable(() -> createIndex(cluster, bucketName, scope, collectionName))
                .retryWhen(Retry.onlyIf(ctx ->
                                findCause(ctx.exception(), InternalServerFailureException.class)
                                        .filter(exception -> CouchbaseError.create(exception)
                                                .getErrorEntries().stream()
                                                .anyMatch(err -> err.getMessage().contains("GSI")))
                                        .isPresent())
                        .exponentialBackoff(Duration.ofMillis(50), Duration.ofSeconds(3))
                        .timeout(Duration.ofSeconds(60))
                        .toReactorRetry())
                .block();
        Mono.fromRunnable(() -> waitForIndex(cluster, bucketName, scope, collectionName))
                .retryWhen(Retry.onlyIf(ctx -> hasCause(ctx.exception(), IndexNotFoundException.class))
                        .exponentialBackoff(Duration.ofMillis(50), Duration.ofSeconds(3))
                        .timeout(Duration.ofSeconds(30))
                        .toReactorRetry())
                .block();
        IndexCommons.waitUntilReady(cluster, bucketName, Duration.ofSeconds(60));
    }

    private void createCollection(Bucket bucket, String scope, String collectionName) {
        CollectionManager collectionManager = bucket.collections();
        try {
            CollectionSpec spec = CollectionSpec.create(collectionName, scope);
            collectionManager.createCollection(spec);
            logger.info("Created collection '" + spec.name() + "' in scope '" + spec.scopeName() + "' of bucket '" + bucket.name() + "'");
        } catch (CollectionExistsException e) {
            logger.info(String.format("Collection <%s> already exists", collectionName));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createIndex(Cluster cluster, String bucketName, String scope, String collection) {
        var options = CreatePrimaryQueryIndexOptions.createPrimaryQueryIndexOptions()
                .ignoreIfExists(true)
                .numReplicas(0);
        if (collection != null && scope != null) {
            options.collectionName(collection).scopeName(scope);
        }
        cluster.queryIndexes().createPrimaryIndex(bucketName,
                options);
    }

    private void waitForIndex(Cluster cluster, String bucketName, String scope, String collection) {
        if (collection != null && scope != null) {
            WATCH_PRIMARY.collectionName(collection).scopeName(scope);
        }
        cluster.queryIndexes().watchIndexes(bucketName, Collections.singletonList(DEFAULT_INDEX_NAME),
                Duration.ofSeconds(10), WATCH_PRIMARY);
    }
}
