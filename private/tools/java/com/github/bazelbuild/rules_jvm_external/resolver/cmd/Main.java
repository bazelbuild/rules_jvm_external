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

package com.github.bazelbuild.rules_jvm_external.resolver.cmd;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.coursier.NebulaFormat;
import com.github.bazelbuild.rules_jvm_external.jar.ListPackages;
import com.github.bazelbuild.rules_jvm_external.resolver.Conflict;
import com.github.bazelbuild.rules_jvm_external.resolver.DependencyInfo;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionRequest;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionResult;
import com.github.bazelbuild.rules_jvm_external.resolver.Resolver;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.remote.DownloadResult;
import com.github.bazelbuild.rules_jvm_external.resolver.remote.Downloader;
import com.github.bazelbuild.rules_jvm_external.resolver.remote.UriNotFoundException;
import com.github.bazelbuild.rules_jvm_external.resolver.ui.AnsiConsoleListener;
import com.github.bazelbuild.rules_jvm_external.resolver.ui.NullListener;
import com.github.bazelbuild.rules_jvm_external.resolver.ui.PlainConsoleListener;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.google.gson.GsonBuilder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Main {

  private static final Set<String> RENDERED_KEYS =
      ImmutableSet.of("artifacts", "dependencies", "packages", "repositories", "version");

  public static void main(String[] args) throws IOException {
    Set<DependencyInfo> infos;
    try (EventListener listener = createEventListener()) {
      ResolverConfig config = new ResolverConfig(listener, args);

      ResolutionRequest request = config.getResolutionRequest();

      Resolver resolver = config.getResolver();

      ResolutionResult resolutionResult = resolver.resolve(request);

      infos = fulfillDependencyInfos(listener, config, resolutionResult.getResolution());

      writeLockFile(listener, config, request, infos, resolutionResult.getConflicts());

      System.exit(0);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static EventListener createEventListener() {
    boolean termAvailable = !Objects.equals(System.getenv().get("TERM"), "dumb");
    boolean consoleAvailable = System.console() != null;
    if (System.getenv("RJE_VERBOSE") != null) {
      return new PlainConsoleListener();
    } else if (termAvailable && consoleAvailable) {
      return new AnsiConsoleListener();
    }
    return new NullListener();
  }

  private static Set<DependencyInfo> fulfillDependencyInfos(
      EventListener listener, ResolverConfig config, Graph<Coordinates> resolved) {
    listener.onEvent(new PhaseEvent("Downloading dependencies"));

    ResolutionRequest request = config.getResolutionRequest();
    String rjeUnsafeCache = System.getenv("RJE_UNSAFE_CACHE");
    boolean cacheResults = false;
    if (rjeUnsafeCache != null) {
      cacheResults = "1".equals(rjeUnsafeCache) || Boolean.parseBoolean(rjeUnsafeCache);
    }

    Downloader downloader =
        new Downloader(
            config.getNetrc(),
            request.getLocalCache(),
            request.getRepositories(),
            listener,
            cacheResults);

    List<CompletableFuture<Set<DependencyInfo>>> futures = new LinkedList<>();

    ExecutorService downloadService =
        Executors.newFixedThreadPool(
            config.getMaxThreads(),
            r -> {
              Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("downloader");
              return thread;
            });
    try {
      for (Coordinates coords : resolved.nodes()) {
        Supplier<Set<DependencyInfo>> dependencyInfoSupplier =
            () -> {
              try {
                return getDependencyInfos(
                    downloader,
                    coords,
                    resolved.successors(coords),
                    config.isFetchSources(),
                    config.isFetchJavadoc());
              } catch (UriNotFoundException e) {
                List<Coordinates> path = new LinkedList<>();
                path.add(coords);
                Set<Coordinates> predecessors = resolved.predecessors(coords);
                while (!predecessors.isEmpty()) {
                  Coordinates next = predecessors.iterator().next();
                  path.add(next);
                  predecessors = resolved.predecessors(next);
                }
                Collections.reverse(path);
                throw new UriNotFoundException(
                    String.format(
                        "Unable to download %s from any of %s. Required because: %s",
                        coords,
                        request.getRepositories(),
                        path.stream().map(Object::toString).collect(Collectors.joining(" -> "))));
              }
            };
        futures.add(CompletableFuture.supplyAsync(dependencyInfoSupplier, downloadService));
      }

      return futures.stream()
          .map(
              future -> {
                try {
                  return future.get();
                } catch (InterruptedException e) {
                  System.exit(5);
                } catch (ExecutionException e) {
                  e.getCause().printStackTrace();
                  System.exit(2);
                }
                return null;
              })
          .flatMap(Set::stream)
          .collect(ImmutableSet.toImmutableSet());
    } finally {
      downloadService.shutdown();
    }
  }

  private static DownloadResult optionallyDownload(Downloader downloader, Coordinates coords) {
    try {
      return downloader.download(coords);
    } catch (UriNotFoundException e) {
      return null;
    }
  }

  private static Set<DependencyInfo> getDependencyInfos(
      Downloader downloader,
      Coordinates coords,
      Set<Coordinates> dependencies,
      boolean fetchSources,
      boolean fetchJavadoc) {
    ImmutableSet.Builder<DependencyInfo> toReturn = ImmutableSet.builder();

    DownloadResult result = downloader.download(coords);

    if (result == null) {
      return toReturn.build();
    }

    SortedSet<String> packages;
    if (result.getPath().isPresent()) {
      try {
        packages = new ListPackages().getPackages(result.getPath().get());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      packages = new TreeSet<>();
    }

    toReturn.add(
        new DependencyInfo(
            coords,
            result.getRepositories(),
            result.getPath(),
            result.getSha256(),
            dependencies,
            packages));

    if (fetchSources) {
      Coordinates sourceCoords = coords.setClassifier("sources").setExtension("jar");
      DownloadResult source = optionallyDownload(downloader, sourceCoords);
      if (source != null) {
        toReturn.add(
            new DependencyInfo(
                sourceCoords,
                source.getRepositories(),
                source.getPath(),
                source.getSha256(),
                ImmutableSet.of(),
                ImmutableSet.of()));
      }
    }

    if (fetchJavadoc) {
      Coordinates docCoords = coords.setClassifier("javadoc").setExtension("jar");
      DownloadResult javadoc = optionallyDownload(downloader, docCoords);
      if (javadoc != null) {
        toReturn.add(
            new DependencyInfo(
                docCoords,
                javadoc.getRepositories(),
                javadoc.getPath(),
                javadoc.getSha256(),
                ImmutableSet.of(),
                ImmutableSet.of()));
      }
    }

    return toReturn.build();
  }

  private static void writeLockFile(
      EventListener listener,
      ResolverConfig config,
      ResolutionRequest request,
      Set<DependencyInfo> infos,
      Set<Conflict> conflicts)
      throws IOException {
    listener.onEvent(new PhaseEvent("Building lock file"));
    Path output = config.getOutput();

    listener.close();

    Function<Coordinates, String> simpleForm =
        c -> c.getGroupId() + ":" + c.getArtifactId() + ":" + c.getVersion();

    Map<String, Object> renderedConflicts = new HashMap<>();
    for (Conflict conflict : conflicts) {
      renderedConflicts.put(
          simpleForm.apply(conflict.getRequested()), simpleForm.apply(conflict.getResolved()));
    }

    Map<String, Object> rendered =
        new NebulaFormat()
            .render(
                request.getRepositories().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList()),
                infos,
                Map.copyOf(renderedConflicts));

    Map<Object, Object> toReturn = new TreeMap<>();
    for (String key : RENDERED_KEYS) {
      toReturn.put(key, rendered.get(key));
    }
    if (rendered.containsKey("conflict_resolution")) {
      toReturn.put("conflict_resolution", rendered.get("conflict_resolution"));
    }

    toReturn.put(
        "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY", "THERE_IS_NO_DATA_ONLY_ZUUL");

    LinkedHashMap<Object, Object> toHash = new LinkedHashMap<>();
    toHash.put("artifacts", rendered.get("artifacts"));
    toHash.put("dependencies", rendered.get("dependencies"));
    toHash.put("repositories", rendered.get("repositories"));
    String reprString = new StarlarkRepr().repr(toHash);
    toReturn.put("__RESOLVED_ARTIFACTS_HASH", reprString.hashCode());

    if (config.getInputHash() != null) {
      toReturn.put("__INPUT_ARTIFACTS_HASH", Long.parseLong(config.getInputHash()));
    }

    String converted =
        new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(toReturn) + "\n";

    try (OutputStream os = output == null ? System.out : Files.newOutputStream(output);
        BufferedOutputStream bos = new BufferedOutputStream(os)) {
      bos.write(converted.getBytes(UTF_8));
    }
  }
}
