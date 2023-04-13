package com.github.bazelbuild.rules_jvm_external.resolver.remote;

import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.COMPLETE;
import static com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent.Stage.STARTING;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.resolver.MavenRepositoryPath;
import com.github.bazelbuild.rules_jvm_external.resolver.events.DownloadEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Logger;

public class Downloader {

  private static final Logger LOG = Logger.getLogger(Downloader.class.getName());
  // Lifted from RealisedMavenModuleResolveMetadata in Gradle's source
  private static final Set<String> JAR_PACKAGINGS =
      ImmutableSet.of("jar", "ejb", "bundle", "maven-plugin", "eclipse-plugin");
  // Packaging types (extensions) we know don't have fallbacks. I know `jar` is in both sets.
  private static final Set<String> NO_FALLBACK_PACKAGINGS =
      ImmutableSet.of("jar", "tar.gz", "tar.bz2", "tar", "zip", "exe", "dll", "so");
  private static final Set<String> NO_FALLBACK_CLASSIFIERS = ImmutableSet.of("sources", "javadoc");
  private final Path localRepository;
  private final Set<URI> repos;
  private final EventListener listener;
  private final Path outputDir;
  private final HttpClient client;

  public Downloader(
      Netrc netrc,
      Path localRepository,
      Collection<URI> repositories,
      EventListener listener,
      Path outputDir) {
    this.localRepository = localRepository;
    this.repos = ImmutableSet.copyOf(repositories);
    this.listener = listener;
    this.outputDir = outputDir;

    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(300))
            .followRedirects(ALWAYS)
            .proxy(ProxySelector.getDefault());
    Authenticator authenticator =
        new Authenticator() {
          @Override
          protected PasswordAuthentication getPasswordAuthentication() {
            String host = getRequestingHost();
            Netrc.Credential credential = netrc.getCredential(host);
            if (credential == null) {
              return null;
            }
            return new PasswordAuthentication(
                credential.account(), credential.password().toCharArray());
          }
        };
    builder = builder.authenticator(authenticator);
    client = builder.build();
  }

  public DownloadResult download(Coordinates coords) {
    DownloadResult result = performDownload(coords);
    if (result != null) {
      return result;
    }

    // There is a fallback we can use.
    // RealisedMavenModuleResolveMetadata.getArtifactsForConfiguration
    // says that if the artifact isn't a "known java packaging", then we should just look for a
    // `jar`
    // variant as well.
    if (isFallbackAvailable(coords)) {
      result = performDownload(coords.setExtension("jar"));
    }

    if (result == null) {
      throw new UriNotFoundException("Unable to download from any repo: " + coords);
    }
    return result;
  }

  private DownloadResult performDownload(Coordinates coords) {
    MavenRepositoryPath repoPath = new MavenRepositoryPath(coords);

    ImmutableSet.Builder<URI> repos = ImmutableSet.builder();
    Path path = null;

    // Check the local cache for the path first
    Path cachedResult = localRepository.resolve(repoPath.getPath());
    if (Files.exists(cachedResult)) {
      path = cachedResult;
    }

    boolean downloaded = false;
    for (URI repo : this.repos) {
      if (path == null) {
        path = get(repo, repoPath);
        if (path != null) {
          repos.add(repo);
          downloaded = true;
        }
      } else if (head(repo, repoPath)) {
        repos.add(repo);
        downloaded = true;
      }
    }

    if (!downloaded) {
      return null;
    }

    String sha256 = calculateSha256(path);

    return new DownloadResult(coords, repos.build(), path, sha256);
  }

  private boolean isFallbackAvailable(Coordinates coords) {
    String extension = coords.getExtension();
    if (extension.isEmpty() || "jar".equals(extension)) {
      return false;
    }

    if (NO_FALLBACK_PACKAGINGS.contains(extension)) {
      return false;
    }

    if (NO_FALLBACK_CLASSIFIERS.contains(coords.getClassifier())) {
      return false;
    }

    return !JAR_PACKAGINGS.contains(extension);
  }

  private Path get(URI repo, MavenRepositoryPath repoPath) {
    URI uriToGet = repoPath.getUri(repo);

    if ("file".equals(uriToGet.getScheme())) {
      Path path = Paths.get(uriToGet);
      if (Files.exists(path)) {
        return path;
      }
      return null;
    }

    HttpRequest request = startPreparingRequest(uriToGet).GET().build();

    HttpResponse<InputStream> response =
        makeRequest(request, HttpResponse.BodyHandlers.ofInputStream());
    if (!isSuccessful(response)) {
      return null;
    }

    String fileName = repoPath.getPath();

    Path path = outputDir.resolve(fileName);
    try {
      Files.createDirectories(path.getParent());

      try (OutputStream os = Files.newOutputStream(path, CREATE_NEW);
          BufferedOutputStream bos = new BufferedOutputStream(os)) {
        response.body().transferTo(bos);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return path;
  }

  private boolean head(URI repo, MavenRepositoryPath repoPath) {
    URI uriToGet = repoPath.getUri(repo);

    if ("file".equals(uriToGet.getScheme())) {
      Path path = Paths.get(uriToGet);
      return Files.exists(path);
    }

    HttpRequest request =
        startPreparingRequest(uriToGet).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();

    HttpResponse<Void> response = makeRequest(request, HttpResponse.BodyHandlers.discarding());

    return isSuccessful(response);
  }

  private <X> HttpResponse<X> makeRequest(
      HttpRequest request, HttpResponse.BodyHandler<X> handler) {
    listener.onEvent(new DownloadEvent(STARTING, request.uri().toString()));
    LOG.fine("Downloading " + request.uri());

    try {
      return client.send(request, handler);
    } catch (ConnectException e) {
      // Unable to connect to the remote server. Report the URL as not being found
      return new EmptyResponse<>(request, HTTP_NOT_FOUND);
    } catch (IOException e) {
      // But in all other cases, get very upset.
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      listener.onEvent(new DownloadEvent(COMPLETE, request.uri().toString()));
    }
  }

  private HttpRequest.Builder startPreparingRequest(URI uri) {
    return HttpRequest.newBuilder()
        .uri(uri)
        .header("User-Agent", "rules_jvm_external resolver")
        .timeout(Duration.ofMinutes(10));
  }

  private boolean isSuccessful(HttpResponse<?> response) {
    return response.statusCode() > 199 && response.statusCode() < 300;
  }

  private String calculateSha256(Path path) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      return Hashing.sha256().hashBytes(bytes).toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
