package dev.bluetext.curity.couchbase;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouchbaseDynamicallyRegisteredClientDataAccessProviderTest {

    @Mock private CouchbaseExecutor executor;
    @Mock private Scope scope;
    @Mock private Collection collection;

    private CouchbaseDynamicallyRegisteredClientDataAccessProvider provider;

    @BeforeEach
    void setUp() {
        when(executor.getScope()).thenReturn(scope);
        when(scope.collection(CouchbaseDynamicallyRegisteredClientDataAccessProvider.DCR_COLLECTION_NAME))
                .thenReturn(collection);
        provider = new CouchbaseDynamicallyRegisteredClientDataAccessProvider(executor);
    }

    @Test
    void getByClientId_returnsNullOnNotFound() {
        when(collection.get("dcr::client1")).thenThrow(
                new DocumentNotFoundException(null));
        assertNull(provider.getByClientId("client1"));
    }

    @Test
    void delete_handlesNotFound() {
        doThrow(new DocumentNotFoundException(null))
                .when(collection).remove("dcr::unknown");
        assertDoesNotThrow(() -> provider.delete("unknown"));
    }
}
