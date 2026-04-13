package io.quarkus.deployment.jvm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.deployment.builditem.ModuleEnableNativeAccessBuildItem;
import io.quarkus.deployment.builditem.ModuleOpenBuildItem;

/**
 * Implements the {@link JvmModulesReconfigurer} interface to reconfigure JVM module restrictions through reflective access.
 *
 * Restrictions:
 * - This class relies on the JVM option: `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED` to access otherwise
 * sealed private methods and fields of the java.base module. Without this option, reflective access will fail.
 *
 * Design Notes:
 * - Reflection is used to access internal JVM mechanisms, making this implementation dependent on the stability of the
 * relevant internal API. We therefore rely on a multiple of different strategies - this being one of them.
 * - This approach bypasses strict module system rules, enabling dynamic adjustments to module access at runtime:
 * it's meant as a convenience during development, Quarkus does not use such techniques in production mode.
 */
final class ReflectiveAccessModulesReconfigurer implements JvmModulesReconfigurer {

    private static final Logger logger = JVMDeploymentLogger.logger;
    private final MethodHandle implAddOpensHandle;
    private final MethodHandle implAddEnableNativeAccessHandle;
    private final MethodHandle addEnableNativeAccessToAllUnnamedHandle;

    ReflectiveAccessModulesReconfigurer() {
        final MethodHandles.Lookup privilegedLookup = acquirePrivilegedLookup();
        implAddOpensHandle = findImplAddOpens(privilegedLookup);
        implAddEnableNativeAccessHandle = findImplAddEnableNativeAccess(privilegedLookup);
        addEnableNativeAccessToAllUnnamedHandle = findAddEnableNativeAccessToAllUnnamed(privilegedLookup);
    }

    @Override
    public void openJavaModules(List<ModuleOpenBuildItem> addOpens, ModulesClassloaderContext modulesContext) {
        if (addOpens.isEmpty())
            return;
        for (ModuleOpenBuildItem m : addOpens) {
            final Module openedModule = modulesContext.findModule(m.openedModuleName());
            final Module openingModule = modulesContext.findModule(m.openingModuleName());
            for (String packageName : m.packageNames()) {
                addOpens(openedModule, packageName, openingModule);
            }
        }
    }

    /**
     * Acquires the super-privileged MethodHandles.Lookup instance (IMPL_LOOKUP):
     * this is necessary to access otherwise sealed private methods.
     * This MUST be run with: --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
     */
    private static MethodHandles.Lookup acquirePrivilegedLookup() {
        try {
            Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            //This setAccessible call is the part that would fail when the java.base module is not opened.
            lookupField.setAccessible(true);
            return (MethodHandles.Lookup) lookupField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | InaccessibleObjectException e) {
            throw new RuntimeException("Failed to acquire privileged MethodHandles.Lookup. " +
                    "This must be run with JVM parameter '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED'", e);
        }
    }

    /**
     * Finds the private Module#implAddOpens(String, Module) method.
     */
    private static MethodHandle findImplAddOpens(MethodHandles.Lookup privilegedLookup) {
        try {
            MethodType methodType = MethodType.methodType(void.class, String.class, Module.class);
            MethodHandle handle = privilegedLookup.findVirtual(Module.class, "implAddOpens", methodType);
            logger.debug("Successfully acquired MethodHandle for implAddOpens.");
            return handle;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to acquire handle to Module#implAddOpens", e);
        }
    }

    /**
     * Finds the private Module#implAddEnableNativeAccess() instance method.
     * This method sets the enableNativeAccess flag on a specific Module, allowing it to
     * call restricted methods (JNI, FFM) without warnings or errors.
     * Used for named modules.
     */
    private static MethodHandle findImplAddEnableNativeAccess(MethodHandles.Lookup privilegedLookup) {
        try {
            MethodType methodType = MethodType.methodType(Module.class);
            MethodHandle handle = privilegedLookup.findVirtual(Module.class, "implAddEnableNativeAccess", methodType);
            logger.debug("Successfully acquired MethodHandle for implAddEnableNativeAccess.");
            return handle;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to acquire handle to Module#implAddEnableNativeAccess", e);
        }
    }

    /**
     * Finds the package-private static Module.addEnableNativeAccessToAllUnnamed() method.
     * For unnamed modules, the JDK checks a singleton ALL_UNNAMED_MODULE sentinel rather than
     * individual unnamed module instances, so we need this static method to enable native access
     * for all unnamed modules at once.
     */
    private static MethodHandle findAddEnableNativeAccessToAllUnnamed(MethodHandles.Lookup privilegedLookup) {
        try {
            MethodType methodType = MethodType.methodType(void.class);
            MethodHandle handle = privilegedLookup.findStatic(Module.class, "addEnableNativeAccessToAllUnnamed", methodType);
            logger.debug("Successfully acquired MethodHandle for addEnableNativeAccessToAllUnnamed.");
            return handle;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to acquire handle to Module#addEnableNativeAccessToAllUnnamed", e);
        }
    }

    @Override
    public void enableNativeAccess(List<ModuleEnableNativeAccessBuildItem> nativeAccesses,
            ModulesClassloaderContext modulesContext) {
        if (nativeAccesses.isEmpty())
            return;
        boolean allUnnamedDone = false;
        for (ModuleEnableNativeAccessBuildItem nativeAccess : nativeAccesses) {
            final Module module = modulesContext.findModule(nativeAccess.moduleName());
            if (module.isNamed()) {
                enableNativeAccessOnModule(module);
            } else if (!allUnnamedDone) {
                // For unnamed modules, the JDK uses a singleton ALL_UNNAMED_MODULE sentinel
                // to check native access (see Module.moduleForNativeAccess()), so we need to
                // enable it via the static addEnableNativeAccessToAllUnnamed() method.
                enableNativeAccessForAllUnnamed();
                allUnnamedDone = true;
            }
        }
    }

    /**
     * Uses the MethodHandle to open a package.
     *
     * @param sourceModule The module to open
     * @param packageName The package to open
     * @param targetModule The module to open to
     */
    private void addOpens(Module sourceModule, String packageName, Module targetModule) {
        try {
            implAddOpensHandle.invokeExact(sourceModule, packageName, targetModule);
            logger.debugf("Successfully opened module %s/%s to %s",
                    sourceModule.getName(), packageName, targetModule.isNamed() ? targetModule.getName() : "UNNAMED");
        } catch (Throwable e) {
            // MethodHandle.invokeExact throws Throwable
            throw new RuntimeException("Failed to invoke implAddOpens", e);
        }
    }

    /**
     * Uses the MethodHandle to enable native access for a named module.
     *
     * @param module The named module to enable native access for
     */
    private void enableNativeAccessOnModule(Module module) {
        try {
            Module ignored = (Module) implAddEnableNativeAccessHandle.invokeExact(module);
            logger.debugf("Successfully enabled native access for module %s", module.getName());
        } catch (Throwable e) {
            // MethodHandle.invokeExact throws Throwable
            throw new RuntimeException("Failed to invoke implAddEnableNativeAccess on module " + module.getName(), e);
        }
    }

    /**
     * Enables native access for all unnamed modules by calling the static
     * Module.addEnableNativeAccessToAllUnnamed() method.
     */
    private void enableNativeAccessForAllUnnamed() {
        try {
            addEnableNativeAccessToAllUnnamedHandle.invokeExact();
            logger.debug("Successfully enabled native access for all unnamed modules");
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke addEnableNativeAccessToAllUnnamed", e);
        }
    }

}
