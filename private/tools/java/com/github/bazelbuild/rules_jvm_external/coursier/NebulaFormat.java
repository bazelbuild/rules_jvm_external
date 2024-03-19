// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.github.bazelbuild.rules_jvm_external.coursier;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.resolver.DependencyInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Format resolution results into the v2 lock file format. */
public class NebulaFormat {

  /** "Render" the resolution result to a `Map` suitable for printing as JSON. */
  public Map<String, Object> render(
      Collection<String> repositories, Set<DependencyInfo> infos, Map<String, Object> conflicts) {
    repositories = new LinkedHashSet<>(repositories);
    boolean isUsingM2Local =
        repositories.stream().map(String::toLowerCase).anyMatch(repo -> repo.equals("m2local/"));

    Map<String, Map<String, Object>> artifacts = new TreeMap<>();
    Map<String, Set<String>> deps = new TreeMap<>();
    Map<String, Set<String>> packages = new TreeMap<>();
    Map<String, Set<String>> repos = new LinkedHashMap<>();
    repositories.forEach(r -> repos.put(stripAuthenticationInformation(r), new TreeSet<>()));

    Set<String> skipped = new TreeSet<>();
    Map<String, String> files = new TreeMap<>();

    infos.forEach(
        info -> {
          Coordinates coords = info.getCoordinates();
          String key = coords.asKey();

          // The short key is the group:artifact[:extension] tuple. The classifier
          // is used as a key in the shasum dict, and the version is also stored
          // in the same dict as the shasums. In the common case where we have
          // multiple `jar` artifacts, this means that we group all the classifiers
          // together.
          String shortKey = coords.getGroupId() + ":" + coords.getArtifactId();
          if (coords.getExtension() != null
              && !coords.getExtension().isEmpty()
              && !"jar".equals(coords.getExtension())) {
            shortKey += ":" + coords.getExtension();
          }

          if (info.getPath().isEmpty() || info.getSha256().isEmpty()) {
            skipped.add(key);
          }

          Map<String, Object> artifactValue =
              artifacts.computeIfAbsent(shortKey, k -> new TreeMap<>());
          artifactValue.put("version", coords.getVersion());

          String classifier;
          if (coords.getClassifier() == null || coords.getClassifier().isEmpty()) {
            classifier = "jar";
          } else {
            classifier = coords.getClassifier();
          }
          @SuppressWarnings("unchecked")
          Map<String, String> shasums =
              (Map<String, String>) artifactValue.computeIfAbsent("shasums", k -> new TreeMap<>());
          info.getSha256().ifPresent(sha -> shasums.put(classifier, sha));

          info.getRepositories().stream()
              .map(Objects::toString)
              .forEach(
                  repo -> {
                    repos
                        .getOrDefault(stripAuthenticationInformation(repo), new TreeSet<>())
                        .add(key);
                  });

          deps.put(
              key,
              info.getDependencies().stream()
                  .map(Coordinates::asKey)
                  .map(Object::toString)
                  .collect(Collectors.toCollection(TreeSet::new)));
          packages.put(key, info.getPackages());

          if (info.getPath().isPresent()) {
            // Regularise paths to UNIX format
            files.put(key, info.getPath().get().toString().replace('\\', '/'));
          }
        });

    Map<String, Object> lock = new LinkedHashMap<>();
    lock.put("artifacts", ensureArtifactsAllHaveAtLeastOneShaSum(artifacts));
    lock.put("dependencies", removeEmptyItems(deps));
    lock.put("packages", removeEmptyItems(packages));
    if (isUsingM2Local) {
      lock.put("m2local", true);
    }
    lock.put("repositories", repos);

    lock.put("skipped", skipped);
    if (conflicts != null && !conflicts.isEmpty()) {
      lock.put("conflict_resolution", new TreeMap<>(conflicts));
    }
    lock.put("files", files);

    lock.put("version", "2");

    return lock;
  }

  private Map<String, Map<String, Object>> ensureArtifactsAllHaveAtLeastOneShaSum(
      Map<String, Map<String, Object>> artifacts) {
    for (Map<String, Object> item : artifacts.values()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> shasums = (Map<String, Object>) item.get("shasums");
      if (shasums == null || !shasums.isEmpty()) {
        continue;
      }
      shasums.put("jar", null);
    }
    return artifacts;
  }

  private <K, V extends Collection> Map<K, V> removeEmptyItems(Map<K, V> input) {
    return input.entrySet().stream()
        .filter(e -> !e.getValue().isEmpty())
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (l, r) -> {
                  l.addAll(r);
                  return l;
                },
                TreeMap::new));
  }

  private String stripAuthenticationInformation(String possibleUri) {
    try {
      URI uri = new URI(possibleUri);
      URI stripped =
          new URI(
              uri.getScheme(),
              null,
              uri.getHost(),
              uri.getPort(),
              uri.getPath(),
              uri.getQuery(),
              uri.getFragment());

      String toReturn = stripped.toString();

      if (stripped.getQuery() == null && uri.getFragment() == null && !toReturn.endsWith("/")) {
        toReturn += "/";
      }

      return toReturn;
    } catch (URISyntaxException e) {
      // Do nothing: we may not have been given a URI, but something like `m2local/`
    }
    return possibleUri;
  }
}
