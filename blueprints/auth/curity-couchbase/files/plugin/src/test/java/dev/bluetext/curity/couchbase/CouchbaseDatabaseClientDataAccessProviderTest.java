package dev.bluetext.curity.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import dev.bluetext.curity.couchbase.configuration.CouchbaseDataAccessProviderConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouchbaseDatabaseClientDataAccessProviderTest {

    @Mock private CouchbaseExecutor executor;
    @Mock private Scope scope;
    @Mock private Collection collection;
    @Mock private CouchbaseDataAccessProviderConfiguration config;

    private CouchbaseDatabaseClientDataAccessProvider provider;

    @BeforeEach
    void setUp() {
        when(executor.getScope()).thenReturn(scope);
        when(scope.collection(CouchbaseDatabaseClientDataAccessProvider.DB_CLIENT_COLLECTION_NAME))
                .thenReturn(collection);
        provider = new CouchbaseDatabaseClientDataAccessProvider(executor);
    }

    @Test
    void getClientById_returnsNullOnNotFound() {
        when(collection.get("dbclient::profile1::client1")).thenThrow(
                new DocumentNotFoundException(null));
        assertNull(provider.getClientById("client1", "profile1"));
    }

    @Test
    void delete_returnsFalseOnNotFound() {
        doThrow(new DocumentNotFoundException(null))
                .when(collection).remove("dbclient::profile1::client1");
        assertFalse(provider.delete("client1", "profile1"));
    }

    @Test
    void delete_returnsTrueOnSuccess() {
        doNothing().when(collection).remove("dbclient::profile1::client1");
        assertTrue(provider.delete("client1", "profile1"));
    }
}
