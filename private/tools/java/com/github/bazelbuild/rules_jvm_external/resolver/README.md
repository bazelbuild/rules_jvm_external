# Java Dependency Resolver

This package contains a Java dependency resolver, which supports two different
resolution mechanisms, one based on Maven's resolver, and one based on
Gradle's.

To understand how this works, please take a look at either the 
[MavenResolver](maven/MavenResolver.java) or
[GradleResolver](gradle/GradleResolver.java).

When executed, the resolver will output a lock file suitable for use with
`rules_jvm_external` to `stdout`

## Examples

### Resolve a simple set of coordinates

`bazel run private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/cmd:Main -- com.google.guava:guava:31.1-jre`

### Pick a different resolver

By default, the resolver will use the Gradle resolver. To change this, use 
the `--resolver` flag. This accepts either `gradle` or `maven`.

`bazel run private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/cmd:Main -- --resolver maven com.google.guava:guava:31.1-jre`

### Use BOMs to allow for "versionless" dependencies

With the Java community, BOM files are often used to group dependencies
together. These specify version numbers for dependencies, which can then
be included in _other_ `pom.xml` files without the version number.

Use the `--bom` flag to list the BOMs that should be used.

`bazel run private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/cmd:Main -- --bom com.google.cloud:spring-cloud-gcp-dependencies:3.4.0 com.google.cloud:google-cloud-pubsub`
