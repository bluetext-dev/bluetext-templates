package com.tentixo;


import com.couchbase.client.core.encryption.CryptoManager;
import com.couchbase.client.core.error.DecodingFailureException;
import com.couchbase.client.core.error.EncodingFailureException;
import com.couchbase.client.java.codec.JsonSerializer;
import com.couchbase.client.java.codec.TypeRef;
import com.couchbase.client.java.encryption.databind.jackson.EncryptionModule;
import com.couchbase.client.java.json.JsonValueModule;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.ConstructorDetector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.fasterxml.jackson.module.paranamer.ParanamerModule;
import com.tentixo.token.DelegationAdapter;
import se.curity.identityserver.sdk.attribute.Attributes;
import se.curity.identityserver.sdk.attribute.AuthenticationAttributes;
import se.curity.identityserver.sdk.data.StringOrArray;
import se.curity.identityserver.sdk.data.authorization.Delegation;
import se.curity.identityserver.sdk.data.authorization.Token;
import se.curity.identityserver.sdk.data.tokens.DefaultStringOrArray;
import se.curity.identityserver.sdk.errors.NoSingleValueException;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

import static com.couchbase.client.core.logging.RedactableArgument.redactUser;
import static java.nio.charset.StandardCharsets.UTF_8;

public class CurityJsonSerializer implements JsonSerializer {


    private final ObjectMapper mapper =  JsonMapper.builder()
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    /**
     * Creates an instance without encryption support.
     *
     * @return the default JSON serializer without encryption support.
     */
    public static CurityJsonSerializer create() {
        return create( null);
    }

    /**
     * Creates an instance with optional encryption support.
     *
     * @param cryptoManager (nullable) The manager to use for activating the
     * {@code Encrypted} annotation, or null to disable encryption support.
     * @return the default JSON serializer with encryption support.
     */
    public static CurityJsonSerializer create(CryptoManager cryptoManager) {
        return new CurityJsonSerializer(cryptoManager);
    }

    private CurityJsonSerializer(CryptoManager cryptoManager) {
        mapper.registerModule(new JsonValueModule());
        mapper.registerModule(new JavaTimeModule());
        if (cryptoManager != null) {
            mapper.registerModule(new EncryptionModule(cryptoManager));
        }
        final var simpleModule = new SimpleModule()
                .addSerializer(Supplier.class, new com.fasterxml.jackson.databind.JsonSerializer<Supplier>() {

                    @Override
                    public void serialize(Supplier value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                        Object o = value.get();
                        if (o instanceof NoSingleValueException) {
                            gen.writeString("NoSingleValeError");
                        }
                    }
                })
                .addDeserializer(Supplier.class, new JsonDeserializer<Supplier>() {
                    @Override
                    public Supplier deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
                        return new Supplier() {
                            @Override
                            public Object get() {
                                try {
                                    return p.getValueAsString();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        };
                    }
                })
                .addSerializer(AuthenticationAttributes.class, new com.fasterxml.jackson.databind.JsonSerializer<AuthenticationAttributes>() {
                    @Override
                    public void serialize(AuthenticationAttributes value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                        Map<String, Object> m = value.toMap();
                        gen.writeObject(m);
                    }
                })
                .addDeserializer(AuthenticationAttributes.class, new JsonDeserializer<AuthenticationAttributes>(){

                    @Override
                    public AuthenticationAttributes deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
                        Map<String, Object> m = p.readValueAs(Map.class);
                        return AuthenticationAttributes.fromAttributes( Attributes.fromMap(m));
                    }
                })
                .addAbstractTypeMapping(Token.class, TokenAdapter.class)
                .addAbstractTypeMapping(Delegation.class, DelegationAdapter.class)
                .addAbstractTypeMapping(StringOrArray.class, StringOrArrayAdapter.class);
        mapper.registerModule(simpleModule);
        mapper.registerModule(new ParameterNamesModule());
        mapper.registerModule(new ParanamerModule());

    }

    @Override
    public byte[] serialize(final Object input) {
        if (input instanceof byte[]) {
            return (byte[]) input;
        }

        try {
            return mapper.writeValueAsBytes(input);
        } catch (Throwable t) {
            throw new EncodingFailureException("Serializing of content + " + redactUser(input) + " to JSON failed.", t);
        }
    }

    @Override
    public <T> T deserialize(final Class<T> target, final byte[] input) {
        if (target.equals(byte[].class)) {
            return (T) input;
        }

        try {
            return mapper.readValue(input, target);
        } catch (Throwable e) {
            throw new DecodingFailureException("Deserialization of content into target " + target
                    + " failed; encoded = " + redactUser(new String(input, UTF_8)), e);
        }
    }

    @Override
    public <T> T deserialize(final TypeRef<T> target, final byte[] input) {
        try {
            JavaType type = mapper.getTypeFactory().constructType(target.type());
            return mapper.readValue(input, type);
        } catch (Throwable e) {
            throw new DecodingFailureException("Deserialization of content into target " + target
                    + " failed; encoded = " + redactUser(new String(input, UTF_8)), e);
        }
    }
}
