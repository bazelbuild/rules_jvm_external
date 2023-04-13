package com.github.bazelbuild.rules_jvm_external.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;
import com.github.bazelbuild.rules_jvm_external.resolver.ui.NullListener;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public abstract class ResolverTestBase {

  private Resolver resolver;

  protected abstract Resolver getResolver(Netrc netrc, EventListener listener);

  @Before
  public void createResolver() {
    resolver = getResolver(Netrc.create(null, new HashMap<>()), new NullListener());
  }

  @Test
  public void shouldResolveASingleCoordinate() {
    Coordinates coords = new Coordinates("com.example:foo:1.0");

    Path repo = MavenRepo.create().add(coords).getPath();

    Graph<Coordinates> resolved = resolver.resolve(prepareRequestFor(repo.toUri(), coords));

    assertEquals(1, resolved.nodes().size());
    assertEquals(coords, resolved.nodes().iterator().next());
  }

  @Test
  public void shouldResolveASingleCoordinateWithADep() {
    Coordinates main = new Coordinates("com.example:foo:1.0");
    Coordinates dep = new Coordinates("com.example:bar:1.0");

    Path repo = MavenRepo.create().add(dep).add(main, dep).getPath();

    Graph<Coordinates> resolved = resolver.resolve(prepareRequestFor(repo.toUri(), main));

    Set<Coordinates> nodes = resolved.nodes();
    assertEquals(2, nodes.size());
    assertEquals(ImmutableSet.of(dep), resolved.successors(main));
  }

  @Test
  public void shouldFetchTransitiveDependencies() {
    Coordinates grandDep = new Coordinates("com.example:grand-dep:1.0");
    Coordinates dep = new Coordinates("com.example:dep:2.0");
    Coordinates main = new Coordinates("com.example:main:3.0");

    Path repo = MavenRepo.create().add(grandDep).add(dep, grandDep).add(main, dep).getPath();

    Graph<Coordinates> graph = resolver.resolve(prepareRequestFor(repo.toUri(), main));

    Set<Coordinates> nodes = graph.nodes();
    assertTrue(nodes.contains(main));
    assertTrue(nodes.contains(dep));
    assertTrue(nodes.contains(grandDep));
  }

  @Test
  public void shouldAllowExclusionsAtTheCoordinateLevel() {
    Coordinates singleDep = new Coordinates("com.example:single-dep:1.0");
    Coordinates noDeps = new Coordinates("com.example:no-deps:1.0");

    Path repo = MavenRepo.create().add(noDeps).add(singleDep, noDeps).getPath();

    ResolutionRequest request =
        prepareRequestFor(repo.toUri() /* deliberately left blank */)
            .addArtifact(singleDep.toString(), "com.example:no-deps");

    Graph<Coordinates> graph = resolver.resolve(request);

    Set<Coordinates> nodes = graph.nodes();
    assertFalse(nodes.contains(noDeps));
    assertTrue(nodes.contains(singleDep));
  }

  @Test
  public void shouldAllowGlobalExclusions() {
    Coordinates singleDep = new Coordinates("com.example:single-dep:1.0");
    Coordinates noDeps = new Coordinates("com.example:no-deps:1.0");

    Path repo = MavenRepo.create().add(noDeps).add(singleDep, noDeps).getPath();

    ResolutionRequest request =
        prepareRequestFor(repo.toUri(), singleDep).exclude("com.example:no-deps");

    Graph<Coordinates> graph = resolver.resolve(request);

    Set<Coordinates> nodes = graph.nodes();
    assertFalse(nodes.contains(noDeps));
    assertTrue(nodes.contains(singleDep));
  }

  @Test
  public void shouldBeAbleToFetchCoordinatesWhichDifferByClassifier() {
    Coordinates jar = new Coordinates("com.example:thing:1.2.3");
    Coordinates classified = new Coordinates("com.example:thing:1.2.3:sausages");

    Path repo = MavenRepo.create().add(jar).add(classified).getPath();

    Graph<Coordinates> resolved =
        resolver.resolve(prepareRequestFor(repo.toUri(), jar, classified));

    Set<Coordinates> nodes = resolved.nodes();
    assertEquals(2, nodes.size());
    assertTrue(nodes.contains(jar));
    assertTrue(nodes.contains(classified));
  }

  @Test
  public void shouldDownloadJarsOverHttp() throws IOException {
    Coordinates coords = new Coordinates("com.example:foo:1.0");

    Path repo = MavenRepo.create().add(coords).getPath();

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", new PathHandler(repo));
    server.start();

    int port = server.getAddress().getPort();
    URI remote = URI.create("http://localhost:" + port);

    Graph<Coordinates> resolved = resolver.resolve(prepareRequestFor(remote, coords));

    assertEquals(1, resolved.nodes().size());
    assertEquals(coords, resolved.nodes().iterator().next());
  }

  @Test
  public void shouldDownloadOverHttpWithAuthenticationPassedInOnRepoUrl() throws IOException {
    Coordinates coords = new Coordinates("com.example:foo:1.0");

    Path repo = MavenRepo.create().add(coords).getPath();

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    HttpContext context = server.createContext("/", new PathHandler(repo));
    context.setAuthenticator(
        new BasicAuthenticator("maven") {
          @Override
          public boolean checkCredentials(String username, String password) {
            return "cheese".equals(username) && "hunter2".equals(password);
          }
        });
    server.start();

    int port = server.getAddress().getPort();

    URI remote = URI.create("http://cheese:hunter2@localhost:" + port);

    Graph<Coordinates> resolved = resolver.resolve(prepareRequestFor(remote, coords));

    assertEquals(1, resolved.nodes().size());
    assertEquals(coords, resolved.nodes().iterator().next());
  }

  @Test
  public void shouldDownloadOverHttpWithAuthenticationGatheredFromNetrc() throws IOException {
    Netrc netrc =
        new Netrc(new Netrc.Credential("localhost", "cheese", "hunter2", null), new HashMap<>());
    assertAuthenticatedAccessWorks(netrc, "cheese", "hunter2");
  }

  @Test
  public void shouldDownloadOverHttpWithMachineSpecificAuthenticationFromNetrc()
      throws IOException {
    Netrc netrc =
        new Netrc(
            null,
            ImmutableMap.of(
                "localhost", new Netrc.Credential("localhost", "cheese", "hunter2", null)));
    assertAuthenticatedAccessWorks(netrc, "cheese", "hunter2");
  }

  @Test
  public void shouldIncludeConflictInformationInOutputFromResolution() throws IOException {
    Coordinates older = new Coordinates("com.example:foo:1.0");
    Coordinates newer = new Coordinates("com.example:foo:1.5");

    Path repo = MavenRepo.create().add(older).add(newer).getPath();

    // TODO: write me
  }

  private void assertAuthenticatedAccessWorks(Netrc netrc, String user, String password)
      throws IOException {
    Coordinates coords = new Coordinates("com.example:foo:3.4.5");

    Path repo = MavenRepo.create().add(coords).getPath();

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    HttpContext context = server.createContext("/", new PathHandler(repo));
    context.setAuthenticator(
        new BasicAuthenticator("maven") {
          @Override
          public boolean checkCredentials(String username, String pwd) {
            if (user == null) {
              return true;
            }
            return user.equals(username) && password.equals(pwd);
          }
        });
    server.start();

    int port = server.getAddress().getPort();

    URI remote = URI.create("http://localhost:" + port);

    Resolver resolver = getResolver(netrc, new NullListener());
    Graph<Coordinates> resolved = resolver.resolve(prepareRequestFor(remote, coords));

    assertEquals(1, resolved.nodes().size());
    assertEquals(coords, resolved.nodes().iterator().next());
  }

  private ResolutionRequest prepareRequestFor(URI repo, Coordinates... coordinates) {
    ResolutionRequest request = new ResolutionRequest().addRepository(repo);
    for (Coordinates coordinate : coordinates) {
      request = request.addArtifact(coordinate.toString());
    }
    return request;
  }
}
