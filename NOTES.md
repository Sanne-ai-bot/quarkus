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

## Phase 0: Baseline Measurements
(To be filled after measurement)

## Phase 5: Final Measurements
(To be filled after implementation)
