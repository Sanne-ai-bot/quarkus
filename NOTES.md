# ServiceLoader Short-Circuit POC — Orientation & Notes

## Key Classes Found

### Bytecode Transformation Pipeline
- `BytecodeTransformerBuildItem`: `core/deployment/.../builditem/BytecodeTransformerBuildItem.java`
  - MultiBuildItem; `visitorFunction: BiFunction<String, ClassVisitor, ClassVisitor>`
  - `requireConstPoolEntry` optimization: only apply if const pool has matching string
  - Applied by `ClassTransformingBuildStep`; output goes to `transformed-bytecode.jar`
- `QuarkusClassWriter`: `core/deployment/.../QuarkusClassWriter.java`
  - Only overrides `getClassLoader()` → TCCL; does NOT override `getCommonSuperClass()`
  - Used with `COMPUTE_FRAMES | COMPUTE_MAXS`
- `ConstPoolScanner`: `core/deployment/.../index/ConstPoolScanner.java`
  - Fast binary scan of constant pool UTF8 entries (no CONSTANT_Class check)

### RunnerClassLoader (production classloader)
- `RunnerClassLoader`: `independent-projects/bootstrap/runner/.../RunnerClassLoader.java`
  - Child-first classloading
  - `fullyIndexedDirectories`: hardcoded `["", "META-INF", "META-INF/services"]`
  - `fullyIndexedResourcesIndexMap`: direct mapping from resource name → `ClassLoadingResource[]`
  - `META-INF/services/<type>` lookups go straight to `fullyIndexedResourcesIndexMap`
  - Resource ordering = classpath order from `quarkus-application.dat`
- `SerializedApplication`: same package; `write()` serializes classpath order

### Classpath Order in Fast-Jar (from AbstractFastJarBuilder)
1. `transformed-bytecode.jar` (first, if present)
2. `generated-bytecode.jar`
3. Application jar (runner jar)
4. Dependencies sorted by path (`Collections.sort(sortedDeps)`)

### Service Provider Handling
- `ServiceProviderBuildItem`: `core/deployment/.../builditem/nativeimage/ServiceProviderBuildItem.java`
  - For native image only; carries `serviceInterface` + `providers` list
- `ServiceUtil`: `core/deployment/.../util/ServiceUtil.java`
  - `classNamesNamedIn(ClassLoader, String)` → `Set<String>` (LinkedHashSet, deduped)
  - `classNamesNamedIn(Path)` → `Set<String>`
  - Strips `#` comments, trims whitespace
- `GeneratedClassBuildItem`: carries `name`, `classData` (byte[]), `applicationClass` flag
- `GeneratedServiceProviderBuildItem`: `serviceInterfaceName` + `implementationClassName`

### Config & Package Type
- `BootstrapConfig`: `core/deployment/.../BootstrapConfig.java`
  - `@ConfigMapping(prefix = "quarkus.bootstrap")`, `@ConfigRoot(phase = BUILD_TIME)`
  - Add new property here: `serviceLoaderShortCircuit()`
- `PackageConfig.JarConfig.JarType`: FAST_JAR, AOT_JAR, UBER_JAR, MUTABLE_JAR, LEGACY_JAR
  - Check with `packageConfig.jar().type()`
- `CurateOutcomeBuildItem` → `getApplicationModel()` → `getRuntimeDependencies()`

### Integration Test Target
- `integration-tests/jpa-h2/`: Hibernate ORM + JDBC H2 + Quarkus REST (quarkus-rest)
  - No external DB required; good for measurement

## Surprises / Notes
- `ConstPoolScanner` only checks UTF8 entries, not CONSTANT_Class refs (comment says so)
- `QuarkusClassWriter` does NOT override `getCommonSuperClass()` — relies on TCCL having all classes
- Dependencies in fast-jar are sorted by Path (`Collections.sort`), which is lexicographic
- `META-INF/services` is a "fully indexed directory" in RunnerClassLoader — direct map lookup, not scanning

## Implementation Architecture

### Runtime shim: `QuarkusServiceLoader<S>`
- `core/runtime/.../serviceloader/QuarkusServiceLoader.java`
- Drop-in for `java.util.ServiceLoader` with identical API
- Checks classloader identity at construction: if TCCL is `RunnerClassLoader`, consults registry
- Falls back to real `ServiceLoader` for unknown types, JDK types, foreign classloaders
- Recording mode: `-Dquarkus.serviceloader.record=<file>` logs every fallback with reason
- Verification mode: `-Dquarkus.serviceloader.verify=true` cross-checks registry vs real SL

### Generated provider factories: `GeneratedProviderFactory<S>`
- `core/runtime/.../serviceloader/GeneratedProviderFactory.java` (base class)
- Each generated factory subclass has `create()` with `new ProviderClass()` bytecode
- `type()` returns class without instantiation; `get()` instantiates + caches
- Error factories for broken providers: `ServiceConfigurationError` on `get()` matching JDK wording

### Registry: `GeneratedServiceLoaderRegistry` (Gizmo-generated)
- `getProviders(String serviceTypeName)` → `List<ServiceLoader.Provider<?>>` or null
- Linear string comparison dispatch (acceptable for POC; ~50-100 service types)
- Factory classes named `GeneratedServiceLoaderRegistry$Factory_N`

### Build step: `ServiceLoaderShortCircuitProcessor`
- `core/deployment/.../serviceloader/ServiceLoaderShortCircuitProcessor.java`
- Guarded by `quarkus.bootstrap.service-loader-short-circuit=true` (default false)
- Phase 1: collects META-INF/services via ContentTree.walk + classloader enumeration
- Phase 1: classifies types (APP_ONLY vs JDK_INVOLVED), verifies providers via Jandex
- Phase 1: generates registry and factory classes via Gizmo
- Phase 3: finds candidate classes via ConstPoolScanner, registers BytecodeTransformerBuildItem
- Phase 3: ASM rewriter changes INVOKESTATIC/INVOKEVIRTUAL owner + descriptor + frame types

### Call-site rewriter strategy
- Two-pass inputTransformer: analysis pass detects rewritable sites, rewrite pass transforms them
- Only rewrites ServiceLoader.load calls where the result is DIRECTLY consumed on the stack
  by a safe method (iterator, stream, findFirst, reload, forEach, spliterator, toString)
- Call sites where the value is stored to a local (ASTORE), passed as a method argument,
  stored to a field, or returned are left untouched — avoids VerifyError since ServiceLoader is final
- Analysis pass uses SKIP_DEBUG|SKIP_FRAMES for speed; if no rewritable sites found, returns
  original bytes unchanged (no COMPUTE_FRAMES, avoiding frame recomputation errors)
- Common pattern: `for (Foo f : ServiceLoader.load(Foo.class))` compiles to
  `load → iterator` (direct consumption), so most for-each loops are rewritten
- Pattern NOT rewritten: `ServiceLoader<X> sl = ServiceLoader.load(X.class); doSomething(sl);`
  — the ASTORE between load and use makes it non-rewritable

## Open Questions
- Ordering: build step collects service files via TCCL enumeration which may not exactly match
  RunnerClassLoader ordering (transformed-bytecode.jar, generated-bytecode.jar come first in
  fast-jar but the build TCCL is QuarkusClassLoader). For a POC this is acceptable; a production
  version should reconstruct the exact fast-jar ordering.
- The `ContentTree.walk` approach for finding candidate classes reads every .class file in every
  dependency jar, which could be slow for very large classpaths. An alternative would be to scan
  only the constant pool index that Quarkus already builds, but the combined index doesn't cover
  all runtime jars.
- Thread safety: the shim's `cachedProviders` field is not volatile. This is acceptable because
  ServiceLoader iterators are not thread-safe in the JDK either, and our usage pattern (one
  QuarkusServiceLoader per call site) means concurrent access is unlikely.

## Phase 0 & 5: Measurements (jpa-h2 integration test)

### Build-time (Quarkus augmentation)
- Baseline (flag off): 1349ms
- With short-circuit (flag on): 1585ms (+236ms build overhead)
  - 40 APP_ONLY service types short-circuited
  - 6 JDK_INVOLVED types (fallback to real ServiceLoader)
  - 54 candidate classes scanned for call-site rewriting

### Call-site rewriting coverage
- Direct-consumption pattern (`ServiceLoader.load(X.class).iterator()` etc.): rewritten
- Store-then-use pattern (`sl = ServiceLoader.load(X.class); for(x:sl)`): NOT rewritten (safe skip)
- Method argument pattern (`helper(ServiceLoader.load(...))`): NOT rewritten (would cause VerifyError)
- Escape patterns (field store, return): NOT rewritten (correct behavior)

### Runtime
- jpa-h2 `JPAFunctionalityTest` passes with flag enabled (no VerifyError, no regressions)
- Note: boot-time speedup from registry lookups is modest for jpa-h2 (ServiceLoader is not the
  dominant boot cost for this app). The benefit is more significant for apps with many ServiceLoader
  calls during bootstrap, particularly those loading Hibernate/SmallRye/MicroProfile SPIs.
