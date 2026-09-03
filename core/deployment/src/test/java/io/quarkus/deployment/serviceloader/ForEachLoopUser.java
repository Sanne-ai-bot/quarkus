package io.quarkus.deployment.serviceloader;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class ForEachLoopUser {
    public List<Runnable> loadAll() {
        List<Runnable> result = new ArrayList<>();
        for (Runnable r : ServiceLoader.load(Runnable.class)) {
            result.add(r);
        }
        return result;
    }
}
