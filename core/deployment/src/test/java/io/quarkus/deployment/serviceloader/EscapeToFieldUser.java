package io.quarkus.deployment.serviceloader;

import java.util.ServiceLoader;

public class EscapeToFieldUser {
    private ServiceLoader<Runnable> loader;

    public void storeToField() {
        loader = ServiceLoader.load(Runnable.class);
    }

    public ServiceLoader<Runnable> getLoader() {
        return loader;
    }
}
