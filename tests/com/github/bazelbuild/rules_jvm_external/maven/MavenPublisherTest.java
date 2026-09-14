package com.github.bazelbuild.rules_jvm_external.maven;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.bazelbuild.rules_jvm_external.maven.MavenSigning.SigningMetadata;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;

public class MavenPublisherTest {

  @Test
  public void testPublishLocal() throws Exception {
    File pom = File.createTempFile("pom", ".xml");
    File jar = File.createTempFile("example-project", ".jar");
    final Path root = Paths.get(System.getenv("TEST_TMPDIR"));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    MavenPublisher.run(
        "com.example:example:1.0.0",
        pom.getAbsolutePath(),
        jar.getAbsolutePath(),
        true,
        null,
        root.toUri().toString(),
        null,
        SigningMetadata.noSigner(),
        executor);
    executor.shutdown();

    Path repoRoot = root.resolve("repository/com/example/example/1.0.0");
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.sha1")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.sha1")));
  }

  @Test
  public void testPublishLocalPomOnly() throws Exception {
    File pom = File.createTempFile("pom", ".xml");
    final Path root = Paths.get(System.getenv("TEST_TMPDIR"));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    MavenPublisher.run(
        "com.example:example-bom:1.0.0",
        pom.getAbsolutePath(),
        "",
        true,
        null,
        root.toUri().toString(),
        null,
        SigningMetadata.noSigner(),
        executor);
    executor.shutdown();

    Path repoRoot = root.resolve("com/example/example-bom/1.0.0");
    assertTrue(Files.exists(repoRoot.resolve("example-bom-1.0.0.pom")));
    assertTrue(Files.exists(repoRoot.resolve("example-bom-1.0.0.pom.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-bom-1.0.0.pom.sha1")));
  }

  @Test
  public void testPublishLocalPreservesCompoundArchiveExtensions() throws Exception {
    File pom = File.createTempFile("pom", ".xml");
    File tarGz = File.createTempFile("example-project", ".tar.gz");
    File tarBz2 = File.createTempFile("example-project-sources", ".tar.bz2");
    File tarXz = File.createTempFile("example-project-docs", ".tar.xz");
    final Path root = Files.createTempDirectory(Paths.get(System.getenv("TEST_TMPDIR")), "repo");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    MavenPublisher.run(
        "com.example:example-archives:1.0.0",
        pom.getAbsolutePath(),
        tarGz.getAbsolutePath(),
        true,
        "sources=" + tarBz2.getAbsolutePath() + ",docs=" + tarXz.getAbsolutePath(),
        root.toUri().toString(),
        null,
        SigningMetadata.noSigner(),
        executor);
    executor.shutdown();

    Path repoRoot = root.resolve("com/example/example-archives/1.0.0");
    assertTrue(Files.exists(repoRoot.resolve("example-archives-1.0.0.tar.gz")));
    assertTrue(Files.exists(repoRoot.resolve("example-archives-1.0.0-sources.tar.bz2")));
    assertTrue(Files.exists(repoRoot.resolve("example-archives-1.0.0-docs.tar.xz")));
    assertFalse(Files.exists(repoRoot.resolve("example-archives-1.0.0.gz")));
    assertFalse(Files.exists(repoRoot.resolve("example-archives-1.0.0-sources.bz2")));
    assertFalse(Files.exists(repoRoot.resolve("example-archives-1.0.0-docs.xz")));
  }

  @Test
  public void testPublishHttp() throws Exception {
    final Path root = Paths.get(System.getenv("TEST_TMPDIR"));
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        ex -> {
          String method = ex.getRequestMethod();
          Path target = root.resolve("." + ex.getRequestURI().getPath()).normalize();

          if ("PUT".equals(method)) {
            Files.createDirectories(target.getParent());
            try (InputStream in = ex.getRequestBody();
                OutputStream out = Files.newOutputStream(target)) {
              in.transferTo(out);
            }
            ex.sendResponseHeaders(201, 0);
          } else if ("GET".equals(method) && Files.exists(target)) {
            byte[] data = Files.readAllBytes(target);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(data);
            }
          } else {
            ex.sendResponseHeaders(404, -1);
          }
          ex.close();
        });
    server.start();

    File pom = File.createTempFile("pom", ".xml");
    File jar = File.createTempFile("example-project", ".jar");

    File testJar = File.createTempFile("example-project-test", ".jar");
    File docsJar = File.createTempFile("example-project-docs", ".jar");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    MavenPublisher.run(
        "com.example:example:1.0.0",
        pom.getAbsolutePath(),
        jar.getAbsolutePath(),
        true,
        "test=" + testJar.getAbsolutePath() + ",javadoc=" + docsJar.getAbsolutePath(),
        "http://localhost:" + server.getAddress().getPort() + "/repository",
        null,
        SigningMetadata.noSigner(),
        executor);
    executor.shutdown();
    server.stop(0);

    Path repoRoot = root.resolve("repository/com/example/example/1.0.0");
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.sha1")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.sha1")));
  }

  @Test
  public void testChecksumsUploadedOnlyAfterArtifact() throws Exception {
    final Path root = Paths.get(System.getenv("TEST_TMPDIR"));
    final Set<String> uploadedArtifacts = new HashSet<>();

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/",
        ex -> {
          String method = ex.getRequestMethod();
          Path target = root.resolve("." + ex.getRequestURI().getPath()).normalize();
          String requestPath = ex.getRequestURI().getPath();

          if ("PUT".equals(method)) {
            // Checksums (md5, sha1, sha256, sha512) should only succeed if the artifact itself has
            // been uploaded
            boolean isChecksum = requestPath.endsWith(".md5") || requestPath.endsWith(".sha1")
                || requestPath.endsWith(".sha256") || requestPath.endsWith(".sha512");
            boolean isArtifact = requestPath.endsWith(".jar") || requestPath.endsWith(".pom");

            if (isChecksum) {
              // Extract the base artifact name (without checksum extension)
              String artifactName = requestPath.replaceAll("\\.(md5|sha1|sha256|sha512)$", "");
              if (!uploadedArtifacts.contains(artifactName)) {
                // Simulate CodeArtifact behavior: 404 if artifact not yet indexed
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
              }
            }

            if (isArtifact) {
              uploadedArtifacts.add(requestPath);
            }

            Files.createDirectories(target.getParent());
            try (InputStream in = ex.getRequestBody();
                OutputStream out = Files.newOutputStream(target)) {
              in.transferTo(out);
            }
            ex.sendResponseHeaders(201, 0);
          } else if ("GET".equals(method) && Files.exists(target)) {
            byte[] data = Files.readAllBytes(target);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(data);
            }
          } else {
            ex.sendResponseHeaders(404, -1);
          }
          ex.close();
        });
    server.start();

    File pom = File.createTempFile("pom", ".xml");
    File jar = File.createTempFile("example-project", ".jar");

    ExecutorService executor = Executors.newFixedThreadPool(4);
    MavenPublisher.run(
        "com.example:example:1.0.0",
        pom.getAbsolutePath(),
        jar.getAbsolutePath(),
        true,
        null,
        "http://localhost:" + server.getAddress().getPort() + "/repository",
        null,
        SigningMetadata.noSigner(),
        executor);
    executor.shutdown();
    server.stop(0);

    Path repoRoot = root.resolve("repository/com/example/example/1.0.0");
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.pom.sha1")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.md5")));
    assertTrue(Files.exists(repoRoot.resolve("example-1.0.0.jar.sha1")));
  }
}
