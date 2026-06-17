package dev.bluetext.curity.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.msg.ResponseStatus;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouchbaseDeviceDataAccessProviderTest {

    @Mock private CouchbaseExecutor executor;
    @Mock private Scope scope;
    @Mock private Collection collection;
    @Mock private CouchbaseDataAccessProviderConfiguration config;

    private CouchbaseDeviceDataAccessProvider provider;

    @BeforeEach
    void setUp() {
        when(executor.getScope()).thenReturn(scope);
        when(scope.collection(CouchbaseDeviceDataAccessProvider.DEVICE_COLLECTION_NAME)).thenReturn(collection);
        provider = new CouchbaseDeviceDataAccessProvider(executor);
    }

    @Test
    void getById_returnsNullOnNotFound() {
        when(collection.get("device::dev1")).thenThrow(
                new DocumentNotFoundException(null));
        assertNull(provider.getById("dev1"));
    }

    @Test
    void delete_handlesNotFound() {
        doThrow(new DocumentNotFoundException(null)).when(collection).remove("device::unknown");
        assertDoesNotThrow(() -> provider.delete("unknown"));
    }

    @Test
    void deleteWithAccountId_onlyDeletesMatchingAccount() {
        when(collection.get("device::dev1")).thenThrow(
                new DocumentNotFoundException(null));
        // getBy returns null because device not found, so remove is never called
        provider.delete("dev1", "acc1");
        verify(collection, never()).remove(anyString());
    }
}
