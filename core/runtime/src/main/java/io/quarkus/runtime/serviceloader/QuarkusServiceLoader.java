package io.quarkus.runtime.serviceloader;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Drop-in shim for {@link java.util.ServiceLoader}. When the registry knows a service type
 * and the class loader is the production {@code RunnerClassLoader}, providers are instantiated
 * directly from the build-time-computed table. Otherwise, delegates to the real ServiceLoader.
 */
public class QuarkusServiceLoader<S> implements Iterable<S> {

    private static final String RUNNER_CL_NAME = "io.quarkus.bootstrap.runner.RunnerClassLoader";

    private static final String RECORD_PROPERTY = "quarkus.serviceloader.record";
    private static final String VERIFY_PROPERTY = "quarkus.serviceloader.verify";
    private static final PrintStream RECORD_OUT;
    private static final boolean VERIFY;
    private static volatile Method registryMethod;
    private static volatile boolean registryResolved;

    static {
        String recordFile = System.getProperty(RECORD_PROPERTY);
        PrintStream out = null;
        if (recordFile != null) {
            try {
                out = new PrintStream(new java.io.FileOutputStream(recordFile, true));
            } catch (Exception e) {
                out = System.err;
            }
        }
        RECORD_OUT = out;
        VERIFY = Boolean.getBoolean(VERIFY_PROPERTY);
    }

    private final Class<S> service;
    private final ClassLoader classLoader;
    private final boolean useRegistry;
    private List<ServiceLoader.Provider<S>> cachedProviders;

    private QuarkusServiceLoader(Class<S> service, ClassLoader classLoader) {
        this.service = Objects.requireNonNull(service);
        this.classLoader = classLoader;
        this.useRegistry = shouldUseRegistry(service, classLoader);
    }

    public static <S> QuarkusServiceLoader<S> load(Class<S> service) {
        return load(service, Thread.currentThread().getContextClassLoader());
    }

    public static <S> QuarkusServiceLoader<S> load(Class<S> service, ClassLoader loader) {
        return new QuarkusServiceLoader<>(service, loader);
    }

    public static <S> QuarkusServiceLoader<S> loadInstalled(Class<S> service) {
        return new QuarkusServiceLoader<>(service, ClassLoader.getPlatformClassLoader());
    }

    public static <S> QuarkusServiceLoader<S> load(ModuleLayer layer, Class<S> service) {
        return new QuarkusServiceLoader<>(service, Thread.currentThread().getContextClassLoader());
    }

    @Override
    public Iterator<S> iterator() {
        List<ServiceLoader.Provider<S>> providers = providers();
        if (providers == null) {
            return fallbackLoader().iterator();
        }
        return new ProviderIterator<>(providers);
    }

    public Stream<ServiceLoader.Provider<S>> stream() {
        List<ServiceLoader.Provider<S>> providers = providers();
        if (providers == null) {
            return fallbackLoader().stream();
        }
        return providers.stream();
    }

    public Optional<S> findFirst() {
        List<ServiceLoader.Provider<S>> providers = providers();
        if (providers == null) {
            return fallbackLoader().findFirst();
        }
        if (providers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(providers.get(0).get());
    }

    public void reload() {
        cachedProviders = null;
    }

    @Override
    public void forEach(Consumer<? super S> action) {
        iterator().forEachRemaining(action);
    }

    @Override
    public Spliterator<S> spliterator() {
        List<ServiceLoader.Provider<S>> providers = providers();
        if (providers == null) {
            return fallbackLoader().spliterator();
        }
        return new ProviderSpliterator<>(providers);
    }

    @Override
    public String toString() {
        return "QuarkusServiceLoader[" + service.getName() + "]";
    }

    @SuppressWarnings("unchecked")
    private List<ServiceLoader.Provider<S>> providers() {
        if (!useRegistry) {
            return null;
        }
        List<ServiceLoader.Provider<S>> cached = cachedProviders;
        if (cached != null) {
            return cached;
        }
        List<ServiceLoader.Provider<S>> providers = lookupRegistry(service);
        if (providers == null) {
            recordFallback("unknown type");
            return null;
        }
        if (VERIFY) {
            verifyAgainstRealServiceLoader(providers);
        }
        cachedProviders = providers;
        return providers;
    }

    private boolean shouldUseRegistry(Class<S> service, ClassLoader cl) {
        if (cl == null) {
            recordFallback("null classloader (bootstrap)");
            return false;
        }
        if (!isRunnerClassLoader(cl)) {
            recordFallback("foreign class loader: " + cl.getClass().getName());
            return false;
        }
        if (service.getClassLoader() == null || !isRunnerClassLoader(service.getClassLoader())) {
            recordFallback("service type from non-runner loader");
            return false;
        }
        return true;
    }

    private static boolean isRunnerClassLoader(ClassLoader cl) {
        return RUNNER_CL_NAME.equals(cl.getClass().getName());
    }

    private ServiceLoader<S> fallbackLoader() {
        if (classLoader == ClassLoader.getPlatformClassLoader()) {
            return ServiceLoader.loadInstalled(service);
        }
        return ServiceLoader.load(service, classLoader);
    }

    private void recordFallback(String reason) {
        if (RECORD_OUT != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("FALLBACK: ").append(service.getName())
                    .append(" reason=").append(reason);
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            int depth = Math.min(stack.length, 8);
            for (int i = 2; i < depth; i++) {
                sb.append("\n  at ").append(stack[i]);
            }
            RECORD_OUT.println(sb);
            RECORD_OUT.flush();
        }
    }

    private void verifyAgainstRealServiceLoader(List<ServiceLoader.Provider<S>> registryProviders) {
        try {
            ServiceLoader<S> real = ServiceLoader.load(service, classLoader);
            List<Class<?>> realTypes = real.stream()
                    .map(ServiceLoader.Provider::type)
                    .collect(java.util.stream.Collectors.toList());
            List<Class<?>> registryTypes = registryProviders.stream()
                    .map(p -> {
                        try {
                            return (Class<?>) p.type();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());

            if (!realTypes.equals(registryTypes)) {
                System.err.println("VERIFY MISMATCH for " + service.getName());
                System.err.println("  Registry: " + registryTypes);
                System.err.println("  Real:     " + realTypes);
                throw new AssertionError("ServiceLoader registry mismatch for " + service.getName()
                        + ": registry=" + registryTypes + ", real=" + realTypes);
            }
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            System.err.println("VERIFY ERROR for " + service.getName() + ": " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <S> List<ServiceLoader.Provider<S>> lookupRegistry(Class<S> service) {
        Method method = resolveRegistryMethod();
        if (method == null) {
            return null;
        }
        try {
            return (List<ServiceLoader.Provider<S>>) method.invoke(null, service.getName());
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Method resolveRegistryMethod() {
        if (registryResolved) {
            return registryMethod;
        }
        synchronized (QuarkusServiceLoader.class) {
            if (registryResolved) {
                return registryMethod;
            }
            try {
                Class<?> registryClass = Class.forName(
                        "io.quarkus.runtime.serviceloader.GeneratedServiceLoaderRegistry",
                        true, Thread.currentThread().getContextClassLoader());
                registryMethod = registryClass.getMethod("getProviders", String.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                registryMethod = null;
            }
            registryResolved = true;
            return registryMethod;
        }
    }

    private static final class ProviderIterator<S> implements Iterator<S> {
        private final List<ServiceLoader.Provider<S>> providers;
        private int index;

        ProviderIterator(List<ServiceLoader.Provider<S>> providers) {
            this.providers = providers;
        }

        @Override
        public boolean hasNext() {
            return index < providers.size();
        }

        @Override
        public S next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return providers.get(index++).get();
        }
    }

    private static final class ProviderSpliterator<S> implements Spliterator<S> {
        private final List<ServiceLoader.Provider<S>> providers;
        private int index;

        ProviderSpliterator(List<ServiceLoader.Provider<S>> providers) {
            this.providers = providers;
        }

        @Override
        public boolean tryAdvance(Consumer<? super S> action) {
            if (index < providers.size()) {
                action.accept(providers.get(index++).get());
                return true;
            }
            return false;
        }

        @Override
        public Spliterator<S> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return providers.size() - index;
        }

        @Override
        public int characteristics() {
            return ORDERED | SIZED | NONNULL;
        }
    }
}
