def unpack_coordinates(coords):
    """Takes a maven coordinate and unpacks it into a struct with fields
    `groupId`, `artifactId`, `version`, `type`, `classifier`
    where type and scope are optional.

    Assumes following maven coordinate syntax:
    groupId:artifactId[:type[:classifier]]:version
    """
    if not coords:
        return None

    parts = coords.split(":")
    nparts = len(parts)

    if nparts == 2:
        return struct(
            groupId = parts[0],
            artifactId = parts[1],
            type = None,
            scope = None,
            classifier = None,
            version = None,
        )

    if nparts < 3 or nparts > 5:
        fail("Unparsed: %s" % coords)

    version = parts[-1]
    parts = dict(enumerate(parts[:-1]))
    return struct(
        groupId = parts.get(0),
        artifactId = parts.get(1),
        type = parts.get(2),
        scope = None,
        classifier = parts.get(3),
        version = version,
    )

def _whitespace(indent):
    whitespace = ""
    for i in range(indent):
        whitespace = whitespace + " "
    return whitespace

def format_dep(unpacked, indent = 8, include_version = True):
    whitespace = _whitespace(indent)

    dependency = [
        whitespace,
        "<dependency>\n",
        whitespace,
        "    <groupId>%s</groupId>\n" % unpacked.groupId,
        whitespace,
        "    <artifactId>%s</artifactId>\n" % unpacked.artifactId,
    ]

    if include_version:
        dependency.extend([
            whitespace,
            "    <version>%s</version>\n" % unpacked.version,
        ])

    if unpacked.type and unpacked.type != "jar":
        dependency.extend([
            whitespace,
            "    <type>%s</type>\n" % unpacked.type,
        ])

    if unpacked.scope and unpacked.scope != "compile":
        dependency.extend([
            whitespace,
            "    <scope>%s</scope>\n" % unpacked.scope,
        ])

    if unpacked.classifier:
        dependency.extend([
            whitespace,
            "    <classifier>%s</classifier>\n" % unpacked.classifier,
        ])

    dependency.extend([
        whitespace,
        "</dependency>",
    ])

    return "".join(dependency)

def generate_pom(
        ctx,
        coordinates,
        pom_template,
        out_name,
        parent = None,
        versioned_dep_coordinates = [],
        unversioned_dep_coordinates = [],
        runtime_deps = [],
        indent = 8):
    unpacked_coordinates = unpack_coordinates(coordinates)
    substitutions = {
        "{groupId}": unpacked_coordinates.groupId,
        "{artifactId}": unpacked_coordinates.artifactId,
        "{version}": unpacked_coordinates.version,
        "{type}": unpacked_coordinates.type or "jar",
        "{classifier}": unpacked_coordinates.classifier or "",
        "{scope}": unpacked_coordinates.scope or "compile",
    }

    if parent:
        # We only want the groupId, artifactID, and version
        unpacked_parent = unpack_coordinates(parent)

        whitespace = _whitespace(indent - 4)
        parts = [
            whitespace,
            "    <groupId>%s</groupId>\n" % unpacked_parent.groupId,
            whitespace,
            "    <artifactId>%s</artifactId>\n" % unpacked_parent.artifactId,
            whitespace,
            "    <version>%s</version>" % unpacked_parent.version,
        ]
        substitutions.update({"{parent}": "".join(parts)})

    deps = []
    for dep in _sort_unpacked(versioned_dep_coordinates) + _sort_unpacked(unversioned_dep_coordinates):
        include_version = dep in versioned_dep_coordinates
        new_scope = "runtime" if dep in runtime_deps else dep.scope
        unpacked = struct(
            groupId = dep.groupId,
            artifactId = dep.artifactId,
            type = dep.type,
            scope = new_scope,
            classifier = dep.classifier,
            version = dep.version,
        )
        deps.append(format_dep(unpacked, indent = indent, include_version = include_version))

    substitutions.update({"{dependencies}": "\n".join(deps)})

    out = ctx.actions.declare_file("%s" % out_name)
    ctx.actions.expand_template(
        template = pom_template,
        output = out,
        substitutions = substitutions,
    )

    return out

def determine_additional_dependencies(jar_files, additional_dependencies):
    """Takes a dict of {`Label`: workspace_name} and returns the `Label`s where any `jar_files match a `workspace_name."""
    to_return = []

    for jar in jar_files:
        owner = jar.owner

        # If we can't tell who the owner is, let's assume things are fine
        if not owner:
            continue

        # Users don't know how `bzlmod` mangles workspace names, but we do
        workspace_name = owner.workspace_name.partition("~")[0]

        for (dep, name) in additional_dependencies.items():
            if (name == workspace_name) and dep:
                if not dep in to_return:
                    to_return.append(dep)

    return to_return

def _sort_unpacked(unpacked_dep):
    """Sorts a list of unpacked dependencies by groupId, artifactId, and version."""

    def _sort_key(dep):
        return (dep.groupId, dep.artifactId, dep.version)

    return sorted(unpacked_dep, key = _sort_key)
