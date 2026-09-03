package io.quarkus.deployment.serviceloader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public class IteratorUser {
    public List<Runnable> loadWithErrorHandling() {
        List<Runnable> result = new ArrayList<>();
        Iterator<Runnable> it = ServiceLoader.load(Runnable.class).iterator();
        while (it.hasNext()) {
            try {
                result.add(it.next());
            } catch (ServiceConfigurationError e) {
                // skip broken providers
            }
        }
        return result;
    }
}
