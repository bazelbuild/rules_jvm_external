load("@io_bazel_stardoc//stardoc:stardoc.bzl", "stardoc")
load("@bazel_skylib//:bzl_library.bzl", "bzl_library")

exports_files(["defs.bzl"])

licenses(["notice"])  # Apache 2.0

exports_files(
    [
        "docs/includes/main_functions_header.md",
        "docs/includes/spec_functions_header.md",
    ],
    visibility = ["//scripts:__pkg__"],
)

stardoc(
    name = "defs",
    out = "defs.md",
    input = "defs.bzl",
    symbol_names = [
        "javadoc",
        "java_export",
        "maven_bom",
        "maven_install",
    ],
    visibility = ["//scripts:__pkg__"],
    deps = ["//:implementation"],
)

stardoc(
    name = "specs",
    out = "specs.md",
    input = "specs.bzl",
    symbol_names = [
        "maven.artifact",
        "maven.repository",
        "maven.exclusion",
    ],
    visibility = ["//scripts:__pkg__"],
    deps = ["//:implementation"],
)

bzl_library(
    name = "implementation",
    srcs = [
        ":coursier.bzl",
        ":defs.bzl",
        ":specs.bzl",
        "//private:artifact_utilities.bzl",
        "//private:constants.bzl",
        "//private:coursier_utilities.bzl",
        "//private:dependency_tree_parser.bzl",
        "//private:java_utilities.bzl",
        "//private:proxy.bzl",
        "//private/rules:artifact.bzl",
        "//private/rules:has_maven_deps.bzl",
        "//private/rules:java.bzl",
        "//private/rules:java_export.bzl",
        "//private/rules:javadoc.bzl",
        "//private/rules:jetifier.bzl",
        "//private/rules:jetifier_maven_map.bzl",
        "//private/rules:jvm_import.bzl",
        "//private/rules:maven_bom.bzl",
        "//private/rules:maven_bom_fragment.bzl",
        "//private/rules:maven_install.bzl",
        "//private/rules:maven_project_jar.bzl",
        "//private/rules:maven_publish.bzl",
        "//private/rules:maven_utils.bzl",
        "//private/rules:pom_file.bzl",
        "//private/rules:urls.bzl",
        "//private/rules:v1_lock_file.bzl",
        "//private/rules:v2_lock_file.bzl",
        "//settings:stamp_manifest.bzl",
    ],
    visibility = [
        # This library is only visible to allow others who depend on
        # `rules_jvm_external` to be able to document their code using
        # stardoc.
        "//visibility:public",
    ],
)

alias(
    name = "generate_api_reference",
    actual = "//scripts:generate_api_reference",
)
