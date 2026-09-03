package io.quarkus.runtime.serviceloader;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * A provider factory for providers that failed build-time verification.
 * {@link #get()} throws a {@link ServiceConfigurationError} matching the JDK's wording.
 * {@link #type()} returns the provider class if it was loadable, or throws if not.
 */
public class ErrorProviderFactory<S> implements ServiceLoader.Provider<S> {

    private final String providerClassName;
    private final String message;
    private Class<? extends S> resolvedType;

    public ErrorProviderFactory(String providerClassName, String message) {
        this.providerClassName = providerClassName;
        this.message = message;
    }

    @SuppressWarnings("unchecked")
    public ErrorProviderFactory(Class<?> providerClass, String message) {
        this.providerClassName = providerClass.getName();
        this.resolvedType = (Class<? extends S>) providerClass;
        this.message = message;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends S> type() {
        if (resolvedType != null) {
            return resolvedType;
        }
        try {
            resolvedType = (Class<? extends S>) Class.forName(providerClassName,
                    false, Thread.currentThread().getContextClassLoader());
            return resolvedType;
        } catch (ClassNotFoundException e) {
            throw new ServiceConfigurationError("Provider " + providerClassName + " not found", e);
        }
    }

    @Override
    public S get() {
        throw new ServiceConfigurationError(message);
    }
}
