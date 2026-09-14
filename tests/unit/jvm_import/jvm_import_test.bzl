# Copyright 2019 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
This module contains a test suite for testing jvm_import
"""

load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load("@package_metadata//providers:package_metadata_info.bzl", "PackageMetadataInfo")
load("@rules_license//rules:gather_metadata.bzl", "gather_metadata_info")
load("@rules_license//rules:providers.bzl", "PackageInfo")
load("@rules_license//rules_gathering:gathering_providers.bzl", "TransitiveMetadataInfo")

TagsInfo = provider(
    doc = "Provider to propagate jvm_import's tags for testing purposes",
    fields = {
        "tags": "tags to be propagated for jvm_import's tests",
    },
)

def _tags_propagator_impl(target, ctx):
    tags = getattr(ctx.rule.attr, "tags")
    return TagsInfo(tags = tags)

tags_propagator = aspect(
    doc = "Aspect that propagates tags to help with testing jvm_import",
    attr_aspects = ["deps"],
    implementation = _tags_propagator_impl,
)

PackageMetadataInfoCollectionInfo = provider(
    doc = "Provider to propagate jvm_import's applicable_licenses for testing purposes",
    fields = {
        "info": "A PackageMetadataInfo provider from jvm_import's applicable_licenses for testing purposes",
    },
)

def _package_metadata_info_propagator_impl(target, ctx):
    direct = getattr(ctx.rule.attr, "package_metadata", None)
    if not direct:
        direct = getattr(ctx.rule.attr, "applicable_licenses")

    infos = [t[PackageMetadataInfo] for t in direct if PackageMetadataInfo in t]
    for t in direct:
        if PackageMetadataInfo not in t:
            continue

        return [
            PackageMetadataInfoCollectionInfo(
                info = t[PackageMetadataInfo],
            ),
        ]

    return []

package_metadata_info_propagator = aspect(
    doc = "Aspect that propagates applicable_licenses to help with testing jvm_import",
    attr_aspects = [],
    implementation = _package_metadata_info_propagator_impl,
)

DepsInfo = provider(
    doc = "Provider to propagate jvm_import's deps for testing purposes",
    fields = {
        "deps": "deps to be propagated for jvm_import's tests",
    },
)

def _deps_propagator_impl(target, ctx):
    raw_deps = getattr(ctx.rule.attr, "deps")
    deps = []
    for dep in raw_deps:
        deps.append(str(dep.label))
    return DepsInfo(deps = deps)

deps_propagator = aspect(
    doc = "Aspect that propagates deps to help with testing jvm_import",
    attr_aspects = ["deps"],
    implementation = _deps_propagator_impl,
)

def _does_jvm_import_have_tags_impl(ctx):
    env = analysistest.begin(ctx)

    expected_tags = [
        "maven_coordinates=com.google.code.findbugs:jsr305:3.0.2",
        "maven_repository=https://repo1.maven.org/maven2",
        "maven_sha256=766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
        "maven_url=https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar",
    ]

    asserts.equals(env, expected_tags, ctx.attr.src[TagsInfo].tags)
    return analysistest.end(env)

does_jvm_import_have_tags_test = analysistest.make(
    _does_jvm_import_have_tags_impl,
    attrs = {
        "src": attr.label(
            doc = "Target to traverse for tags",
            aspects = [tags_propagator],
            mandatory = True,
        ),
    },
)

def _does_jvm_import_exclude_exclusions_in_deps_test_impl(ctx):
    env = analysistest.begin(ctx)

    expected_tags = [
        "maven_coordinates=com.google.guava:guava:31.1-jre",
        "maven_exclusion=com.google.errorprone:error_prone_annotations",
        "maven_repository=https://repo1.maven.org/maven2",
        "maven_sha256=a42edc9cab792e39fe39bb94f3fca655ed157ff87a8af78e1d6ba5b07c4a00ab",
        "maven_url=https://repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar",
    ]

    asserts.equals(env, expected_tags, ctx.attr.src[TagsInfo].tags)

    expected_deps = [
        "@@_main~maven~pom_exclusion_testing_coursier//:com_google_code_findbugs_jsr305",
        "@@_main~maven~pom_exclusion_testing_coursier//:com_google_guava_failureaccess",
        "@@_main~maven~pom_exclusion_testing_coursier//:com_google_guava_listenablefuture",
        "@@_main~maven~pom_exclusion_testing_coursier//:com_google_j2objc_j2objc_annotations",
        "@@_main~maven~pom_exclusion_testing_coursier//:org_checkerframework_checker_qual",
    ]
    asserts.equals(env, expected_deps, ctx.attr.src[DepsInfo].deps)
    return analysistest.end(env)

does_jvm_import_exclude_exclusions_in_deps_test = analysistest.make(
    _does_jvm_import_exclude_exclusions_in_deps_test_impl,
    attrs = {
        "src": attr.label(
            doc = "Target to traverse for tags and deps",
            aspects = [tags_propagator, deps_propagator],
            mandatory = True,
        ),
    },
)

def _does_jvm_import_have_applicable_licenses_impl(ctx):
    env = analysistest.begin(ctx)

    asserts.true(env, PackageMetadataInfoCollectionInfo in ctx.attr.src)
    info = ctx.attr.src[PackageMetadataInfoCollectionInfo]
    asserts.true(env, info.info)

    return analysistest.end(env)

does_jvm_import_have_applicable_licenses_test = analysistest.make(
    _does_jvm_import_have_applicable_licenses_impl,
    attrs = {
        "src": attr.label(
            doc = "Target to traverse for applicable_licenses",
            aspects = [package_metadata_info_propagator],
            mandatory = True,
        ),
    },
)

def _does_jvm_import_export_a_package_provider_impl(ctx):
    env = analysistest.begin(ctx)

    asserts.true(env, PackageInfo in ctx.attr.src)
    package_info = ctx.attr.src[PackageInfo]
    asserts.equals(env, "pkg:maven/com.google.code.findbugs/jsr305@3.0.2", package_info.purl)

    # The metadata is applied directly to the target in this case, so there should
    # not be any transitive metadata. Apparently.
    # TODO: restore once https://github.com/bazelbuild/rules_license/issues/154 is resolved
    #    metadata_info = ctx.attr.src[TransitiveMetadataInfo]
    #    asserts.equals(env, depset(), metadata_info.package_info)

    return analysistest.end(env)

does_jvm_import_export_a_package_provider_test = analysistest.make(
    _does_jvm_import_export_a_package_provider_impl,
    attrs = {
        "src": attr.label(
            doc = "Target to traverse for providers",
            aspects = [gather_metadata_info],
            mandatory = True,
        ),
    },
)

def _jvm_import_uses_execution_platform_java_runtime_impl(ctx):
    env = analysistest.begin(ctx)

    actions = analysistest.target_actions(env)
    for mnemonic in ["StampJarManifest", "CreateCompileJar"]:
        matching_actions = [action for action in actions if action.mnemonic == mnemonic]
        asserts.equals(env, 1, len(matching_actions))

        argv = matching_actions[0].argv
        asserts.true(env, "remotejdk11_" in argv[0], "Expected %s to use the execution-platform Java runtime, got %s" % (mnemonic, argv[0]))
        asserts.true(env, "-jar" in argv)
        asserts.equals(env, "C.UTF-8", matching_actions[0].env.get("LC_CTYPE"))
        deploy_jar_args = [arg for arg in argv if arg.endswith("AddJarManifestEntry_deploy.jar")]
        asserts.equals(env, 1, len(deploy_jar_args))

    return analysistest.end(env)

jvm_import_uses_execution_platform_java_runtime_test = analysistest.make(
    _jvm_import_uses_execution_platform_java_runtime_impl,
    config_settings = {
        "//command_line_option:platforms": "@@//tests/unit/jvm_import:android_s390x_target_platform",
        "//command_line_option:tool_java_runtime_version": "remotejdk_11",
    },
)

def _does_non_jvm_import_target_carry_metadata(ctx):
    env = analysistest.begin(ctx)

    asserts.false(env, PackageInfo in ctx.attr.src)

    metadata_info = ctx.attr.src[TransitiveMetadataInfo]
    infos = metadata_info.package_info.to_list()
    asserts.equals(env, 1, len(infos))

    return analysistest.end(env)

does_non_jvm_import_target_carry_metadata_test = analysistest.make(
    _does_non_jvm_import_target_carry_metadata,
    attrs = {
        "src": attr.label(
            doc = "Target to traverse for providers",
            aspects = [gather_metadata_info],
            mandatory = True,
        ),
    },
)

def jvm_import_test_suite(name):
    does_jvm_import_have_tags_test(
        name = "does_jvm_import_have_tags_test",
        target_under_test = "@jvm_import_test//:com_google_code_findbugs_jsr305_3_0_2",
        src = "@jvm_import_test//:com_google_code_findbugs_jsr305_3_0_2",
    )
    does_jvm_import_have_applicable_licenses_test(
        name = "does_jvm_import_have_applicable_licenses_test",
        target_under_test = "@jvm_import_test//:com_google_code_findbugs_jsr305_3_0_2",
        src = "@jvm_import_test//:com_google_code_findbugs_jsr305_3_0_2",
    )
    does_jvm_import_export_a_package_provider_test(
        name = "does_jvm_import_export_a_package_provider",
        target_under_test = "@jvm_import_test//:com_google_code_findbugs_jsr305",
        src = "@jvm_import_test//:com_google_code_findbugs_jsr305",
    )
    jvm_import_uses_execution_platform_java_runtime_test(
        name = "jvm_import_uses_execution_platform_java_runtime_test",
        target_under_test = "@jvm_import_test//:com_google_code_findbugs_jsr305_3_0_2",
    )

    does_jvm_import_exclude_exclusions_in_deps_test(
        name = "does_jvm_import_exclude_exclusions_in_deps_test",
        target_under_test = "@pom_exclusion_testing_coursier//:com_google_guava_guava",
        src = "@pom_exclusion_testing_coursier//:com_google_guava_guava",
    )

    # TODO: restore once https://github.com/bazelbuild/rules_license/issues/154 is resolved
    #    does_non_jvm_import_target_carry_metadata_test(
    #        name = "does_non_jvm_import_target_carry_metadata",
    #        target_under_test = "@jvm_import_test//:com_android_support_appcompat_v7",
    #        src = "@jvm_import_test//:com_android_support_appcompat_v7",
    #    )
    native.test_suite(
        name = name,
        tests = [
            ":does_jvm_import_have_tags_test",
            ":jvm_import_uses_execution_platform_java_runtime_test",
        ],
    )
