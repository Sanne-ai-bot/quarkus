package io.quarkus.runtime.serviceloader;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Base class for generated provider factories. Subclasses are emitted by the build step
 * with a {@code create()} method containing plain {@code new} bytecode for the provider class.
 * <p>
 * Implements {@link ServiceLoader.Provider} so it can be returned directly from
 * {@link QuarkusServiceLoader#stream()}.
 */
public abstract class GeneratedProviderFactory<S> implements ServiceLoader.Provider<S> {

    private final Class<? extends S> type;
    private S cached;
    private boolean instantiated;

    protected GeneratedProviderFactory(Class<? extends S> type) {
        this.type = type;
    }

    @Override
    public Class<? extends S> type() {
        return type;
    }

    protected abstract S create();

    @Override
    public S get() {
        if (!instantiated) {
            try {
                cached = create();
            } catch (ServiceConfigurationError e) {
                throw e;
            } catch (Throwable t) {
                throw new ServiceConfigurationError(
                        "Provider " + type.getName() + " could not be instantiated", t);
            }
            instantiated = true;
        }
        return cached;
    }
}
