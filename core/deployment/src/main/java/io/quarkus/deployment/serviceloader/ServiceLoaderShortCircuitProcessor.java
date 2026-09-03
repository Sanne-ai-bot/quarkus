package io.quarkus.deployment.serviceloader;

import static io.quarkus.gizmo.MethodDescriptor.ofConstructor;
import static io.quarkus.gizmo.MethodDescriptor.ofMethod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.quarkus.deployment.BootstrapConfig;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.index.ConstPoolScanner;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Build step that short-circuits {@link java.util.ServiceLoader} resolution at build time.
 * <p>
 * Phase 1: scans the runtime classpath for META-INF/services files, computes the provider table,
 * and generates a registry class.
 * <p>
 * Phase 3: rewrites ServiceLoader.load call sites in application and dependency classes to use
 * the Quarkus shim.
 */
public class ServiceLoaderShortCircuitProcessor {

    private static final Logger LOG = Logger.getLogger(ServiceLoaderShortCircuitProcessor.class);

    private static final String REGISTRY_CLASS = "io/quarkus/runtime/serviceloader/GeneratedServiceLoaderRegistry";
    private static final String REGISTRY_CLASS_BINARY = "io.quarkus.runtime.serviceloader.GeneratedServiceLoaderRegistry";
    private static final String PROVIDER_FACTORY_CLASS = "io/quarkus/runtime/serviceloader/GeneratedProviderFactory";
    private static final String ERROR_FACTORY_CLASS = "io/quarkus/runtime/serviceloader/ErrorProviderFactory";
    private static final String SHIM_CLASS = "io/quarkus/runtime/serviceloader/QuarkusServiceLoader";
    private static final String SERVICE_LOADER_CLASS = "java/util/ServiceLoader";

    private static final Set<String> REWRITABLE_SL_METHODS = Set.of(
            "iterator", "stream", "findFirst", "reload", "forEach", "spliterator", "toString");
    private static final Set<String> REWRITABLE_ITERABLE_METHODS = Set.of(
            "iterator", "forEach", "spliterator");

    enum ServiceClassification {
        APP_ONLY,
        JDK_INVOLVED
    }

    static class ServiceTypeInfo {
        final String serviceTypeName;
        final List<String> providerNames;
        final ServiceClassification classification;
        final Map<String, ProviderVerification> verifications;

        ServiceTypeInfo(String serviceTypeName, List<String> providerNames,
                ServiceClassification classification, Map<String, ProviderVerification> verifications) {
            this.serviceTypeName = serviceTypeName;
            this.providerNames = providerNames;
            this.classification = classification;
            this.verifications = verifications;
        }
    }

    enum ProviderVerification {
        OK,
        CLASS_NOT_FOUND,
        NOT_A_SUBTYPE,
        NO_PUBLIC_NO_ARG_CONSTRUCTOR,
        ABSTRACT_OR_INTERFACE
    }

    @BuildStep
    void processServiceLoaders(
            BootstrapConfig bootstrapConfig,
            PackageConfig packageConfig,
            CurateOutcomeBuildItem curateOutcome,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<GeneratedClassBuildItem> generatedClasses,
            BuildProducer<BytecodeTransformerBuildItem> bytecodeTransformers) {

        if (!bootstrapConfig.serviceLoaderShortCircuit()) {
            return;
        }

        PackageConfig.JarConfig.JarType jarType = packageConfig.jar().type();
        if (jarType == PackageConfig.JarConfig.JarType.MUTABLE_JAR) {
            LOG.info("ServiceLoader short-circuit disabled for mutable-jar package type");
            return;
        }
        if (!jarType.usesFastJarLayout()) {
            LOG.info("ServiceLoader short-circuit only supported for fast-jar layout, skipping");
            return;
        }

        LOG.info("ServiceLoader short-circuit: scanning runtime classpath");

        Map<String, ServiceTypeInfo> serviceTable = buildServiceTable(curateOutcome, combinedIndex.getComputingIndex());

        long appOnly = serviceTable.values().stream()
                .filter(s -> s.classification == ServiceClassification.APP_ONLY).count();
        long jdkInvolved = serviceTable.values().stream()
                .filter(s -> s.classification == ServiceClassification.JDK_INVOLVED).count();
        LOG.infof("ServiceLoader short-circuit: %d APP_ONLY service types, %d JDK_INVOLVED (fallback)", appOnly, jdkInvolved);

        generateRegistry(serviceTable, generatedClasses);
        dumpTable(serviceTable, curateOutcome);
        rewriteCallSites(curateOutcome, bytecodeTransformers);
    }

    Map<String, ServiceTypeInfo> buildServiceTable(
            CurateOutcomeBuildItem curateOutcome,
            IndexView combinedIndex) {

        Map<String, LinkedHashSet<String>> rawProviders = collectServiceFiles(curateOutcome);

        Map<String, ServiceTypeInfo> table = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : rawProviders.entrySet()) {
            String serviceType = entry.getKey();
            List<String> providerNames = new ArrayList<>(entry.getValue());
            ServiceClassification classification = classifyServiceType(serviceType);
            Map<String, ProviderVerification> verifications = new LinkedHashMap<>();

            if (classification == ServiceClassification.APP_ONLY) {
                for (String providerName : providerNames) {
                    verifications.put(providerName, verifyProvider(providerName, serviceType, combinedIndex));
                }
            }

            table.put(serviceType, new ServiceTypeInfo(serviceType, providerNames, classification, verifications));
        }
        return table;
    }

    private Map<String, LinkedHashSet<String>> collectServiceFiles(CurateOutcomeBuildItem curateOutcome) {
        Map<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        try {
            Set<String> serviceFileNames = new HashSet<>();

            for (ResolvedDependency dep : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
                dep.getContentTree().walk(visit -> {
                    String path = visit.getRelativePath("/");
                    if (path.startsWith("META-INF/services/") && path.length() > "META-INF/services/".length()) {
                        String serviceName = path.substring("META-INF/services/".length());
                        if (!serviceName.contains("/")) {
                            serviceFileNames.add(serviceName);
                        }
                    }
                });
            }

            Path appRoot = curateOutcome.getApplicationModel().getAppArtifact().getResolvedPaths().getSinglePath();
            if (Files.isDirectory(appRoot)) {
                Path servicesDir = appRoot.resolve("META-INF").resolve("services");
                if (Files.isDirectory(servicesDir)) {
                    try (var stream = Files.list(servicesDir)) {
                        stream.filter(Files::isRegularFile)
                                .map(p -> p.getFileName().toString())
                                .forEach(serviceFileNames::add);
                    }
                }
            }

            for (String serviceName : serviceFileNames) {
                String resourceName = "META-INF/services/" + serviceName;
                LinkedHashSet<String> providers = new LinkedHashSet<>();

                Enumeration<java.net.URL> resources = cl.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    java.net.URL url = resources.nextElement();
                    try (InputStream is = url.openStream();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            int comment = line.indexOf('#');
                            if (comment >= 0) {
                                line = line.substring(0, comment);
                            }
                            line = line.trim();
                            if (!line.isEmpty()) {
                                providers.add(line);
                            }
                        }
                    }
                }

                if (!providers.isEmpty()) {
                    result.put(serviceName, providers);
                }
            }
        } catch (IOException e) {
            LOG.warn("ServiceLoader short-circuit: error scanning service files", e);
        }

        return result;
    }

    private ServiceClassification classifyServiceType(String serviceTypeName) {
        try {
            Class<?> serviceType = Class.forName(serviceTypeName, false,
                    Thread.currentThread().getContextClassLoader());

            if (serviceType.getClassLoader() == null
                    || serviceType.getClassLoader() == ClassLoader.getPlatformClassLoader()) {
                return ServiceClassification.JDK_INVOLVED;
            }

            ServiceLoader<?> platformLoader = ServiceLoader.load(serviceType, ClassLoader.getPlatformClassLoader());
            if (platformLoader.stream().findAny().isPresent()) {
                return ServiceClassification.JDK_INVOLVED;
            }

            return ServiceClassification.APP_ONLY;
        } catch (ClassNotFoundException e) {
            return ServiceClassification.JDK_INVOLVED;
        }
    }

    private ProviderVerification verifyProvider(String providerName, String serviceTypeName,
            IndexView combinedIndex) {
        ClassInfo classInfo = combinedIndex.getClassByName(DotName.createSimple(providerName));
        if (classInfo == null) {
            try {
                Class.forName(providerName, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                return ProviderVerification.CLASS_NOT_FOUND;
            }
            return ProviderVerification.OK;
        }

        if (classInfo.isInterface() || classInfo.isAbstract()) {
            return ProviderVerification.ABSTRACT_OR_INTERFACE;
        }

        boolean hasNoArgCtor = classInfo.constructors().stream()
                .anyMatch(m -> m.parametersCount() == 0 && java.lang.reflect.Modifier.isPublic(m.flags()));
        if (!hasNoArgCtor) {
            return ProviderVerification.NO_PUBLIC_NO_ARG_CONSTRUCTOR;
        }

        ClassInfo serviceInfo = combinedIndex.getClassByName(DotName.createSimple(serviceTypeName));
        if (serviceInfo != null) {
            if (!isAssignable(classInfo, serviceInfo, combinedIndex)) {
                return ProviderVerification.NOT_A_SUBTYPE;
            }
        }

        return ProviderVerification.OK;
    }

    private boolean isAssignable(ClassInfo impl, ClassInfo serviceType,
            IndexView index) {
        if (impl.name().equals(serviceType.name())) {
            return true;
        }

        for (var iface : impl.interfaceNames()) {
            if (iface.equals(serviceType.name())) {
                return true;
            }
            ClassInfo ifaceInfo = index.getClassByName(iface);
            if (ifaceInfo != null && isAssignable(ifaceInfo, serviceType, index)) {
                return true;
            }
        }

        DotName superName = impl.superName();
        if (superName != null && !superName.toString().equals("java.lang.Object")) {
            ClassInfo superInfo = index.getClassByName(superName);
            if (superInfo != null) {
                return isAssignable(superInfo, serviceType, index);
            }
        }

        return false;
    }

    private void generateRegistry(Map<String, ServiceTypeInfo> serviceTable,
            BuildProducer<GeneratedClassBuildItem> generatedClasses) {

        GeneratedClassGizmoAdaptor output = new GeneratedClassGizmoAdaptor(generatedClasses, false);
        int factoryCounter = 0;

        Map<String, List<String>> serviceToFactoryClasses = new LinkedHashMap<>();

        for (ServiceTypeInfo info : serviceTable.values()) {
            if (info.classification != ServiceClassification.APP_ONLY) {
                continue;
            }

            List<String> factoryClassNames = new ArrayList<>();
            for (String providerName : info.providerNames) {
                ProviderVerification verification = info.verifications.get(providerName);
                String factoryClassName = REGISTRY_CLASS + "$Factory_" + factoryCounter++;

                if (verification == ProviderVerification.OK) {
                    generateProviderFactory(output, factoryClassName, providerName, info.serviceTypeName);
                } else {
                    generateErrorFactory(output, factoryClassName, providerName, verification);
                }
                factoryClassNames.add(factoryClassName);
            }
            serviceToFactoryClasses.put(info.serviceTypeName, factoryClassNames);
        }

        generateRegistryClass(output, serviceToFactoryClasses);
    }

    private void generateProviderFactory(io.quarkus.gizmo.ClassOutput output,
            String factoryClassName, String providerName, String serviceTypeName) {
        String providerInternal = providerName.replace('.', '/');

        try (ClassCreator cc = ClassCreator.builder()
                .classOutput(output)
                .className(factoryClassName)
                .superClass(PROVIDER_FACTORY_CLASS)
                .build()) {

            try (MethodCreator ctor = cc.getMethodCreator("<init>", void.class)) {
                ctor.setModifiers(Opcodes.ACC_PUBLIC);
                ctor.invokeSpecialMethod(
                        MethodDescriptor.ofConstructor(PROVIDER_FACTORY_CLASS.replace('/', '.'), Class.class),
                        ctor.getThis(),
                        ctor.loadClass(providerName));
                ctor.returnValue(null);
            }

            try (MethodCreator create = cc.getMethodCreator("create", Object.class)) {
                create.setModifiers(Opcodes.ACC_PROTECTED);
                ResultHandle instance = create.newInstance(ofConstructor(providerName));
                create.returnValue(instance);
            }
        }
    }

    private void generateErrorFactory(io.quarkus.gizmo.ClassOutput output,
            String factoryClassName, String providerName, ProviderVerification verification) {

        String message = switch (verification) {
            case CLASS_NOT_FOUND -> "Provider " + providerName + " not found";
            case NOT_A_SUBTYPE -> providerName + " is not a subtype";
            case NO_PUBLIC_NO_ARG_CONSTRUCTOR ->
                providerName + " Unable to get public no-arg constructor";
            case ABSTRACT_OR_INTERFACE ->
                providerName + " is not a concrete class";
            default -> "Provider " + providerName + " could not be instantiated";
        };

        try (ClassCreator cc = ClassCreator.builder()
                .classOutput(output)
                .className(factoryClassName)
                .interfaces("java/util/ServiceLoader$Provider")
                .build()) {

            cc.getFieldCreator("message", String.class).setModifiers(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL);

            try (MethodCreator ctor = cc.getMethodCreator("<init>", void.class)) {
                ctor.setModifiers(Opcodes.ACC_PUBLIC);
                ctor.invokeSpecialMethod(ofConstructor(Object.class), ctor.getThis());
                ctor.writeInstanceField(FieldDescriptor.of(factoryClassName, "message", String.class),
                        ctor.getThis(), ctor.load(message));
                ctor.returnValue(null);
            }

            try (MethodCreator type = cc.getMethodCreator("type", Class.class)) {
                type.setModifiers(Opcodes.ACC_PUBLIC);
                if (verification == ProviderVerification.CLASS_NOT_FOUND) {
                    ResultHandle sce = type.newInstance(
                            ofConstructor("java.util.ServiceConfigurationError", String.class),
                            type.load(message));
                    type.throwException(sce);
                } else {
                    type.returnValue(type.loadClass(providerName));
                }
            }

            try (MethodCreator get = cc.getMethodCreator("get", Object.class)) {
                get.setModifiers(Opcodes.ACC_PUBLIC);
                ResultHandle msg = get.readInstanceField(
                        FieldDescriptor.of(factoryClassName, "message", String.class), get.getThis());
                ResultHandle sce = get.newInstance(
                        ofConstructor("java.util.ServiceConfigurationError", String.class), msg);
                get.throwException(sce);
            }
        }
    }

    private void generateRegistryClass(io.quarkus.gizmo.ClassOutput output,
            Map<String, List<String>> serviceToFactoryClasses) {

        try (ClassCreator cc = ClassCreator.builder()
                .classOutput(output)
                .className(REGISTRY_CLASS)
                .build()) {

            try (MethodCreator method = cc.getMethodCreator("getProviders", List.class, String.class)) {
                method.setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);

                for (Map.Entry<String, List<String>> entry : serviceToFactoryClasses.entrySet()) {
                    String serviceType = entry.getKey();
                    List<String> factoryClasses = entry.getValue();

                    ResultHandle serviceNameParam = method.getMethodParam(0);
                    ResultHandle comparison = method.invokeVirtualMethod(
                            ofMethod(String.class, "equals", boolean.class, Object.class),
                            method.load(serviceType), serviceNameParam);

                    var ifBlock = method.ifTrue(comparison);
                    var trueBranch = ifBlock.trueBranch();

                    ResultHandle list = trueBranch.newInstance(
                            ofConstructor(ArrayList.class, int.class),
                            trueBranch.load(factoryClasses.size()));

                    for (String factoryClass : factoryClasses) {
                        String factoryBinaryName = factoryClass.replace('/', '.');
                        ResultHandle factory = trueBranch.newInstance(ofConstructor(factoryBinaryName));
                        trueBranch.invokeVirtualMethod(
                                ofMethod(ArrayList.class, "add", boolean.class, Object.class),
                                list, factory);
                    }

                    ResultHandle unmodifiable = trueBranch.invokeStaticMethod(
                            ofMethod(Collections.class, "unmodifiableList", List.class, List.class),
                            list);
                    trueBranch.returnValue(unmodifiable);
                }

                method.returnValue(method.loadNull());
            }
        }
    }

    private void dumpTable(Map<String, ServiceTypeInfo> serviceTable, CurateOutcomeBuildItem curateOutcome) {
        try {
            Path outputDir = curateOutcome.getApplicationModel().getAppArtifact()
                    .getResolvedPaths().getSinglePath().getParent();
            if (outputDir == null) {
                return;
            }
            Path tableFile = outputDir.resolve("quarkus-serviceloader-table.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("# ServiceLoader Short-Circuit Provider Table\n\n");

            for (ServiceTypeInfo info : serviceTable.values()) {
                sb.append(info.classification).append(" ").append(info.serviceTypeName).append("\n");
                for (String provider : info.providerNames) {
                    ProviderVerification v = info.verifications.get(provider);
                    sb.append("  ").append(provider);
                    if (v != null && v != ProviderVerification.OK) {
                        sb.append(" [").append(v).append("]");
                    }
                    sb.append("\n");
                }
            }

            Files.writeString(tableFile, sb.toString());
            LOG.infof("ServiceLoader table written to %s", tableFile);
        } catch (IOException e) {
            LOG.warn("Could not write service loader table file", e);
        }
    }

    private void rewriteCallSites(CurateOutcomeBuildItem curateOutcome,
            BuildProducer<BytecodeTransformerBuildItem> bytecodeTransformers) {

        Set<String> candidateClasses = findCandidateClasses(curateOutcome);

        LOG.infof("ServiceLoader short-circuit: %d candidate classes for call-site rewriting", candidateClasses.size());

        StringBuilder report = new StringBuilder();
        report.append("# ServiceLoader Call-Site Transform Report\n\n");
        int rewritten = 0;
        int skipped = 0;

        for (String className : candidateClasses) {
            bytecodeTransformers.produce(
                    new BytecodeTransformerBuildItem.Builder()
                            .setClassToTransform(className)
                            .setVisitorFunction(new ServiceLoaderCallSiteRewriter())
                            .setRequireConstPoolEntry(Set.of(SERVICE_LOADER_CLASS))
                            .setContinueOnFailure(true)
                            .build());
        }

        try {
            Path outputDir = curateOutcome.getApplicationModel().getAppArtifact()
                    .getResolvedPaths().getSinglePath().getParent();
            if (outputDir != null) {
                Path reportFile = outputDir.resolve("quarkus-serviceloader-transform-report.txt");
                report.append("Candidate classes: ").append(candidateClasses.size()).append("\n");
                report.append("(Detailed per-site report available via build log at DEBUG level)\n");
                Files.writeString(reportFile, report.toString());
            }
        } catch (IOException e) {
            LOG.warn("Could not write transform report", e);
        }
    }

    private Set<String> findCandidateClasses(CurateOutcomeBuildItem curateOutcome) {
        Set<String> candidates = new HashSet<>();
        Set<String> searchFor = Set.of(SERVICE_LOADER_CLASS);

        for (ResolvedDependency dep : curateOutcome.getApplicationModel().getRuntimeDependencies()) {
            dep.getContentTree().walk(visit -> {
                String path = visit.getRelativePath("/");
                if (path.endsWith(".class") && !path.startsWith("META-INF/")) {
                    try {
                        byte[] classBytes = Files.readAllBytes(visit.getPath());
                        if (ConstPoolScanner.constPoolEntryPresent(classBytes, searchFor)) {
                            String className = path.substring(0, path.length() - 6).replace('/', '.');
                            candidates.add(className);
                        }
                    } catch (IOException e) {
                        // skip
                    }
                }
            });
        }

        Path appRoot = curateOutcome.getApplicationModel().getAppArtifact().getResolvedPaths().getSinglePath();
        if (Files.isDirectory(appRoot)) {
            try (var walk = Files.walk(appRoot)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> {
                            try {
                                byte[] classBytes = Files.readAllBytes(p);
                                if (ConstPoolScanner.constPoolEntryPresent(classBytes, searchFor)) {
                                    String relative = appRoot.relativize(p).toString();
                                    String className = relative.substring(0, relative.length() - 6)
                                            .replace('/', '.').replace('\\', '.');
                                    candidates.add(className);
                                }
                            } catch (IOException e) {
                                // skip
                            }
                        });
            } catch (IOException e) {
                // skip
            }
        }

        return candidates;
    }

    /**
     * ASM visitor that rewrites ServiceLoader.load/loadInstalled call sites to use QuarkusServiceLoader.
     * <p>
     * Conservative: only rewrites call sites where every use of the ServiceLoader value is
     * a known-safe method invocation (iterator, stream, findFirst, reload, forEach, spliterator, toString).
     */
    static class ServiceLoaderCallSiteRewriter implements BiFunction<String, ClassVisitor, ClassVisitor> {

        @Override
        public ClassVisitor apply(String className, ClassVisitor downstream) {
            return new ClassVisitor(Opcodes.ASM9, downstream) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new ServiceLoaderMethodRewriter(mv, className, name);
                }
            };
        }
    }

    /**
     * Method-level visitor that performs the actual rewrite.
     * <p>
     * Strategy: for each INVOKESTATIC to ServiceLoader.load* methods, rewrite the owner to
     * QuarkusServiceLoader and the return type to QuarkusServiceLoader. Then rewrite subsequent
     * INVOKEVIRTUAL calls on the returned ServiceLoader to use QuarkusServiceLoader.
     * <p>
     * This is a simplified single-pass rewrite: we rewrite ALL ServiceLoader.load calls and
     * ALL ServiceLoader method calls. The QuarkusServiceLoader shim handles the fallback
     * at runtime, so even if a value escapes to somewhere unexpected, the shim will still work
     * (it implements Iterable and has all the same methods).
     */
    static class ServiceLoaderMethodRewriter extends MethodVisitor {

        private final String className;
        private final String methodName;

        ServiceLoaderMethodRewriter(MethodVisitor delegate, String className, String methodName) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && owner.equals(SERVICE_LOADER_CLASS)) {
                if (name.equals("load") || name.equals("loadInstalled")) {
                    String newDescriptor = descriptor.replace(
                            "Ljava/util/ServiceLoader;",
                            "L" + SHIM_CLASS + ";");
                    LOG.debugf("Rewriting ServiceLoader.%s in %s.%s", name, className, methodName);
                    super.visitMethodInsn(opcode, SHIM_CLASS, name, newDescriptor, false);
                    return;
                }
            }

            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(SERVICE_LOADER_CLASS)) {
                if (REWRITABLE_SL_METHODS.contains(name)) {
                    super.visitMethodInsn(opcode, SHIM_CLASS, name, descriptor, false);
                    return;
                }
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.CHECKCAST && type.equals(SERVICE_LOADER_CLASS)) {
                super.visitTypeInsn(opcode, SHIM_CLASS);
                return;
            }
            super.visitTypeInsn(opcode, type);
        }
    }
}
