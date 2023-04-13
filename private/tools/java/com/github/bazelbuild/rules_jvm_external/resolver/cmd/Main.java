package com.github.bazelbuild.rules_jvm_external.resolver.cmd;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.coursier.NebulaFormat;
import com.github.bazelbuild.rules_jvm_external.jar.ListPackages;
import com.github.bazelbuild.rules_jvm_external.resolver.Artifact;
import com.github.bazelbuild.rules_jvm_external.resolver.DependencyInfo;
import com.github.bazelbuild.rules_jvm_external.resolver.Jetifier;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionRequest;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Main {

  public static void main(String[] args) throws IOException {
    Path outputDir = Files.createTempDirectory("resolver");

    Set<DependencyInfo> infos = null;
    Path output = null;
    try (EventListener listener = createEventListener()) {
      Config config = new Config(listener, args);

      Jetifier jetifier = config.getJetifier();

      ResolutionRequest request = config.getResolutionRequest();
      if (config.isJetifyEnabled()) {
        request = jetifier.amend(request);
      }

      Resolver resolver = config.getResolver();

      Graph<Coordinates> resolved = resolver.resolve(request);

      // Create a new request with the updated entries, if necessary
      // remembering to update exclusions for specific coordinates.
      if (config.isJetifyEnabled()) {
        List<Artifact> originalDeps = request.getDependencies();

        request = jetifier.amend(request, resolved.nodes());

        // Only go and fetch things if the deps we've been asked to fetch have changed.
        // This will be used more often than necessary if the user has requested any
        // version ranges, or constants such as the latest release, but it may help
        // make dependency resolution faster in the simple case.
        if (!request.getDependencies().equals(originalDeps)) {
          listener.onEvent(new PhaseEvent("Secondary resolution for jetified artifacts"));
          resolved = resolver.resolve(request);
        }
      }

      infos = getInfos(listener, config, outputDir, resolved);

      listener.onEvent(new PhaseEvent("Building lock file"));
      output = config.getOutput();

      listener.close();

      Map<String, Object> rendered =
          new NebulaFormat(
                  request.getRepositories().stream()
                      .map(Object::toString)
                      .collect(Collectors.toList()))
              .render(infos, new HashMap<>());

      String converted =
          new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(rendered);

      try (OutputStream os = output == null ? System.out : Files.newOutputStream(output);
          BufferedOutputStream bos = new BufferedOutputStream(os)) {
        bos.write(converted.getBytes(UTF_8));
      }

      System.exit(0);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  private static EventListener createEventListener() {
    boolean termAvailable = !Objects.equals(System.getenv().get("TERM"), "dumb");
    boolean consoleAvailable = System.console() != null;
    if (termAvailable && consoleAvailable) {
      return new AnsiConsoleListener();
    } else if (System.getenv("RJE_VERBOSE") != null) {
      return new PlainConsoleListener();
    }
    return new NullListener();
  }

  private static Set<DependencyInfo> getInfos(
      EventListener listener, Config config, Path outputDir, Graph<Coordinates> resolved) {
    listener.onEvent(new PhaseEvent("Downloading dependencies"));

    ResolutionRequest request = config.getResolutionRequest();

    Downloader downloader =
        new Downloader(
            config.getNetrc(),
            request.getLocalCache(),
            request.getRepositories(),
            listener,
            outputDir);

    List<CompletableFuture<Set<DependencyInfo>>> futures = new LinkedList<>();

    ExecutorService downloadService =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
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

    SortedSet<String> packages;
    try {
      packages = new ListPackages().getPackages(result.getPath());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
}
