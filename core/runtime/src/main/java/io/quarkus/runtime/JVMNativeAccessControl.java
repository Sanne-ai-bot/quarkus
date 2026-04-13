package io.quarkus.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Proactively enables native access for Quarkus core infrastructure that loads native libraries
 * early during augmentation, before the build-item-driven {@code enableNativeAccess} path runs.
 * <p>
 * On Windows, the Quarkus console uses aesh which creates a {@code WinSysTerminal} backed by
 * Jansi's {@code Kernel32} JNI class. This triggers {@code System.load()} during augmentation,
 * which on JDK 24+ emits a warning about restricted native method calls. Since augmentation
 * happens before {@code ModuleEnableNativeAccessBuildItem} is processed, we must enable native
 * access before the console is set up.
 * <p>
 * This class is intentionally limited to Windows + JDK 24+ to avoid masking native access
 * warnings on platforms where they wouldn't otherwise occur during augmentation.
 *
 * @see JVMUnsafeWarningsControl for a similar pattern applied to {@code sun.misc.Unsafe} warnings
 */
public final class JVMNativeAccessControl {

    private JVMNativeAccessControl() {
    }

    /**
     * Enables native access for all unnamed modules, so that libraries on the classpath
     * (such as Jansi) can call restricted JNI methods without triggering warnings.
     * <p>
     * This is a no-op on JDK &lt; 24 or on non-Windows platforms.
     * <p>
     * This method uses the same privileged MethodHandles.Lookup trick as
     * {@code ReflectiveAccessModulesReconfigurer}, which requires
     * {@code --add-opens=java.base/java.lang.invoke=ALL-UNNAMED} to be set on the JVM.
     * The dev mode JVM always has this flag set.
     */
    public static void enableNativeAccessIfRequired() {
        if (Runtime.version().feature() < 24) {
            return;
        }
        if (!isWindows()) {
            return;
        }
        try {
            // Acquire the privileged IMPL_LOOKUP to access package-private methods on Module.
            // This requires --add-opens=java.base/java.lang.invoke=ALL-UNNAMED which is
            // always set for the dev mode JVM.
            Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            lookupField.setAccessible(true);
            MethodHandles.Lookup privilegedLookup = (MethodHandles.Lookup) lookupField.get(null);

            // Call Module.addEnableNativeAccessToAllUnnamed() — a package-private static method
            // that sets the enableNativeAccess flag on the singleton ALL_UNNAMED_MODULE sentinel.
            MethodHandle handle = privilegedLookup.findStatic(
                    Module.class,
                    "addEnableNativeAccessToAllUnnamed",
                    MethodType.methodType(void.class));
            handle.invokeExact();
        } catch (Throwable e) {
            // If this fails, the worst case is that the JVM warning is printed — not a fatal error.
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(java.util.Locale.ROOT).contains("windows");
    }
}
