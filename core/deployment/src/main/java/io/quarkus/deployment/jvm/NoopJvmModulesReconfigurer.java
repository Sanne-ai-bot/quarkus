package io.quarkus.deployment.jvm;

import java.util.List;

import io.quarkus.deployment.builditem.ModuleEnableNativeAccessBuildItem;
import io.quarkus.deployment.builditem.ModuleOpenBuildItem;

class NoopJvmModulesReconfigurer implements JvmModulesReconfigurer {

    static final NoopJvmModulesReconfigurer INSTANCE = new NoopJvmModulesReconfigurer();

    @Override
    public void openJavaModules(List<ModuleOpenBuildItem> addOpens, ModulesClassloaderContext referenceClassloader) {
        // noop
    }

    @Override
    public void enableNativeAccess(List<ModuleEnableNativeAccessBuildItem> nativeAccesses,
            ModulesClassloaderContext modulesContext) {
        // noop - native access restrictions are not enforced before JDK 25
    }
}
