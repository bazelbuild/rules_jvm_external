# Using rules_jvm_external with bzlmod

Bzlmod is the package manager for Bazel modules and is required starting with Bazel 7.

## Installation

Add the following to your `MODULE.bazel` file, setting the `version` to the latest one
available on https://registry.bazel.build/modules/rules_jvm_external:

```starlark
bazel_dep(name = "rules_jvm_external", version = "...")
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        # This line is an example coordinate, you'd copy-paste your actual dependencies here
        # from your build.gradle or pom.xml file.
        "org.seleniumhq.selenium:selenium-java:4.4.0",
    ],
)

# You can split off individual artifacts to define artifact-specific options (this example sets `neverlink`).
# The `maven.install` and `maven.artifact` tags will be merged automatically.
maven.artifact(
    artifact = "javapoet",
    group = "com.squareup",
    neverlink = True,
    version = "1.11.1",
)

use_repo(maven, "maven")
```

Now you can run the `@maven//:pin` program to create a JSON lockfile of the transitive dependencies,
in a format that rules_jvm_external can use later. You'll check this file into the repository.

```sh
$ bazel run @maven//:pin
```

Ignore the instructions printed at the end of the output from this command, as they aren't updated
for bzlmod yet. See [#836](https://github.com/bazelbuild/rules_jvm_external/issues/836)

Due to [#835](https://github.com/bazelbuild/rules_jvm_external/issues/835) this creates a file with
a longer name than it should, so we rename it:

```sh
$ mv rules_jvm_external~4.5~maven~maven_install.json maven_install.json
```

Now that this file exists, we can update the `MODULE.bazel` to reflect that we pinned the
dependencies.

Add a `lock_file` attribute to the `maven.install()` call like so:

```starlark
maven.install(
    ...
    lock_file = "//:maven_install.json",
)
```

Now you'll be able to use the same `REPIN=1 bazel run @maven//:pin` operation described in the
[README](/README.md#updating-maven_installjson) to update the dependencies.

## Extension and tag documentation

The extension and tag documentation can be found [in this document](bzlmod-api.md).

## Declaring dependencies in files

It is possible to use a
gradle [version catalog](https://docs.gradle.org/current/userguide/version_catalogs.html)
to declare dependencies. These should be declared in a `libs.versions.toml` file, and can be
imported to your bazel project by using the `from_toml` tag:

```starlark
maven.from_toml(
    libs_versions_toml = "//gradle:libs.versions.toml",
)
```

An example `libs.versions.toml` file could look like:

```toml
[versions]
junitJupiter = "5.12.2"

[libraries]
guava = { module = "com.google.guava:guava" }
guavaBom = { module = "com.google.guava:guava-bom", version = "33.4.8-jre" }
junitApi = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junitJupiter" }
```

#### Extensions to the Gradle version catalog format

`rules_jvm_external` supports several additional fields on library entries beyond the
standard Gradle version catalog format. These must be specified as quoted strings within
the inline table:

| Field | Example | Description |
|-------|---------|-------------|
| `classifier` | `classifier = "all"` | Maven classifier for the artifact |
| `exclusions` | `exclusions = "['com.example:unwanted']"` | JSON-encoded list of `group:artifact` exclusions |
| `force_version` | `force_version = "true"` | Pins this version, ignoring higher versions from transitive deps |
| `is_bom` | `is_bom = "true"` | Treats this entry as a BOM instead of a regular artifact |
| `package` | `package = "aar"` | Packaging type (default is `jar`) |

For example:

```toml
[libraries]
guavaBom = { module = "com.google.guava:guava-bom", version = "33.4.8-jre", is_bom = "true" }
guava = { module = "com.google.guava:guava" }
clickhouse = { module = "com.clickhouse:clickhouse-jdbc", version = "0.9.2", classifier = "all", force_version = "true" }
misk = { module = "com.squareup.misk:misk-core", version = "1.0.0", exclusions = "['*:*']" }
```

### Declaring BOMs from external files

This can be done by using the `bom_modules` attribute of the `from_toml` tag. This is
a list of gradle modules, matching the `module` in the `libs.versions.toml` file. We
can change our module declaration like so to correctly use the guava bom:

```starlark
maven.from_toml(
    libs_versions_toml = "//gradle:libs.versions.toml",
    bom_modules = [
        "com.google.guava:guava-bom",
    ],
)
```

## Artifact exclusion

The non-bzlmod instructions for how to configure
`exclusions` [from the README](../README.md#artifact-exclusion)
don't work as shown for bzlmod; it's not possible to "inline" them as shown (it will cause an `ERROR: in tag at
<root>/MODULE.bazel:22:14, error converting value for attribute artifacts: expected value of type 'string' for
element 9 of artifacts, but got None (NoneType)`). Split it like this instead:

```starlark
# https://github.com/grpc/grpc-java/issues/10576
maven.artifact(
    artifact = "grpc-core",
    exclusions = ["io.grpc:grpc-util"],
    group = "io.grpc",
    version = "1.58.0",  # Keep version in sync with below!
)
maven.install(
    artifacts = [
        "junit:junit:4.13.2",
        ...
```

Alternatively, you can use the mechanism outlined below to add exclusions.

## Modifying artifact declarations

Because artifacts are not always declared in the module file, `rules_jvm_external` offers
a mechanism for modifying artifacts that are declared elsewhere (eg. in an `install` or a
`from_toml` tag). This is done using the `amend_artifact` tag:

```starlark
maven.amend_artifact(
    coordinates = "io.grpc:grpc-core",
    exclusions = ["io.grpc:grpc-util"],
)
```

When matching artifacts that have been declared, only the `group:artifact` tuple is used
for matching.

## Module dependency layering

The extension collects declarations from all tags with the same `name` before resolving them. Each
name is an independent Maven repository namespace. Declarations in one namespace never affect
another namespace.

The root module and its dependencies have different roles during layering. The root contributes
the declarations that belong to the current Bazel project. Every other module is a non-root
contributor. Coordinates are conceptually matched by `group:artifact:packaging:classifier`,
meaning that a classified JAR layers independently of its unclassified JAR.

When performing [duplicate coordinate checks](#diagnostics), the declarations are keyed by
`group:artifact:classifier`, but not packaging. Packaging-distinct declarations at different
versions can therefore warn or fail even though the extension layers them independently. It also
continues to check multiple root declarations. Ordinary cross-module conflicts with the same
layering key no longer reach this check.

### Version precedence

For conflicts between modules, layering selects one complete declaration for each coordinate. The
selected declaration supplies its exclusions, `neverlink`, `testonly`, `force_version`, packaging,
classifier, and other fields. Fields from discarded declarations are not merged into it. Layering
does not deduplicate within the root module, so repeated root declarations for one coordinate reach
the existing repository-level duplicate check, which warns or fails according to
`duplicate_version_warning`. Forcing is the exception: if any module, the root included, sets
`force_version` on the same coordinate at two different versions, layering will fail with an
error message.

The surviving declaration is chosen by these rules:

1. A forced version in the root module always wins.
2. Otherwise, a forced declaration beats any unforced one, whatever the versions.
3. Otherwise, the highest version wins, regardless of which module declared it.

On ties and conflicts:

- Two non-root modules that force different versions is an error and fails before resolution. The root can settle it by forcing the version itself.
- On a tie (equal versions, or the same forced version from more than one module) the first module's declaration is kept; the root counts as first.
- A non-root artifact marked `testonly` is dropped.

Be aware that non-default packaging and classifiers remain independent of each other and of the
plain versioned coordinate. This may lead to some surprises when resolution is complete.

"Highest" uses the Maven `ComparableVersion` ordering implemented by
`private/rules/maven_version.bzl`, not lexical string ordering.

`version_conflict_policy = "pinned"` changes this interaction. For the Gradle and Maven resolvers,
root artifacts are marked as `force_version` before layering. The duplicate-force check applies to
declared forces before this policy is applied. Maven then marks every versioned root declaration.
Gradle first selects one version for each root `group:artifact`: an unclassified declaration takes
precedence over classified declarations, and Maven `ComparableVersion` order selects among
declarations with the same classification status. Every root declaration for that module at the
selected version is then marked forced, including classified declarations. The root consequently
wins because it now forces the coordinate. For Coursier, layering is unchanged and the one
surviving direct version is later passed as a `--force-version` argument. A higher non-root
version can therefore displace the root under Coursier and then be pinned.

The `force_version` flag can be set by an `artifact` tag, an `amend_artifact` tag, or a regular
artifact read by `from_toml`. Coordinates in `install.artifacts` cannot carry the flag. BOMs use the
same extension-layer precedence rules as artifacts.

### Contributors and configuration

When the root and other modules contribute artifacts to the same namespace, the extension prints a
message such as:

`The maven repository 'multiple_lock_files' has contributions from multiple bzlmod modules, and will be resolved together: ["bzlmod_lock_files", "rules_jvm_external"]`

If those contributions are expected, set `known_contributing_modules` on the root `install` tag.
The warning includes the value to add. Once this attribute is non-empty, only listed modules may
contribute artifacts or BOMs to that namespace. A module that contributes only BOMs triggers the
same contribution warning and can be acknowledged through the same attribute.

After dependencies are layered, scalar `install` attributes from the root module take precedence.
List attributes are combined root-first, while preserving their existing deduplication or
concatenation behaviour.

The default namespace is `maven`. A module intended for use through `bazel_dep` should normally use
its own name, such as the `rules_jvm_external_deps` namespace used by this project. The default is
appropriate when a module deliberately contributes functionality that would otherwise be supplied
as a Maven dependency, or when the project is only used as the root module.

### <a id="diagnostics"></a>Diagnostics

Layering keeps the following diagnostics so that unexpected versions can be traced to their
contributing module. Each entry shows the message a user sees and how to resolve it. Several are
governed by [`duplicate_version_warning`](bzlmod-api.md#maven.install-duplicate_version_warning),
which is `"error"` to fail, `"warn"` (the default) to print and continue, or `"none"` to stay
silent.

#### Which modules are contributing to this repository?

An unacknowledged non-root module contributing artifacts or BOMs always prints the contribution
warning:

```
The maven repository 'my-project' has contributions from multiple bzlmod modules, and will be resolved together: ["my-project", "some-other-module"]
```

**Remedy:** if the contributions are expected, set `known_contributing_modules` on the root
`install` tag to the module names in the message; otherwise remove the contributing module. When
`known_contributing_modules` instead excludes a contributor, an `INFO` message is printed when
`RJE_VERBOSE` is set.

#### Why is my forced version rejected?

One module forcing the same coordinate at two different versions fails:

```
Module 'my_module' forces dependency 'com.google.guava:guava' at different versions: 31.1-jre and 33.0.0-jre.
```

**Remedy:** keep a single version for the coordinate within that module.

Non-root modules forcing different versions of a coordinate that the root does not force fails with:

```
Conflicting forced versions for dependency 'com.google.guava:guava': module_a wants 31.1-jre, module_b wants 33.0.0-jre. Add an `artifact` tag to the root module at the version you want and set `force_version = True`.
```

**Remedy:** add an `artifact` tag to the root module at the version you want and set
`force_version = True` on it.

#### Which version will be selected?

When layering selects a version different from the root version, the version-selection warning is:

```
WARNING: For dependency 'com.google.protobuf:protobuf-java' the root @maven repo wants version 3.25.5, but got 4.27.2 from the bazel_worker_java bazel dep. Please update the version in your MODULE.bazel or set `force_version = True`.
```

`duplicate_version_warning` controls whether this warns, fails, or stays silent.

**Remedy:** update the version in the root module to the highest version, or set
`force_version = True` in the root module to ensure that version is the one used
in dependency resolution.

You only see this when the version that ends up being used differs from the one declared in your
root module. For example, a `bazel_dep` may pull in a higher version of a dependency you also
declare in the root. If the resolved version already matches your root declaration, there is
nothing to act on and no warning is printed. Coordinates that only a `bazel_dep` declares (and
your root does not) do not produce this warning either; they are covered by the contribution
warning above instead.

#### Which versions are reaching the repository?

When more than one version of the same dependency makes it into the repository, whether declared
twice in one module or contributed by several modules, the message is:

```
Found duplicate artifact versions
    com.google.guava:guava has multiple versions 31.1-jre, 33.0.0-jre
Please remove duplicate artifacts from the artifact list so you do not get unexpected artifact versions
```

`duplicate_version_warning` controls whether this warns, fails, or stays silent. **Remedy:** remove
duplicate artifacts from the artifact list.

A non-root-only coordinate is reported as an `INFO` message when a repin variable and `RJE_VERBOSE`
are both set:

```
INFO: The @maven repo is getting the additional artifact com.google.guava:guava:33.0.0-jre from the module_a bazel dep.
```

## Known issues

- Some error messages print instructions that don't apply under bzlmod,
  e.g. https://github.com/bazelbuild/rules_jvm_external/issues/827
