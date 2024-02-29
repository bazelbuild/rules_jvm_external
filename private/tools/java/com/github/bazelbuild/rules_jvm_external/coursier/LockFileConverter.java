package com.github.bazelbuild.rules_jvm_external.coursier;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.MavenRepositoryPath;
import com.github.bazelbuild.rules_jvm_external.resolver.DependencyInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Reads the output of the coursier resolve and generate a v2 lock file */
public class LockFileConverter {

  private final Set<String> repositories;
  private final Path unsortedJson;

  public static void main(String[] args) {
    Path unsortedJson = null;
    Path output = null;

    // Insertion order matters
    Set<String> repositories = new LinkedHashSet<>();

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--json":
          i++;
          unsortedJson = Paths.get(args[i]);
          break;

        case "--output":
          i++;
          output = Paths.get(args[i]);
          break;

        case "--repo":
          i++;
          // Make sure the repos end with a slash
          if (args[i].endsWith("/")) {
            repositories.add(args[i]);
          } else {
            repositories.add(args[i] + "/");
          }
          break;

        default:
          throw new IllegalArgumentException("Unknown command line option: " + args[i]);
      }
    }

    if (unsortedJson == null) {
      System.err.println(
          "Path to coursier-generated lock file is required. Add using the `--json` flag");
      System.exit(1);
    }

    LockFileConverter converter = new LockFileConverter(repositories, unsortedJson);
    Set<DependencyInfo> infos = converter.getDependencies();
    Map<String, Object> conflicts = converter.getConflicts();

    Map<String, Object> rendered = new NebulaFormat(repositories).render(infos, conflicts);

    String converted =
        new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(rendered);

    if (output == null) {
      System.out.println(converted);
    } else {
      try {
        Files.write(output, converted.getBytes(UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  public LockFileConverter(Set<String> repositories, Path unsortedJson) {
    this.repositories = Objects.requireNonNull(repositories);
    this.unsortedJson = Objects.requireNonNull(unsortedJson);
  }

  private Map<String, Object> getConflicts() {
    Map<String, Object> depTree = readDepTree();

    @SuppressWarnings("unchecked")
    Map<String, Object> conflicts =
        (Map<String, Object>) depTree.getOrDefault("conflict_resolution", Collections.EMPTY_MAP);
    return conflicts;
  }

  public Set<DependencyInfo> getDependencies() {
    Map<String, Object> depTree = readDepTree();
    Map<Coordinates, Coordinates> mappings = deriveCoordinateMappings(depTree);

    Set<DependencyInfo> toReturn =
        new TreeSet<>(Comparator.comparing(DependencyInfo::getCoordinates));

    @SuppressWarnings("unchecked")
    Collection<Map<String, Object>> coursierDeps =
        (Collection<Map<String, Object>>) depTree.get("dependencies");
    for (Map<String, Object> coursierDep : coursierDeps) {
      String coord = (String) coursierDep.get("coord");
      Coordinates coords = mappings.get(new Coordinates(coord));

      Set<URI> repos = new LinkedHashSet<>();
      @SuppressWarnings("unchecked")
      Collection<String> mirrorUrls =
          (Collection<String>) coursierDep.getOrDefault("mirror_urls", new TreeSet<>());
      for (String mirrorUrl : mirrorUrls) {
        for (String repo : repositories) {
          if ("m2local".equalsIgnoreCase(repo) || "m2local/".equalsIgnoreCase(repo)) {
            continue;
          }
          if (mirrorUrl.startsWith(repo)) {
            repos.add(URI.create(repo));
          }
        }
      }

      // If there's a file, make a note of where it came from
      String file = (String) coursierDep.get("file");

      String classifier = coords.getClassifier();
      if (classifier == null || classifier.isEmpty()) {
        classifier = "jar";
      }

      Set<Coordinates> directDeps = new TreeSet<>();
      if (!"sources".equals(classifier) && !"javadoc".equals(classifier)) {
        @SuppressWarnings("unchecked")
        Collection<String> depCoords =
            (Collection<String>) coursierDep.getOrDefault("directDependencies", new TreeSet<>());
        directDeps =
            depCoords.stream()
                .map(Coordinates::new)
                .map(c -> mappings.getOrDefault(c, c))
                .collect(Collectors.toCollection(TreeSet::new));
      }

      Object rawPackages = coursierDep.get("packages");
      Set<String> packages = Collections.EMPTY_SET;
      if (rawPackages != null) {
        @SuppressWarnings("unchecked")
        Collection<String> depPackages = (Collection<String>) rawPackages;
        packages = new TreeSet<>(depPackages);
      }

      SortedMap<String, SortedSet<String>> services = new TreeMap<>();
      Object rawServices = coursierDep.get("services");
      if (rawServices != null) {
        services = new TreeMap<>((Map<String, SortedSet<String>>) rawServices);
      }

      toReturn.add(
          new DependencyInfo(
              coords,
              repos,
              file == null ? null : Paths.get(file),
              (String) coursierDep.get("sha256"),
              directDeps,
              packages,
              services));
    }

    return toReturn;
  }

  private Map<String, Object> readDepTree() {
    try (Reader reader = Files.newBufferedReader(unsortedJson)) {
      return new Gson().fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Provide mappings from coordinates that are incorrect in the original lock file.
   *
   * <p>It turns out that coursier will sometimes claim that a regular set of coordinates is, in
   * fact, for a different extension (typically `aar`). The v2 lock file format relies on the
   * coordinates to determine the path to the artifact, so this kind of nonsense <i>will not
   * stand</i>. Go through all the coordinates in the file and make sure that the coordinate matches
   * the output path. If it doesn't, work out the correct coordinate and provide a mapping.
   *
   * @return a mapping of {@link Coordinates} from the dep tree to the correct {
   * @link Coordinates}.
   */
  private Map<Coordinates, Coordinates> deriveCoordinateMappings(Map<String, Object> depTree) {
    Map<Coordinates, Coordinates> toReturn = new HashMap<>();

    @SuppressWarnings("unchecked")
    Collection<Map<String, Object>> coursierDeps =
        (Collection<Map<String, Object>>) depTree.get("dependencies");
    for (Map<String, Object> coursierDep : coursierDeps) {
      Coordinates coord = new Coordinates((String) coursierDep.get("coord"));
      String expectedPath = new MavenRepositoryPath(coord).getPath();
      String file = (String) coursierDep.get("file");

      if (file == null) {
        toReturn.put(coord, coord);
        continue;
      }

      // Files may be URL encoded. Decode
      try {
        file = URLDecoder.decode(file, UTF_8.toString());
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      }

      if (file.endsWith(expectedPath)) {
        toReturn.put(coord, coord);
        continue;
      }

      // The path of the output does not match the expected path. Attempt to rewrite.
      // Assume that the group and artifact IDs are correct, otherwise, we have real
      // problems.

      // The expected path looks something like:
      // "[group]/[artifact]/[version]/[artifact]-[version](-[classifier])(.[extension])"
      String prefix = coord.getGroupId().replace(".", "/") + "/" + coord.getArtifactId() + "/";

      int index = file.indexOf(prefix);
      if (index == -1) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot determine actual coordinates for %s. Current coordinates are %s",
                file, coord));
      }
      String pathSubstring = file.substring(index + prefix.length());

      // The next part of the string should be the version number
      index = pathSubstring.indexOf("/");
      if (index == -1) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot determine version number from %s. Current coordinates are %s",
                file, coord));
      }
      String version = pathSubstring.substring(0, index);

      // After the version, there should be nothing left but a file name
      pathSubstring = pathSubstring.substring(version.length() + 1);

      // Now we know the version, we can calculate the expected file name. For now, ignore
      // the fact that there may be a classifier. We're going to derive that if necessary.
      String expectedFileName = coord.getArtifactId() + "-" + version;

      index = pathSubstring.indexOf(expectedFileName);
      if (index == -1) {
        throw new IllegalArgumentException(
            String.format(
                "Expected file name (%s) not found in path (%s). Current coordinates are %s",
                expectedFileName, file, coord));
      }

      String classifier = "";
      String extension = "";
      String remainder = pathSubstring.substring(expectedFileName.length());

      if (remainder.isEmpty()) {
        throw new IllegalArgumentException(
            String.format(
                "File does not appear to have a suffix. %s. Current coordinates are %s",
                file, coord));
      }

      if (remainder.charAt(0) == '-') {
        // We have a classifier
        index = remainder.lastIndexOf('.');
        if (index == -1) {
          throw new IllegalArgumentException(
              String.format(
                  "File does not appear to have a suffix. %s. Current coordinates are %s",
                  file, coord));
        }
        classifier = remainder.substring(1, index);
        extension = remainder.substring(index + 1);
      } else if (remainder.charAt(0) == '.') {
        // We have an extension
        extension = remainder.substring(1);
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Unable to determine classifier and extension from %s. Current coordinates are %s",
                file, coord));
      }

      toReturn.put(
          coord,
          new Coordinates(
              coord.getGroupId(), coord.getArtifactId(), extension, classifier, version));
    }

    return toReturn;
  }
}
