package com.tentixo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.curity.identityserver.sdk.Nullable;
import se.curity.identityserver.sdk.data.StringOrArray;
import se.curity.identityserver.sdk.data.tokens.DefaultStringOrArray;
import se.curity.identityserver.sdk.errors.NoSingleValueException;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public final class StringOrArrayAdapter implements StringOrArray {

    private static final Logger _logger = LoggerFactory.getLogger(StringOrArrayAdapter.class);
    private final StringOrArray _delegate;

    private StringOrArrayAdapter(StringOrArray delegate) {
        _delegate = delegate;
    }

    @JsonCreator
    public static StringOrArrayAdapter of(Object values) {
        if (values instanceof Collection<?>) {
            return of(values);
        } else if (values instanceof String) {
            return of((String) values);
        }
        return of(List.of());
    }

    @JsonCreator
    public static StringOrArrayAdapter of(Collection<String> values) {
        return new StringOrArrayAdapter(DefaultStringOrArray.of(values));
    }

    @JsonCreator
    public static StringOrArrayAdapter of(@Nullable String value) {
        return new StringOrArrayAdapter(DefaultStringOrArray.of(value));
    }

    @JsonIgnore
    @Override
    public @Nullable String getValueOrError() throws NoSingleValueException {
        return _delegate.getValueOrError();
    }

    @JsonIgnore
    @Override
    public @Nullable String getValueOrError(Supplier<? extends NoSingleValueException> exceptionSupplier) throws NoSingleValueException {
        return _delegate.getValueOrError(exceptionSupplier);
    }

    @JsonProperty("values")
    public List<String> getValues() {
        return _delegate.getValues();
    }

    @Override
    public @Nullable Object toStringOrArray() {
        return _delegate.toStringOrArray();
    }

    public boolean contains(String value) {
        return _delegate.contains(value);
    }

    public String toString() {
        return _delegate.toString();
    }

    public boolean equals(Object other) {
        return _delegate.equals(other);
    }

    public int compareTo(StringOrArray other) {
        return _delegate.compareTo(other);
    }
}
