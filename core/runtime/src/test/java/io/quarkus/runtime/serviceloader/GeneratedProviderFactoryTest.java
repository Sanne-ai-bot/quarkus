package io.quarkus.runtime.serviceloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ServiceConfigurationError;

import org.junit.jupiter.api.Test;

class GeneratedProviderFactoryTest {

    public interface TestService {
    }

    public static class GoodProvider implements TestService {
    }

    public static class ThrowingProvider implements TestService {
        public ThrowingProvider() {
            throw new RuntimeException("constructor failed");
        }
    }

    @Test
    void typeShouldReturnProviderClass() {
        GeneratedProviderFactory<TestService> factory = new GeneratedProviderFactory<>(GoodProvider.class) {
            @Override
            protected TestService create() {
                return new GoodProvider();
            }
        };
        assertEquals(GoodProvider.class, factory.type());
    }

    @Test
    void getShouldInstantiateAndCache() {
        GeneratedProviderFactory<TestService> factory = new GeneratedProviderFactory<>(GoodProvider.class) {
            @Override
            protected TestService create() {
                return new GoodProvider();
            }
        };
        TestService first = factory.get();
        TestService second = factory.get();
        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void getShouldWrapConstructorException() {
        GeneratedProviderFactory<TestService> factory = new GeneratedProviderFactory<>(ThrowingProvider.class) {
            @Override
            protected TestService create() {
                return new ThrowingProvider();
            }
        };
        ServiceConfigurationError error = assertThrows(ServiceConfigurationError.class, factory::get);
        assertNotNull(error.getCause());
        assertEquals("constructor failed", error.getCause().getMessage());
    }

    @Test
    void errorProviderFactoryShouldThrowOnGet() {
        ErrorProviderFactory<TestService> factory = new ErrorProviderFactory<>(
                "com.example.MissingProvider",
                "Provider com.example.MissingProvider not found");
        assertThrows(ServiceConfigurationError.class, factory::get);
    }

    @Test
    void errorProviderWithExistingClassShouldReturnType() {
        ErrorProviderFactory<TestService> factory = new ErrorProviderFactory<>(
                GoodProvider.class, "not a subtype");
        assertEquals(GoodProvider.class, factory.type());
        assertThrows(ServiceConfigurationError.class, factory::get);
    }
}
