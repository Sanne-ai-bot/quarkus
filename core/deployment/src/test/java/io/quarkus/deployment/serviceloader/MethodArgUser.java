package io.quarkus.deployment.serviceloader;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class MethodArgUser {
    public List<Runnable> loadViaHelper() {
        ServiceLoader<Runnable> loader = ServiceLoader.load(Runnable.class);
        return toList(loader);
    }

    private static <S> List<S> toList(ServiceLoader<S> loader) {
        return StreamSupport.stream(loader.spliterator(), false)
                .collect(Collectors.toList());
    }
}
