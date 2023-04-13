_PIN_SCRIPT = """#!/usr/bin/env bash
set -euo pipefail

temp_file=$(mktemp)
{repin} --resolver {resolver} \
    --argsfile {args_file} \
    {use_unsafe_shared_cache} \
    {fetch_sources} \
    {fetch_javadoc} \
    --output "$temp_file"

mv "$temp_file" "$BUILD_WORKSPACE_DIRECTORY/{lock_file}"

if [ -n "{lock_file}" ]; then
    echo "Successfully pinned resolved artifacts for @{repository_name}, {lock_file} is now up-to-date."
else
    echo "Successfully pinned resolved artifacts for @{repository_name} in {lock_file}." \
      "This file should be checked into your version control system."
    echo
    echo "Next, please update your WORKSPACE file by adding the maven_install_json attribute" \
      "and loading pinned_maven_install from @{repository_name}//:defs.bzl".
    echo
    echo "For example:"
    echo
    cat <<EOF
=============================================================

maven_install(
    artifacts = # ...,
    repositories = # ...,
    maven_install_json = "@//:{repository_name}_install.json",
)

load("@{repository_name}//:defs.bzl", "pinned_maven_install")
pinned_maven_install()

=============================================================
EOF

    echo
    echo "To update {repository_name}_install.json, run this command to re-pin the unpinned repository:"
    echo
    echo "    bazel run @unpinned_{repository_name}//:pin"
fi
echo
"""

def repin_maven_lock_file_impl(ctx):
    # Generate the args file
    artifacts = [json.decode(a) for a in ctx.attr.artifacts]
    repos = ctx.attr.repositories

    contents = {
        "repositories": repos,
        "artifacts": artifacts,
        "globalExclusions": ctx.attr.exclusions,
    }

    args_file = ctx.actions.declare_file("%s-args" % ctx.label.name)
    ctx.actions.write(args_file, json.encode_indent(contents), is_executable = False)

    script = ctx.actions.declare_file("%s.sh" % ctx.label.name)
    ctx.actions.write(
        script,
        _PIN_SCRIPT.format(
            args_file = args_file.short_path,
            fetch_javadoc = "--javadocs" if ctx.attr.fetch_javadoc else "",
            fetch_sources = "--sources" if ctx.attr.fetch_sources else "",
            lock_file = ctx.attr.lock_file,
            repin = ctx.executable._resolver_binary.short_path,
            repository_name = ctx.attr.repository_name,
            resolver = ctx.attr.resolver,
        ),
        is_executable = True,
    )

    return [
        DefaultInfo(
            executable = script,
            files = depset([script]),
            runfiles = ctx.runfiles(files = [args_file]).merge(ctx.attr._resolver_binary[DefaultInfo].default_runfiles),
        ),
    ]

repin_maven_lock_file = rule(
    repin_maven_lock_file_impl,
    executable = True,
    attrs = {
        "repository_name": attr.string(),
        "lock_file": attr.string(),
        "artifacts": attr.string_list(
            allow_empty = True,
        ),
        "repositories": attr.string_list(
            allow_empty = True,
        ),
        "exclusions": attr.string_list(
            allow_empty = True,
        ),
        "resolver": attr.string(
            values = ["gradle", "maven"],
            default = "gradle",
        ),
        "fetch_sources": attr.bool(),
        "fetch_javadoc": attr.bool(),
        "_resolver_binary": attr.label(
            default = "//private/tools/java/com/github/bazelbuild/rules_jvm_external/resolver/cmd:Main",
            executable = True,
            cfg = "exec",
        ),
    },
)
