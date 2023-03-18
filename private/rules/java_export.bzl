load(":javadoc.bzl", "javadoc")
load(":maven_bom_fragment.bzl", "maven_bom_fragment")
load(":maven_project_jar.bzl", "maven_project_jar")
load(":maven_publish.bzl", "maven_publish")
load(":pom_file.bzl", "pom_file")

def java_export(
        name,
        maven_coordinates,
        deploy_env = [],
        pom_template = None,
        visibility = None,
        tags = [],
        testonly = None,
        extra_artifacts = {},
        **kwargs):
    """Extends `java_library` to allow maven artifacts to be uploaded.

    This macro can be used as a drop-in replacement for `java_library`, but
    also generates an implicit `name.publish` target that can be run to publish
    maven artifacts derived from this macro to a maven repository. The publish
    rule understands the following variables (declared using `--define` when
    using `bazel run`):

      * `maven_repo`: A URL for the repo to use. May be "https" or "file".
      * `maven_user`: The user name to use when uploading to the maven repository.
      * `maven_password`: The password to use when uploading to the maven repository.

    This macro also generates a `name-pom` target that creates the `pom.xml` file
    associated with the artifacts. The template used is derived from the (optional)
    `pom_template` argument, and the following substitutions are performed on
    the template file:

      * `{groupId}`: Replaced with the maven coordinates group ID.
      * `{artifactId}`: Replaced with the maven coordinates artifact ID.
      * `{version}`: Replaced by the maven coordinates version.
      * `{type}`: Replaced by the maven coordinates type, if present (defaults to "jar")
      * `{scope}`: Replaced by the maven coordinates type, if present (defaults to "compile")
      * `{dependencies}`: Replaced by a list of maven dependencies directly relied upon
        by java_library targets within the artifact.

    The "edges" of the artifact are found by scanning targets that contribute to
    runtime dependencies for the following tags:

      * `maven_coordinates=group:artifact:type:version`: Specifies a dependency of
        this artifact.
      * `maven:compile_only`: Specifies that this dependency should not be listed
        as a dependency of the artifact being generated.

    To skip generation of the javadoc jar, add the `no-javadocs` tag to the target.

    Generated rules:
      * `name`: A `java_library` that other rules can depend upon.
      * `name-docs`: A javadoc jar file.
      * `name-pom`: The pom.xml file.
      * `name.publish`: To be executed by `bazel run` to publish to a maven repo.

    Args:
      name: A unique name for this target
      maven_coordinates: The maven coordinates for this target.
      pom_template: The template to be used for the pom.xml file.
      deploy_env: A list of labels of Java targets to exclude from the generated jar.
        [`java_binary`](https://bazel.build/reference/be/java#java_binary) targets are *not*
        supported.
      visibility: The visibility of the target
      kwargs: These are passed to [`java_library`](https://bazel.build/reference/be/java#java_library),
        and so may contain any valid parameter for that rule.
    """

    maven_coordinates_tags = ["maven_coordinates=%s" % maven_coordinates]
    lib_name = "%s-lib" % name

    javadocopts = kwargs.pop("javadocopts", [])

    # Construct the java_library we'll export from here.
    native.java_library(
        name = lib_name,
        visibility = visibility,
        tags = tags + maven_coordinates_tags,
        testonly = testonly,
        **kwargs
    )

    maven_export(
        name,
        maven_coordinates,
        maven_coordinates_tags,
        deploy_env,
        pom_template,
        visibility,
        tags,
        testonly,
        lib_name,
        javadocopts,
        extra_artifacts
    )

def maven_export(
        name,
        maven_coordinates,
        maven_coordinates_tags,
        deploy_env,
        pom_template,
        visibility,
        tags,
        testonly,
        lib_name,
        javadocopts,
        extra_artifacts = {}):
    """Helper rule to reuse this code for both java_export and kt_jvm_export.

    After a library has already been created (either a kt_jvm_library or
    java_library) this rule will create the maven jar and pom files and publish
    them.

    All arguments are the same as java_export with the addition of:
      lib_name: Name of the library that has been built.
      javadocopts: The options to be used for javadocs.

    """

    deploy_env = deploy_env if deploy_env else []

    # Merge the jars to create the maven project jar
    maven_project_jar(
        name = "%s-project" % name,
        target = ":%s" % lib_name,
        deploy_env = deploy_env,
        visibility = visibility,
        tags = tags + maven_coordinates_tags,
        testonly = testonly,
    )

    native.filegroup(
        name = "%s-maven-artifact" % name,
        srcs = [
            ":%s-project" % name,
        ],
        output_group = "maven_artifact",
        visibility = visibility,
        tags = tags,
        testonly = testonly,
    )

    native.filegroup(
        name = "%s-maven-source" % name,
        srcs = [
            ":%s-project" % name,
        ],
        output_group = "maven_source",
        visibility = visibility,
        tags = tags,
        testonly = testonly,
    )

    docs_jar = None
    if not "no-javadocs" in tags:
        docs_jar = "%s-docs" % name
        javadoc(
            name = docs_jar,
            deps = [
                ":%s-project" % name,
            ] + deploy_env,
            javadocopts = javadocopts,
            visibility = visibility,
            tags = tags,
            testonly = testonly,
        )

    pom_file(
        name = "%s-pom" % name,
        target = ":%s" % lib_name,
        pom_template = pom_template,
        visibility = visibility,
        tags = tags,
        testonly = testonly,
    )

    maven_publish(
        name = "%s.publish" % name,
        coordinates = maven_coordinates,
        pom = "%s-pom" % name,
        javadocs = docs_jar,
        artifact = ":%s-maven-artifact" % name,
        source_jar = ":%s-maven-source" % name,
        visibility = visibility,
        tags = tags,
        testonly = testonly,
        extra_artifacts = extra_artifacts
    )

    # We may want to aggregate several `java_export` targets into a single Maven BOM POM
    # https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#bill-of-materials-bom-poms
    maven_bom_fragment(
        name = "%s.bom-fragment" % name,
        maven_coordinates = maven_coordinates,
        artifact = ":%s" % lib_name,
        src_artifact = ":%s-maven-source" % name,
        javadoc_artifact = None if "no-javadocs" in tags else ":%s-docs" % name,
        pom = ":%s-pom" % name,
        testonly = testonly,
        tags = tags,
        visibility = visibility,
    )

    # Finally, alias the primary output
    native.alias(
        name = name,
        actual = ":%s-project" % name,
        visibility = visibility,
        tags = tags,
        testonly = testonly,
    )
