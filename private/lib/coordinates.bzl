def unpack_coordinates(coords):
    """Takes a maven coordinate and unpacks it into a struct with fields
    `groupId`, `artifactId`, `version`, `type`, `scope`
    where type and scope are optional.

    Assumes `coords` is in one of the following syntaxes:
     * groupId:artifactId[:type[:scope]]:version
     * groupId:artifactId[:version][:classifier][@type]
    """
    if not coords:
        return None

    parts = coords.split(":")
    nparts = len(parts)

    if nparts < 2:
        fail("Unparsed: %s" % coords)

    # Both formats look the same for just `group:artifact`
    if nparts == 2:
        return struct(
            groupId = parts[0],
            artifactId = parts[1],
            type = None,
            scope = None,
            version = None,
            classifier = None,
        )

    # From here, we can be sure we have at least three `parts`
    if _is_version_number(parts[2]):
        return _unpack_gradle_format(coords)

    return _unpack_rje_format(coords, parts)

def _is_version_number(part):
    return part[0].isdigit()

def _unpack_rje_format(coords, parts):
    nparts = len(parts)

    if nparts < 3 or nparts > 5:
        fail("Unparsed: %s" % coords)

    version = parts[-1]
    parts = dict(enumerate(parts[:-1]))
    return struct(
        groupId = parts.get(0),
        artifactId = parts.get(1),
        type = parts.get(2),
        scope = parts.get(3),
        classifier = None,
        version = version,
    )

def _unpack_gradle_format(coords):
    idx = coords.find("@")
    type = None
    if idx != -1:
        type = coords[idx + 1:]
        coords = coords[0:idx]

    parts = coords.split(":")
    nparts = len(parts)

    if nparts < 3 or nparts > 4:
        fail("Unparsed: %s" % coords)

    parts = dict(enumerate(parts))

    return struct(
        groupId = parts.get(0),
        artifactId = parts.get(1),
        version = parts.get(2),
        classifier = parts.get(3),
        type = type,
        scope = None,
    )

def to_external_form(coords):
    """Formats `coords` as a string suitable for use by tools such as Gradle.

    The returned format matches Gradle's "external dependency" short-form
    syntax: `group:name:version:classifier@type`
    """

    if type(coords) == "string":
        unpacked = unpack_coordinates(coords)
    else:
        unpacked = coords

    to_return = "%s:%s:%s" % (unpacked.groupId, unpacked.artifactId, unpacked.version)

    if hasattr(unpacked, "classifier"):
        if unpacked.classifier and unpacked.classifier != "jar":
            to_return += ":" + unpacked.classifier

    if hasattr(unpacked, "type"):
        if unpacked.type and unpacked.type != "jar":
            to_return += "@" + unpacked.type

    return to_return

_DEFAULT_PURL_REPOS = [
    "https://repo.maven.apache.org/maven2",
    "https://repo.maven.apache.org/maven2/",
    "https://repo1.maven.org",
    "https://repo1.maven.org/",
]

def to_purl(coords, repository):
    to_return = "pkg:maven/"

    unpacked = unpack_coordinates(coords)
    to_return += "{group}:{artifact}@{version}".format(
        artifact = unpacked.artifactId,
        group = unpacked.groupId,
        version = unpacked.version,
    )

    suffix = []
    if unpacked.classifier:
        suffix.append("classifier=" + unpacked.classifier)
    if unpacked.type:
        suffix.append("type=" + unpacked.type)
    if repository and repository not in _DEFAULT_PURL_REPOS:
        # Default repository name is pulled from https://github.com/package-url/purl-spec/blob/master/PURL-TYPES.rst
        suffix.append("repository=" + repository)

    if len(suffix):
        to_return += "?" + "&".join(suffix)

    return to_return
