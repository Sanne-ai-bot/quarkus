package io.quarkus.deployment.serviceloader;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public class StreamUser {
    public List<Runnable> loadViaStream() {
        return ServiceLoader.load(Runnable.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toList());
    }
}
