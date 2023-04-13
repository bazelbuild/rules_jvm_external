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

load("//private/rules:java.bzl", "generate_java_jar_command", "get_java_proxy_args")
load("//private/rules:maven_utils.bzl", "unpack_coordinates")
load("//private/rules:urls.bzl", "remove_auth_from_url")
load("//private/rules:v1_lock_file.bzl", "v1_lock_file")
load("//private/rules:v2_lock_file.bzl", "v2_lock_file")
load("//private:coursier_utilities.bzl", "escape", "is_maven_local_path")
load("//private:dependency_tree_parser.bzl", "JETIFY_INCLUDE_LIST_JETIFY_ALL", "parser")

_BUILD = """
# package(default_visibility = [{visibilities}])  # https://github.com/bazelbuild/bazel/issues/13681

load("@bazel_skylib//:bzl_library.bzl", "bzl_library")
load("@rules_jvm_external//private/rules:jvm_import.bzl", "jvm_import")
load("@rules_jvm_external//private/rules:jetifier.bzl", "jetify_aar_import", "jetify_jvm_import")
{aar_import_statement}

{imports}

# Required by stardoc if the repo is ever frozen
bzl_library(
   name = "defs",
   srcs = ["defs.bzl"],
   deps = [
       "@rules_jvm_external//:implementation",
   ],
   visibility = ["//visibility:public"],
)
"""

DEFAULT_AAR_IMPORT_LABEL = "@build_bazel_rules_android//android:rules.bzl"

_AAR_IMPORT_STATEMENT = """\
load("%s", "aar_import")
"""

_BUILD_PIN = """
sh_binary(
    name = "pin",
    srcs = ["pin.sh"],
    args = [
      "$(location :unsorted_deps.json)",
    ],
    data = [
        ":unsorted_deps.json",
    ],
    visibility = ["//visibility:public"],
)
"""

_BUILD_OUTDATED = """
sh_binary(
    name = "outdated",
    srcs = ["outdated.sh"],
    data = [
        "@rules_jvm_external//private/tools/prebuilt:outdated_deploy.jar",
        "outdated.artifacts",
        "outdated.repositories",
    ],
    args = [
        "$(location @rules_jvm_external//private/tools/prebuilt:outdated_deploy.jar)",
        "$(location outdated.artifacts)",
        "$(location outdated.repositories)",
    ],
    visibility = ["//visibility:public"],
)
"""

def _is_verbose(repository_ctx):
    return bool(repository_ctx.os.environ.get("RJE_VERBOSE"))

def _is_windows(repository_ctx):
    return repository_ctx.os.name.find("windows") != -1

def _is_file(repository_ctx, path):
    return repository_ctx.which("test") and repository_ctx.execute(["test", "-f", path]).return_code == 0

def _is_directory(repository_ctx, path):
    return repository_ctx.which("test") and repository_ctx.execute(["test", "-d", path]).return_code == 0

def _shell_quote(s):
    # Lifted from
    #   https://github.com/bazelbuild/bazel-skylib/blob/6a17363a3c27dde70ab5002ad9f2e29aff1e1f4b/lib/shell.bzl#L49
    # because this file cannot load symbols from bazel_skylib since commit
    # 47505f644299aa2483d0df06c2bb2c7aa10d26d4.
    return "'" + s.replace("'", "'\\''") + "'"

def _execute(repository_ctx, cmd, timeout = 600, environment = {}, progress_message = None):
    if progress_message:
        repository_ctx.report_progress(progress_message)

    verbose = _is_verbose(repository_ctx)
    if verbose:
        repository_ctx.execute(
            ["echo", "\n%s" % " ".join([str(c) for c in cmd])],
            quiet = False,
        )

    return repository_ctx.execute(
        cmd,
        timeout = timeout,
        environment = environment,
        quiet = not verbose,
    )

def _get_aar_import_statement_or_empty_str(repository_ctx):
    if repository_ctx.attr.use_starlark_android_rules:
        # parse the label to validate it
        _ = Label(repository_ctx.attr.aar_import_bzl_label)
        return _AAR_IMPORT_STATEMENT % repository_ctx.attr.aar_import_bzl_label
    else:
        return ""

def _windows_check(repository_ctx):
    # TODO(jin): Remove BAZEL_SH usage ASAP. Bazel is going bashless, so BAZEL_SH
    # will not be around for long.
    #
    # On Windows, run msys once to bootstrap it
    # https://github.com/bazelbuild/rules_jvm_external/issues/53
    if (_is_windows(repository_ctx)):
        bash = repository_ctx.os.environ.get("BAZEL_SH")
        if (bash == None):
            fail("Please set the BAZEL_SH environment variable to the path of MSYS2 bash. " +
                 "This is typically `c:\\msys64\\usr\\bin\\bash.exe`. For more information, read " +
                 "https://docs.bazel.build/versions/master/install-windows.html#getting-bazel")

# Compute a signature of the list of artifacts that will be used to build
# the dependency tree. This is used as a check to see whether the dependency
# tree needs to be repinned.
#
# Visible for testing
def compute_dependency_inputs_signature(artifacts, repositories):
    artifact_inputs = []

    for artifact in sorted(artifacts):
        parsed = json.decode(artifact)

        # Sort the keys to provide a stable order
        keys = sorted(parsed.keys())
        flattened = ":".join(["%s=%s" % (key, parsed[key]) for key in keys])
        artifact_inputs.append(flattened)
    return hash(repr(sorted(artifact_inputs))) ^ hash(repr(sorted(repositories)))

def get_netrc_lines_from_entries(netrc_entries):
    netrc_lines = []
    for machine, login_dict in sorted(netrc_entries.items()):
        for login, password in sorted(login_dict.items()):
            netrc_lines.append("machine {}".format(machine))
            netrc_lines.append("login {}".format(login))
            if password:
                netrc_lines.append("password {}".format(password))
    return netrc_lines

def get_home_netrc_contents(repository_ctx):
    # Copied with a ctx -> repository_ctx rename from tools/build_defs/repo/http.bzl's _get_auth.
    # Need to keep updated with improvements in source since we cannot load private methods.
    if "HOME" in repository_ctx.os.environ:
        if not repository_ctx.os.name.startswith("windows"):
            netrcfile = "%s/.netrc" % (repository_ctx.os.environ["HOME"],)
            if _is_file(repository_ctx, netrcfile):
                return repository_ctx.read(netrcfile)
    return ""

def _add_outdated_files(repository_ctx, artifacts, repositories):
    repository_ctx.file(
        "outdated.artifacts",
        "\n".join(["{}:{}:{}".format(artifact["group"], artifact["artifact"], artifact["version"]) for artifact in artifacts]) + "\n",
        executable = False,
    )

    repository_ctx.file(
        "outdated.repositories",
        "\n".join([repo["repo_url"] for repo in repositories]) + "\n",
        executable = False,
    )

    repository_ctx.template(
        "outdated.sh",
        repository_ctx.attr._outdated,
        {
            "{repository_name}": repository_ctx.name,
            "{proxy_opts}": " ".join([_shell_quote(arg) for arg in get_java_proxy_args(repository_ctx)]),
        },
        executable = True,
    )

def _fail_if_repin_required(repository_ctx):
    if not repository_ctx.attr.fail_if_repin_required:
        return False

    env_var_names = repository_ctx.os.environ.keys()
    return "RULES_JVM_EXTERNAL_REPIN" not in env_var_names and "REPIN" not in env_var_names

def _pinned_coursier_fetch_impl(repository_ctx):
    if not repository_ctx.attr.maven_install_json:
        fail("Please specify the file label to maven_install.json (e.g." +
             "//:maven_install.json).")

    _windows_check(repository_ctx)

    repositories = []
    for repository in repository_ctx.attr.repositories:
        repositories.append(json.decode(repository))

    artifacts = []
    for artifact in repository_ctx.attr.artifacts:
        artifacts.append(json.decode(artifact))

    _check_artifacts_are_unique(artifacts, repository_ctx.attr.duplicate_version_warning)

    # Read Coursier state from maven_install.json.
    repository_ctx.symlink(
        repository_ctx.path(repository_ctx.attr.maven_install_json),
        repository_ctx.path("imported_maven_install.json"),
    )
    maven_install_json_content = json.decode(repository_ctx.read(repository_ctx.attr.maven_install_json))

    if v1_lock_file.is_valid_lock_file(maven_install_json_content):
        importer = v1_lock_file
        print("Lock file should be updated. Please run `REPIN=1 bazel run @unpinned_%s//:pin`" % repository_ctx.name)
    elif v2_lock_file.is_valid_lock_file(maven_install_json_content):
        importer = v2_lock_file
    else:
        fail("Unable to read lock file: %s" % repository_ctx.attr.maven_install_json)

    # Validation steps for maven_install.json.

    # Validate that there's a dependency_tree element in the parsed JSON.
    if not importer.is_valid_lock_file(maven_install_json_content):
        fail("Failed to parse %s. " % repository_ctx.path(repository_ctx.attr.maven_install_json) +
             "It is not a valid maven_install.json file. Has this " +
             "file been modified manually?")

    input_artifacts_hash = importer.get_input_artifacts_hash(maven_install_json_content)

    # Then, check to see if we need to repin our deps because inputs have changed
    if input_artifacts_hash == None:
        print("NOTE: %s_install.json does not contain a signature of the required artifacts. " % repository_ctx.name +
              "This feature ensures that the build does not use stale dependencies when the inputs " +
              "have changed. To generate this signature, run 'bazel run @unpinned_%s//:pin'." % repository_ctx.name)
    else:
        computed_artifacts_hash = compute_dependency_inputs_signature(repository_ctx.attr.artifacts, repository_ctx.attr.repositories)
        if computed_artifacts_hash != input_artifacts_hash:
            if _fail_if_repin_required(repository_ctx):
                fail("%s_install.json contains an invalid input signature and must be regenerated. " % (repository_ctx.name) +
                     "This typically happens when the maven_install artifacts have been changed but not repinned. " +
                     "PLEASE DO NOT MODIFY THIS FILE DIRECTLY! To generate a new " +
                     "%s_install.json and re-pin the artifacts, either run:\n" % repository_ctx.name +
                     " REPIN=1 bazel run @unpinned_%s//:pin\n" % repository_ctx.name +
                     "or:\n" +
                     " 1) Set 'fail_if_repin_required' to 'False' in 'maven_install'\n" +
                     " 2) Run 'bazel run @unpinned_%s//:pin'\n" % repository_ctx.name +
                     " 3) Reset 'fail_if_repin_required' to 'True' in 'maven_install'\n\n")
            else:
                print("The inputs to %s_install.json have changed, but the lock file has not been regenerated. " % repository_ctx.name +
                      "Consider running 'bazel run @unpinned_%s//:pin'" % repository_ctx.name)

    dep_tree_signature = importer.get_lock_file_hash(maven_install_json_content)

    if dep_tree_signature == None:
        print("NOTE: %s_install.json does not contain a signature entry of the dependency tree. " % repository_ctx.name +
              "This feature ensures that the file is not modified manually. To generate this " +
              "signature, run 'bazel run @unpinned_%s//:pin'." % repository_ctx.name)
    elif importer.compute_lock_file_hash(maven_install_json_content) != dep_tree_signature:
        # Then, validate that the signature provided matches the contents of the dependency_tree.
        # This is to stop users from manually modifying maven_install.json.
        fail("%s_install.json contains an invalid signature (expected %s and got %s) and may be corrupted. " % (
                 repository_ctx.name,
                 dep_tree_signature,
                 importer.compute_lock_file_hash(maven_install_json_content),
             ) +
             "PLEASE DO NOT MODIFY THIS FILE DIRECTLY! To generate a new " +
             "%s_install.json and re-pin the artifacts, follow these steps: \n\n" % repository_ctx.name +
             "  1) In your WORKSPACE file, comment or remove the 'maven_install_json' attribute in 'maven_install'.\n" +
             "  2) Run 'bazel run @%s//:pin'.\n" % repository_ctx.name +
             "  3) Uncomment or re-add the 'maven_install_json' attribute in 'maven_install'.\n\n")

    # Create the list of http_file repositories for each of the artifacts
    # in maven_install.json. This will be loaded additionally like so:
    #
    # load("@maven//:defs.bzl", "pinned_maven_install")
    # pinned_maven_install()
    http_files = [
        "load(\"@bazel_tools//tools/build_defs/repo:http.bzl\", \"http_file\")",
        "load(\"@bazel_tools//tools/build_defs/repo:utils.bzl\", \"maybe\")",
        "def pinned_maven_install():",
        "    pass",  # Keep it syntactically correct in case of empty dependencies.
    ]
    maven_artifacts = []
    netrc_entries = importer.get_netrc_entries(maven_install_json_content)

    for artifact in importer.get_artifacts(maven_install_json_content):
        http_file_repository_name = escape(artifact["coordinates"])
        maven_artifacts.extend([artifact["coordinates"]])
        http_files.extend([
            "    http_file(",
            "        name = \"%s\"," % http_file_repository_name,
            "        sha256 = \"%s\"," % artifact["sha256"],
            # repository_ctx should point to external/$repository_ctx.name
            # The http_file should point to external/$http_file_repository_name
            # File-path is relative defined from http_file traveling to repository_ctx.
            "        netrc = \"../%s/netrc\"," % (repository_ctx.name),
        ])
        http_files.append("        urls = %s," % repr(
            [remove_auth_from_url(url) for url in artifact["urls"]],
        ))
        http_files.append("        downloaded_file_path = \"%s\"," % artifact["file"])
        http_files.append("    )")

    http_files.extend(["maven_artifacts = [\n%s\n]" % (",\n".join(["    \"%s\"" % artifact for artifact in maven_artifacts]))])

    repository_ctx.file("defs.bzl", "\n".join(http_files), executable = False)
    repository_ctx.file(
        "netrc",
        "\n".join(
            repository_ctx.attr.additional_netrc_lines +
            get_home_netrc_contents(repository_ctx).splitlines() +
            get_netrc_lines_from_entries(netrc_entries),
        ),
        executable = False,
    )

    repository_ctx.report_progress("Generating BUILD targets..")
    (generated_imports, jar_versionless_target_labels) = parser.generate_imports(
        repository_ctx = repository_ctx,
        dependencies = importer.get_artifacts(maven_install_json_content),
        explicit_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
        },
        neverlink_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
            if a.get("neverlink", False)
        },
        testonly_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
            if a.get("testonly", False)
        },
        override_targets = repository_ctx.attr.override_targets,
        skip_maven_local_dependencies = False,
    )

    repository_ctx.template(
        "compat_repository.bzl",
        repository_ctx.attr._compat_repository,
        substitutions = {},
        executable = False,
    )

    repository_ctx.file(
        "BUILD",
        (_BUILD + _BUILD_OUTDATED).format(
            visibilities = ",".join(["\"%s\"" % s for s in (["//visibility:public"] if not repository_ctx.attr.strict_visibility else repository_ctx.attr.strict_visibility_value)]),
            repository_name = repository_ctx.name,
            imports = generated_imports,
            aar_import_statement = _get_aar_import_statement_or_empty_str(repository_ctx),
        ),
        executable = False,
    )

    _add_outdated_files(repository_ctx, artifacts, repositories)

    # Generate a compatibility layer of external repositories for all jar artifacts.
    if repository_ctx.attr.generate_compat_repositories:
        compat_repositories_bzl = ["load(\"@%s//:compat_repository.bzl\", \"compat_repository\")" % repository_ctx.name]
        compat_repositories_bzl.append("def compat_repositories():")
        for versionless_target_label in jar_versionless_target_labels:
            compat_repositories_bzl.extend([
                "    compat_repository(",
                "        name = \"%s\"," % versionless_target_label,
                "        generating_repository = \"%s\"," % repository_ctx.name,
                "    )",
            ])
            repository_ctx.file(
                "compat.bzl",
                "\n".join(compat_repositories_bzl) + "\n",
                executable = False,
            )

def infer_artifact_path_from_primary_and_repos(primary_url, repository_urls):
    """Returns the artifact path inferred by comparing primary_url with urls in repository_urls.

    When given a list of repository urls and a primary url that has a repository url as a prefix and a maven artifact
    path as a suffix, this method will try to determine what the maven artifact path is and return it.

    This method has some handling for basic http-based auth parsing and will do a url comparison with the user:pass@
    portion stripped.

    Ex.
    infer_artifact_path_from_primary_and_repos(
        "http://a:b@c/group/path/to/artifact/file.jar",
        ["http://c"])
    == "group/path/to/artifact/file.jar"

    Returns:
        String of the artifact path used by maven to find a particular artifact. Does not have a leading slash (`/`).
    """
    userless_repository_urls = [remove_auth_from_url(r.rstrip("/")) for r in repository_urls]
    userless_primary_url = remove_auth_from_url(primary_url)
    primary_artifact_path = None
    for url in userless_repository_urls:
        if userless_primary_url.find(url) != -1:
            primary_artifact_path = userless_primary_url[len(url) + 1:]
            break
    return primary_artifact_path

def _check_artifacts_are_unique(artifacts, duplicate_version_warning):
    if duplicate_version_warning == "none":
        return
    seen_artifacts = {}
    duplicate_artifacts = {}
    for artifact in artifacts:
        artifact_coordinate = artifact["group"] + ":" + artifact["artifact"] + (":%s" % artifact["classifier"] if artifact.get("classifier") != None else "")
        if artifact_coordinate in seen_artifacts:
            # Don't warn if the same version is in the list multiple times
            if seen_artifacts[artifact_coordinate] != artifact["version"]:
                if artifact_coordinate in duplicate_artifacts:
                    duplicate_artifacts[artifact_coordinate].append(artifact["version"])
                else:
                    duplicate_artifacts[artifact_coordinate] = [artifact["version"]]
        else:
            seen_artifacts[artifact_coordinate] = artifact["version"]

    if duplicate_artifacts:
        msg_parts = ["Found duplicate artifact versions"]
        for duplicate in duplicate_artifacts:
            msg_parts.append("    {} has multiple versions {}".format(duplicate, ", ".join([seen_artifacts[duplicate]] + duplicate_artifacts[duplicate])))
        msg_parts.append("Please remove duplicate artifacts from the artifact list so you do not get unexpected artifact versions")
        if duplicate_version_warning == "error":
            fail("\n".join(msg_parts))
        else:
            print("\n".join(msg_parts))

def _coursier_fetch_impl(repository_ctx):
    # Not using maven_install.json, so we resolve and fetch from scratch.
    # This takes significantly longer as it doesn't rely on any local
    # caches and uses our resolver's own download mechanisms.

    _windows_check(repository_ctx)

    # Deserialize the spec blobs
    repositories = [json.decode(r) for r in repository_ctx.attr.repositories]

    artifacts = []
    for artifact in repository_ctx.attr.artifacts:
        updated_artifact = {}
        updated_artifact.update(json.decode(artifact))
        exclusions = ["%s:%s" % (e["group"], e["artifact"]) for e in updated_artifact.get("exclusions", [])]
        updated_artifact["exclusions"] = exclusions
        artifacts.append(updated_artifact)

    _check_artifacts_are_unique(artifacts, repository_ctx.attr.duplicate_version_warning)

    excluded_artifacts = []
    for artifact in repository_ctx.attr.excluded_artifacts:
        a = json.decode(artifact)
        excluded_artifacts.append("%s:%s" % (a["group"], a["artifact"]))

    jetify = repository_ctx.attr.jetify_include_list
    if ["*"] == jetify:
        jetify = []

    repository_ctx.file(
        "resolver.args",
        content = json.encode_indent(
            {
                "repositories": [r["repo_url"] for r in repositories],
                "artifacts": artifacts,
                "fetchSources": repository_ctx.attr.fetch_sources,
                "fetchJavadoc": repository_ctx.attr.fetch_javadoc,
                "globalExclusions": excluded_artifacts,
                "enableJetify": repository_ctx.attr.jetify,
                "jetify": jetify,
            },
            indent = "  ",
        ),
        executable = False,
    )

    resolver_cmd = generate_java_jar_command(
        repository_ctx,
        repository_ctx.path(repository_ctx.attr._resolver),
    ) + [
        "--argsfile",
        "resolver.args",
        "--resolver",
        repository_ctx.attr.resolver,
    ]

    result = _execute(
        repository_ctx,
        resolver_cmd,
        progress_message = "Resolving dependencies",
    )
    if result.return_code != 0:
        fail("Error while resolving dependencies: " + result.stderr)

    # Write the raw output to a file in case we need to debug the resolution later
    repository_ctx.file(
        "resolution_result.json",
        content = result.stdout,
        executable = False,
    )

    lock_file_contents = json.decode(result.stdout)

    # Keep the output somewhere so we can refer to it later
    repository_ctx.file(
        "unsorted_deps.json",
        content = v2_lock_file.render_lock_file(
            lock_file_contents,
            compute_dependency_inputs_signature(repository_ctx.attr.artifacts, repository_ctx.attr.repositories),
        ),
        executable = False,
    )

    dependencies = v2_lock_file.get_artifacts(lock_file_contents)

    # Symlink in all the files if necessary
    for dep in dependencies:
        if not dep.get("file"):
            continue

        parts = dep["file"].split("/")
        unpacked = unpack_coordinates(dep["coordinates"])
        local_path = "v1/%s/%s/%s" % (unpacked.groupId, unpacked.artifactId, parts[-1])

        if is_maven_local_path(dep["file"]):
            print("Assuming maven local for artifact: %s" % dep["coordinates"])

        if not repository_ctx.path(local_path).exists:
            repository_ctx.symlink(dep["file"], local_path)

        dep["file"] = local_path

    repository_ctx.report_progress("Generating BUILD targets..")
    (generated_imports, jar_versionless_target_labels) = parser.generate_imports(
        repository_ctx = repository_ctx,
        dependencies = dependencies,
        explicit_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
        },
        neverlink_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
            if a.get("neverlink", False)
        },
        testonly_artifacts = {
            a["group"] + ":" + a["artifact"] + (":" + a["classifier"] if "classifier" in a else ""): True
            for a in artifacts
            if a.get("testonly", False)
        },
        override_targets = repository_ctx.attr.override_targets,
        # Skip maven local dependencies if generating the unpinned repository
        skip_maven_local_dependencies = repository_ctx.attr.name.startswith("unpinned_"),
    )

    # This repository rule can be either in the pinned or unpinned state, depending on when
    # the user invokes artifact pinning. Normalize the repository name here.
    if repository_ctx.name.startswith("unpinned_"):
        repository_name = repository_ctx.name[len("unpinned_"):]
        outdated_build_file_content = ""
    else:
        repository_name = repository_ctx.name

        # Add outdated artifact files if this is a pinned repo
        outdated_build_file_content = _BUILD_OUTDATED
        _add_outdated_files(repository_ctx, artifacts, repositories)

    repository_ctx.file(
        "BUILD",
        (_BUILD + _BUILD_PIN + outdated_build_file_content).format(
            visibilities = ",".join(["\"%s\"" % s for s in (["//visibility:public"] if not repository_ctx.attr.strict_visibility else repository_ctx.attr.strict_visibility_value)]),
            repository_name = repository_name,
            imports = generated_imports,
            aar_import_statement = _get_aar_import_statement_or_empty_str(repository_ctx),
        ),
        executable = False,
    )

    # If maven_install.json has already been used in maven_install,
    # we don't need to instruct user to update WORKSPACE and load pinned_maven_install.
    # If maven_install.json is not used yet, provide complete instructions.
    #
    # Also support custom locations for maven_install.json and update the pin.sh script
    # accordingly.
    predefined_maven_install = bool(repository_ctx.attr.maven_install_json)
    if predefined_maven_install:
        package_path = repository_ctx.attr.maven_install_json.package
        file_name = repository_ctx.attr.maven_install_json.name
        if package_path == "":
            maven_install_location = file_name  # e.g. some.json
        else:
            maven_install_location = "/".join([package_path, file_name])  # e.g. path/to/some.json
    else:
        # Default maven_install.json file name.
        maven_install_location = "{repository_name}_install.json"

    # Expose the script to let users pin the state of the fetch in
    # `<workspace_root>/maven_install.json`.
    #
    # $ bazel run @unpinned_maven//:pin
    #
    # Create the maven_install.json export script for unpinned repositories.
    repository_ctx.template(
        "pin.sh",
        repository_ctx.attr._pin,
        {
            "{maven_install_location}": "$BUILD_WORKSPACE_DIRECTORY/" + maven_install_location,
            "{predefined_maven_install}": str(predefined_maven_install),
            "{repository_name}": repository_name,
        },
        executable = True,
    )

    # Generate 'defs.bzl' with just the dependencies for ':pin'.
    http_files = [
        "load(\"@bazel_tools//tools/build_defs/repo:http.bzl\", \"http_file\")",
        "load(\"@bazel_tools//tools/build_defs/repo:utils.bzl\", \"maybe\")",
        "def pinned_maven_install():",
        "    pass",  # Ensure we're syntactically correct even if no deps are added
    ]
    repository_ctx.file(
        "defs.bzl",
        "\n".join(http_files),
        executable = False,
    )

    # Generate a compatibility layer of external repositories for all jar artifacts.
    if repository_ctx.attr.generate_compat_repositories:
        repository_ctx.template(
            "compat_repository.bzl",
            repository_ctx.attr._compat_repository,
            substitutions = {},
            executable = False,
        )

        compat_repositories_bzl = ["load(\"@%s//:compat_repository.bzl\", \"compat_repository\")" % repository_ctx.name]
        compat_repositories_bzl.append("def compat_repositories():")
        for versionless_target_label in jar_versionless_target_labels:
            compat_repositories_bzl.extend([
                "    compat_repository(",
                "        name = \"%s\"," % versionless_target_label,
                "        generating_repository = \"%s\"," % repository_ctx.name,
                "    )",
            ])
        repository_ctx.file(
            "compat.bzl",
            "\n".join(compat_repositories_bzl) + "\n",
            executable = False,
        )

pinned_coursier_fetch = repository_rule(
    attrs = {
        "_compat_repository": attr.label(default = "//private:compat_repository.bzl"),
        "_outdated": attr.label(default = "//private:outdated.sh"),
        "repositories": attr.string_list(),  # list of repository objects, each as json
        "artifacts": attr.string_list(),  # list of artifact objects, each as json
        "fetch_sources": attr.bool(default = False),
        "fetch_javadoc": attr.bool(default = False),
        "generate_compat_repositories": attr.bool(default = False),  # generate a compatible layer with repositories for each artifact
        "maven_install_json": attr.label(allow_single_file = True),
        "override_targets": attr.string_dict(default = {}),
        "strict_visibility": attr.bool(
            doc = """Controls visibility of transitive dependencies.

            If "True", transitive dependencies are private and invisible to user's rules.
            If "False", transitive dependencies are public and visible to user's rules.
            """,
            default = False,
        ),
        "strict_visibility_value": attr.label_list(default = ["//visibility:private"]),
        "jetify": attr.bool(doc = "Runs the AndroidX [Jetifier](https://developer.android.com/studio/command-line/jetifier) tool on artifacts specified in jetify_include_list. If jetify_include_list is not specified, run Jetifier on all artifacts.", default = False),
        "jetify_include_list": attr.string_list(doc = "List of artifacts that need to be jetified in `groupId:artifactId` format. By default all artifacts are jetified if `jetify` is set to True.", default = JETIFY_INCLUDE_LIST_JETIFY_ALL),
        "additional_netrc_lines": attr.string_list(doc = "Additional lines prepended to the netrc file used by `http_file` (with `maven_install_json` only).", default = []),
        "use_credentials_from_home_netrc_file": attr.bool(default = False, doc = "Whether to include coursier credentials gathered from the user home ~/.netrc file"),
        "fail_if_repin_required": attr.bool(doc = "Whether to fail the build if the maven_artifact inputs have changed but the lock file has not been repinned.", default = False),
        "use_starlark_android_rules": attr.bool(default = False, doc = "Whether to use the native or Starlark version of the Android rules."),
        "aar_import_bzl_label": attr.string(default = DEFAULT_AAR_IMPORT_LABEL, doc = "The label (as a string) to use to import aar_import from"),
        "duplicate_version_warning": attr.string(
            doc = """What to do if there are duplicate artifacts

            If "error", then print a message and fail the build.
            If "warn", then print a warning and continue.
            If "none", then do nothing.
            """,
            default = "warn",
            values = [
                "error",
                "warn",
                "none",
            ],
        ),
    },
    implementation = _pinned_coursier_fetch_impl,
)

coursier_fetch = repository_rule(
    attrs = {
        "_resolver": attr.label(default = "//private/tools/prebuilt:resolver_deploy.jar"),
        "_pin": attr.label(default = "//private:pin.sh"),
        "_compat_repository": attr.label(default = "//private:compat_repository.bzl"),
        "_outdated": attr.label(default = "//private:outdated.sh"),
        "repositories": attr.string_list(),  # list of repository objects, each as json
        "artifacts": attr.string_list(),  # list of artifact objects, each as json
        "fail_on_missing_checksum": attr.bool(default = True),
        "fetch_sources": attr.bool(default = False),
        "fetch_javadoc": attr.bool(default = False),
        "use_credentials_from_home_netrc_file": attr.bool(default = False, doc = "Whether to include coursier credentials gathered from the user home ~/.netrc file"),
        "excluded_artifacts": attr.string_list(default = []),  # list of artifacts to exclude
        "generate_compat_repositories": attr.bool(default = False),  # generate a compatible layer with repositories for each artifact
        "resolver": attr.string(doc = "Resolver engine to use", values = ["gradle", "maven"], default = "gradle"),
        "version_conflict_policy": attr.string(
            doc = """Policy for user-defined vs. transitive dependency version conflicts

            If "pinned", choose the user-specified version in maven_install unconditionally.
            If "default", follow Coursier's default policy.
            """,
            default = "default",
            values = [
                "default",
                "pinned",
            ],
        ),
        "maven_install_json": attr.label(allow_single_file = True),
        "override_targets": attr.string_dict(default = {}),
        "strict_visibility": attr.bool(
            doc = """Controls visibility of transitive dependencies

            If "True", transitive dependencies are private and invisible to user's rules.
            If "False", transitive dependencies are public and visible to user's rules.
            """,
            default = False,
        ),
        "strict_visibility_value": attr.label_list(default = ["//visibility:private"]),
        "resolve_timeout": attr.int(default = 600),
        "jetify": attr.bool(doc = "Runs the AndroidX [Jetifier](https://developer.android.com/studio/command-line/jetifier) tool on artifacts specified in jetify_include_list. If jetify_include_list is not specified, run Jetifier on all artifacts.", default = False),
        "jetify_include_list": attr.string_list(doc = "List of artifacts that need to be jetified in `groupId:artifactId` format. By default all artifacts are jetified if `jetify` is set to True.", default = JETIFY_INCLUDE_LIST_JETIFY_ALL),
        "use_starlark_android_rules": attr.bool(default = False, doc = "Whether to use the native or Starlark version of the Android rules."),
        "aar_import_bzl_label": attr.string(default = DEFAULT_AAR_IMPORT_LABEL, doc = "The label (as a string) to use to import aar_import from"),
        "duplicate_version_warning": attr.string(
            doc = """What to do if there are duplicate artifacts

            If "error", then print a message and fail the build.
            If "warn", then print a warning and continue.
            If "none", then do nothing.
            """,
            default = "warn",
            values = [
                "error",
                "warn",
                "none",
            ],
        ),
    },
    environ = [
        "JAVA_HOME",
        "http_proxy",
        "HTTP_PROXY",
        "https_proxy",
        "HTTPS_PROXY",
        "no_proxy",
        "NO_PROXY",
        "COURSIER_CACHE",
        "COURSIER_OPTS",
        "COURSIER_URL",
        "RJE_VERBOSE",
        "XDG_CACHE_HOME",
    ],
    implementation = _coursier_fetch_impl,
)
