package io.quarkus.deployment.serviceloader;

import java.util.Optional;
import java.util.ServiceLoader;

public class FindFirstUser {
    public Optional<Runnable> loadFirst() {
        return ServiceLoader.load(Runnable.class).findFirst();
    }
}
