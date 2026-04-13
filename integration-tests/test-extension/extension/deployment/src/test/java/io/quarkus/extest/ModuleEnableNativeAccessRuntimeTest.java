package io.quarkus.extest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Verifies that {@link io.quarkus.deployment.builditem.ModuleEnableNativeAccessBuildItem} is processed
 * at runtime (not just in the JAR manifest), so that native access is actually enabled on the
 * unnamed module during test/dev mode.
 * <p>
 * The build item is produced by
 * {@link io.quarkus.extest.deployment.ModulesCustomProcessor#allowNativeLibraryLoad()} with a
 * fake module name that doesn't exist, causing it to fall back to the unnamed module.
 * <p>
 * This test only runs on JDK 25+ where native access restrictions are enforced.
 */
@EnabledForJreRange(min = JRE.JAVA_25)
public class ModuleEnableNativeAccessRuntimeTest {

    @RegisterExtension
    static QuarkusExtensionTest test = new QuarkusExtensionTest();

    @Test
    void unnamedModuleHasNativeAccessEnabled() throws Exception {
        // The test class is in the unnamed module of the Quarkus runtime classloader
        Module unnamedModule = getClass().getModule();

        // Use reflection since isNativeAccessEnabled() is a JDK 22+ API
        // and the project compiles with --release 17
        Method isNativeAccessEnabled = Module.class.getMethod("isNativeAccessEnabled");
        boolean enabled = (boolean) isNativeAccessEnabled.invoke(unnamedModule);
        assertTrue(enabled,
                "Native access should be enabled on the unnamed module after "
                        + "ModuleEnableNativeAccessBuildItem is processed at runtime");
    }
}
