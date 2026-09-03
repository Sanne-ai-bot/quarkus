package io.quarkus.deployment.serviceloader;

import java.util.ServiceLoader;

public class ReturnUser {
    public ServiceLoader<Runnable> getServiceLoader() {
        return ServiceLoader.load(Runnable.class);
    }
}
