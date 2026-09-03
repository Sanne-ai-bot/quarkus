package io.quarkus.deployment.serviceloader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ServiceLoaderCallSiteRewriterTest {

    private static final String SHIM_CLASS = "io/quarkus/runtime/serviceloader/QuarkusServiceLoader";
    private static final String SERVICE_LOADER_CLASS = "java/util/ServiceLoader";

    @Test
    void forEachLoopShouldBeRewritten() throws IOException {
        TransformResult result = transformClass(ForEachLoopUser.class);
        assertTrue(result.hasShimCall, "for-each loop should produce a rewritten call site");
        assertFalse(result.hasOriginalServiceLoaderCall, "original ServiceLoader call should be gone");
    }

    @Test
    void iteratorUsageShouldBeRewritten() throws IOException {
        TransformResult result = transformClass(IteratorUser.class);
        assertTrue(result.hasShimCall);
    }

    @Test
    void streamMapShouldBeRewritten() throws IOException {
        TransformResult result = transformClass(StreamUser.class);
        assertTrue(result.hasShimCall);
    }

    @Test
    void findFirstShouldBeRewritten() throws IOException {
        TransformResult result = transformClass(FindFirstUser.class);
        assertTrue(result.hasShimCall);
    }

    @Test
    void escapeToFieldShouldNotBeRewritten() throws IOException {
        TransformResult result = transformClass(EscapeToFieldUser.class);
        assertFalse(result.hasShimCall,
                "load call must NOT be rewritten when value escapes to field (would cause VerifyError)");
        assertTrue(result.hasOriginalServiceLoaderCall,
                "original ServiceLoader.load call should be preserved");
    }

    @Test
    void returnValueShouldNotBeRewritten() throws IOException {
        TransformResult result = transformClass(ReturnUser.class);
        assertFalse(result.hasShimCall,
                "load call must NOT be rewritten when value is returned (would cause VerifyError)");
        assertTrue(result.hasOriginalServiceLoaderCall,
                "original ServiceLoader.load call should be preserved");
    }

    @Test
    void methodArgShouldNotBeRewritten() throws IOException {
        TransformResult result = transformClass(MethodArgUser.class);
        assertFalse(result.hasShimCall,
                "load call must NOT be rewritten when value is passed as method argument");
        assertTrue(result.hasOriginalServiceLoaderCall,
                "original ServiceLoader.load call should be preserved");
    }

    private TransformResult transformClass(Class<?> clazz) throws IOException {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        byte[] original;
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourceName)) {
            original = is.readAllBytes();
        }

        ServiceLoaderShortCircuitProcessor.ServiceLoaderCallSiteRewriter rewriter = new ServiceLoaderShortCircuitProcessor.ServiceLoaderCallSiteRewriter();

        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = rewriter.apply(clazz.getName(), writer);
        reader.accept(visitor, 0);
        byte[] transformed = writer.toByteArray();

        return analyzeTransformed(transformed);
    }

    private TransformResult analyzeTransformed(byte[] classBytes) {
        TransformResult result = new TransformResult();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                            String mdescriptor, boolean isInterface) {
                        if (owner.equals(SHIM_CLASS)) {
                            result.hasShimCall = true;
                        }
                        if (owner.equals(SERVICE_LOADER_CLASS)
                                && (mname.equals("load") || mname.equals("loadInstalled"))) {
                            result.hasOriginalServiceLoaderCall = true;
                        }
                    }
                };
            }
        }, 0);
        return result;
    }

    static class TransformResult {
        boolean hasShimCall;
        boolean hasOriginalServiceLoaderCall;
    }
}
