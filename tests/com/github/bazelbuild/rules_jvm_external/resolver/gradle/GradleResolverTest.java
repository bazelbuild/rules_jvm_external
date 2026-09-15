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

package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.resolver.Artifact;
import com.github.bazelbuild.rules_jvm_external.resolver.DependencyInfo;
import com.github.bazelbuild.rules_jvm_external.resolver.MavenRepo;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionRequest;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionResult;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolvedArtifact;
import com.github.bazelbuild.rules_jvm_external.resolver.Resolver;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolverTestBase;
import com.github.bazelbuild.rules_jvm_external.resolver.cmd.AbstractMain;
import com.github.bazelbuild.rules_jvm_external.resolver.cmd.ResolverConfig;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.lockfile.V3LockFile;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;
import com.github.bazelbuild.rules_jvm_external.resolver.remote.DownloadResult;
import com.github.bazelbuild.rules_jvm_external.resolver.remote.Downloader;
import com.github.bazelbuild.rules_jvm_external.resolver.ui.NullListener;
import com.google.common.graph.Graph;
import com.google.devtools.build.runfiles.AutoBazelRepository;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.junit.Test;

@AutoBazelRepository
public class GradleResolverTest extends ResolverTestBase {

  @Override
  protected Resolver getResolver(Netrc netrc, EventListener listener) {
    return new GradleResolver(netrc, ResolverConfig.DEFAULT_MAX_THREADS, listener);
  }

  @Test
  public void duplicateDirectDependenciesUseHighestVersion() {
    Coordinates lower = new Coordinates("com.example:library:1.0");
    Coordinates higher = new Coordinates("com.example:library:2.0");
    Path repo = MavenRepo.create().add(lower).add(higher).getPath();

    Graph<Coordinates> resolved =
        resolver.resolve(prepareRequestFor(repo.toUri(), lower, higher)).getResolution();

    assertFalse(resolved.nodes().contains(lower));
    assertTrue(resolved.nodes().contains(higher));
  }

  @Test
  public void resolvesSimpleJvmVariant() throws IOException, XMLStreamException {
    // This test validates gradle can resolve a artifact using only gradle module metadata
    // In this case, there's a root artifact com.example.sample which points to
    // com.example.sample-jvm, which satisfies the runtimeClasspath configuration
    // as it has the JVM variant attributes by default.
    Coordinates baseCoordinates = new Coordinates("com.example:sample:1.0");
    Coordinates jvmCoordinates = new Coordinates("com.example:sample-jvm:1.0");
    MavenRepo mavenRepo = MavenRepo.create();
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);
    Path baseMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/simpleJvmVariant/sample-1.0.module"));
    String baseMetadata = Files.readString(baseMetadataPath);
    moduleMetadataHelper.addToMavenRepo(baseCoordinates, baseMetadata);

    Path jvmMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/simpleJvmVariant/sample-jvm-1.0.module"));
    String jvmMetadata = Files.readString(jvmMetadataPath);
    moduleMetadataHelper.addToMavenRepo(jvmCoordinates, jvmMetadata);

    Graph<Coordinates> resolved =
        resolver
            .resolve(prepareRequestFor(mavenRepo.getPath().toUri(), baseCoordinates))
            .getResolution();

    assertEquals(2, resolved.nodes().size());
    // sample-jvm resolves indirectly through sample using the gradle module metadata redirect
    assertEquals(Set.of(baseCoordinates, jvmCoordinates), resolved.nodes());
  }

  @Test
  public void resolvesJvmButNotAndroidVariant() throws IOException, XMLStreamException {
    // This test validates a scenario similar to
    // https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.1.0/okhttp-5.1.0.module
    // which supports 2 different coordinates with one base coordinate - one for JVM and android
    // using variant selection.
    // Right now, we only resolve the default runtime classpath configuration, so we'll only resolve
    // the JVM variant
    // and won't have the android variant
    Coordinates baseCoordinates = new Coordinates("com.example:sample:1.0");
    Coordinates jvmCoordinates = new Coordinates("com.example:sample-jvm:1.0");
    Coordinates androidCoordinates = new Coordinates("com.example:sample-android:1.0");
    MavenRepo mavenRepo = MavenRepo.create();
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);
    Path baseMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/jvmAndAndroidVariants/sample-1.0.module"));
    String baseMetadata = Files.readString(baseMetadataPath);
    moduleMetadataHelper.addToMavenRepo(baseCoordinates, baseMetadata);

    Path jvmMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/jvmAndAndroidVariants/sample-jvm-1.0.module"));
    String jvmMetadata = Files.readString(jvmMetadataPath);
    moduleMetadataHelper.addToMavenRepo(jvmCoordinates, jvmMetadata);

    Path androidMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/jvmAndAndroidVariants/sample-android-1.0.module"));
    String androidMetadata = Files.readString(androidMetadataPath);
    moduleMetadataHelper.addToMavenRepo(androidCoordinates, androidMetadata);

    Graph<Coordinates> resolved =
        resolver
            .resolve(prepareRequestFor(mavenRepo.getPath().toUri(), baseCoordinates))
            .getResolution();

    // sample-jvm resolves indirectly through sample using the gradle module metadata redirect
    // but not sample-android as we don't resolve multiple variants currently.
    assertEquals(2, resolved.nodes().size());
    // Once we support resolving android variant, this test should be updated to ensure
    // sample-android is also resolved
    assertEquals(Set.of(baseCoordinates, jvmCoordinates), resolved.nodes());
  }

  @Test
  public void throwsAnExceptionIfASingleDependencyWasNotResolved() throws IOException {
    Coordinates validCoordinates = new Coordinates("com.example:sample:1.0");
    Coordinates invalidCoordinates = new Coordinates("com.example:does-not-exist:1.0");
    MavenRepo mavenRepo = MavenRepo.create().add(validCoordinates);
    // Junit 4 doesn't have a clean way to assert exceptions
    // so we handle the exception explicitly and assert on it
    try {
      resolver
          .resolve(
              prepareRequestFor(mavenRepo.getPath().toUri(), validCoordinates, invalidCoordinates))
          .getResolution();
      fail("Resolution shouldn't succeed if invalid coordinates are specified");

    } catch (Exception e) {
      assertTrue(e.getCause() instanceof GradleDependencyResolutionException);
      assertEquals(
          "Failed to resolve dependency: com.example:does-not-exist:1.0 (NOT_FOUND)",
          e.getCause().getMessage());
    }
  }

  @Test
  public void throwsAnExceptionIfMultipleDependenciesWereNotResolved() throws IOException {
    Coordinates validCoordinates = new Coordinates("com.example:sample:1.0");
    Coordinates invalidCoordinates1 = new Coordinates("com.example:does-not-exist:1.0");
    Coordinates invalidCoordinates2 = new Coordinates("com.example:does-not-exist-too:1.0");
    MavenRepo mavenRepo = MavenRepo.create().add(validCoordinates);
    // Junit 4 doesn't have a clean way to assert exceptions
    // so we handle the exception explicitly and assert on it
    try {
      resolver
          .resolve(
              prepareRequestFor(
                  mavenRepo.getPath().toUri(),
                  validCoordinates,
                  invalidCoordinates1,
                  invalidCoordinates2))
          .getResolution();
      fail("Resolution shouldn't succeed if invalid coordinates are specified");

    } catch (Exception e) {
      assertTrue(e.getCause() instanceof GradleDependencyResolutionException);
      assertEquals(
          "Multiple dependencies failed to resolve:\n"
              + "  - com.example:does-not-exist:1.0 (NOT_FOUND)\n"
              + "  - com.example:does-not-exist-too:1.0 (NOT_FOUND)",
          e.getCause().getMessage());
    }
  }

  @Test
  public void resolvesAggregatingDependencyWithOnlyClassifiedArtifacts()
      throws IOException, XMLStreamException {
    // This test validates that dependencies with only platform-specific classified artifacts
    // (e.g., native libraries like netty-transport-native-kqueue with -osx-aarch_64.jar)
    // are correctly marked as aggregating and resolution completes successfully
    // without trying to download a non-existent base JAR.
    Coordinates rootCoordinates = new Coordinates("com.example:app:1.0");
    Coordinates nativeLibCoordinates = new Coordinates("com.example:native-lib:1.0");
    MavenRepo mavenRepo = MavenRepo.create();
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);

    // Add root artifact with dependency on native-lib
    Path rootMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/aggregatingDependency/app-1.0.module"));
    String rootMetadata = Files.readString(rootMetadataPath);
    moduleMetadataHelper.addToMavenRepo(rootCoordinates, rootMetadata);

    // Add native-lib with only classified artifacts (no base JAR)
    // Use POM extension to avoid creating a base JAR file
    Path nativeLibMetadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/aggregatingDependency/native-lib-1.0.module"));
    String nativeLibMetadata = Files.readString(nativeLibMetadataPath);
    Coordinates nativeLibPomCoordinates = nativeLibCoordinates.setExtension("pom");
    moduleMetadataHelper.addToMavenRepo(nativeLibPomCoordinates, nativeLibMetadata);

    // Add classified artifacts for native-lib
    Coordinates osxAarch64 = new Coordinates("com.example:native-lib:jar:osx-aarch_64:1.0");
    Coordinates osxX8664 = new Coordinates("com.example:native-lib:jar:osx-x86_64:1.0");
    mavenRepo.add(osxAarch64);
    mavenRepo.add(osxX8664);

    // This should complete successfully without trying to download the base JAR
    // (com.example:native-lib:1.0 without classifier)
    var result = resolver.resolve(prepareRequestFor(mavenRepo.getPath().toUri(), rootCoordinates));
    Graph<Coordinates> resolved = result.getResolution();

    // Verify the graph contains the root coordinate
    assertTrue(resolved.nodes().contains(rootCoordinates));

    // The base coordinate for native-lib should NOT be in the graph
    // because it only has a POM (it's an aggregating dependency with no base artifact)
    assertEquals(
        "Expected aggregating native-lib coordinate to be removed from graph",
        false,
        resolved.nodes().contains(nativeLibCoordinates));

    // The classified artifacts should be in the graph
    assertTrue(
        "Expected osx-aarch_64 classified artifact in graph",
        resolved.nodes().contains(osxAarch64));
    assertTrue(
        "Expected osx-x86_64 classified artifact in graph", resolved.nodes().contains(osxX8664));

    // The graph should contain: app + 2 classified native-lib artifacts
    assertEquals("Expected 3 coordinates in graph", 3, resolved.nodes().size());
  }

  @Test
  public void lockFileHashIncludesNoBinaryBaseArtifactWithClassifiedArtifact() throws IOException {
    Coordinates baseCoordinates = new Coordinates("com.example:native-lib:1.0");
    Coordinates classifiedCoordinates =
        new Coordinates("com.example:native-lib:jar:osx-aarch_64:1.0");
    URI repo = URI.create("https://example.com/repo/");
    Path classifiedArtifact = Files.createTempFile("native-lib", ".jar");
    String classifiedSha = "sha-for-classified-artifact";

    Map<String, Object> rendered =
        new V3LockFile(
                Set.of(repo),
                Set.of(
                    new DependencyInfo(
                        baseCoordinates,
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(classifiedCoordinates),
                        Set.of(),
                        Set.of(),
                        new TreeMap<>()),
                    new DependencyInfo(
                        classifiedCoordinates,
                        Set.of(repo),
                        Optional.of(classifiedArtifact),
                        Optional.of(classifiedSha),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        new TreeMap<>())),
                Set.of(),
                false)
            .render();

    Map<String, Integer> hashes = AbstractMain.calculateArtifactHash(rendered);
    assertTrue(hashes.containsKey(baseCoordinates.asKey()));
    assertTrue(hashes.containsKey(classifiedCoordinates.asKey()));

    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> artifacts =
        (Map<String, Map<String, Object>>) rendered.get("artifacts");
    @SuppressWarnings("unchecked")
    Map<String, String> shasums =
        (Map<String, String>) artifacts.get("com.example:native-lib").get("shasums");
    assertTrue(shasums.containsKey("jar"));
    assertNull(shasums.get("jar"));
    assertEquals(classifiedSha, shasums.get("osx-aarch_64"));
  }

  @Test
  public void downloaderDoesNotUsePomKnownPathAsBinaryForNonPomCoordinate() throws IOException {
    Coordinates coordinates = new Coordinates("com.example:pom-backed:1.0");
    MavenRepo mavenRepo = MavenRepo.create().add(coordinates);
    Path pomPath =
        mavenRepo
            .getPath()
            .resolve(coordinates.toRepoPath())
            .getParent()
            .resolve("pom-backed-1.0.pom");
    Files.delete(mavenRepo.getPath().resolve(coordinates.toRepoPath()));

    DownloadResult download =
        new Downloader(
                Netrc.fromUserHome(),
                Files.createTempDirectory("local-repo"),
                Set.of(mavenRepo.getPath().toUri()),
                new NullListener(),
                false,
                Map.of(coordinates, pomPath))
            .download(coordinates);

    assertTrue(download.getPath().isEmpty());
    assertTrue(download.getSha256().isEmpty());
  }

  @Test
  public void resolvesTestFixturesVariantWithDistinctDependenciesAndNoCycles()
      throws IOException, XMLStreamException {
    Coordinates sampleCoordinates = new Coordinates("com.example:sample:1.0");
    Coordinates sampleTestFixturesCoordinates =
        new Coordinates("com.example:sample:jar:test-fixtures:1.0");
    Coordinates mainDepCoordinates = new Coordinates("com.example:main-dep:1.0");
    Coordinates testFixturesDepCoordinates = new Coordinates("com.example:tf-dep:1.0");

    MavenRepo mavenRepo =
        MavenRepo.create().add(mainDepCoordinates).add(testFixturesDepCoordinates);
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);
    Path metadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/testFixturesVariant/sample-1.0.module"));
    moduleMetadataHelper.addToMavenRepo(sampleCoordinates, Files.readString(metadataPath));

    // The base helper only creates the main jar. Add just the classified artifact here; using
    // add(...) would rewrite sample-1.0.pom and strip the Gradle metadata marker that makes
    // variant-aware resolution work.
    mavenRepo.addArtifactOnly(sampleTestFixturesCoordinates);

    Graph<Coordinates> resolved =
        resolver
            .resolve(
                prepareRequestFor(
                    mavenRepo.getPath().toUri(), sampleCoordinates, sampleTestFixturesCoordinates))
            .getResolution();

    assertTrue(resolved.nodes().contains(sampleCoordinates));
    assertTrue(resolved.nodes().contains(sampleTestFixturesCoordinates));
    assertTrue(resolved.nodes().contains(mainDepCoordinates));
    assertTrue(resolved.nodes().contains(testFixturesDepCoordinates));

    assertEquals(Set.of(mainDepCoordinates), resolved.successors(sampleCoordinates));
    assertEquals(
        Set.of(sampleCoordinates, testFixturesDepCoordinates),
        resolved.successors(sampleTestFixturesCoordinates));
    assertFalse(resolved.successors(sampleCoordinates).contains(sampleCoordinates));
    assertFalse(resolved.successors(sampleCoordinates).contains(sampleTestFixturesCoordinates));
    assertFalse(
        resolved.successors(sampleTestFixturesCoordinates).contains(sampleTestFixturesCoordinates));
  }

  @Test
  public void forcedModuleVersionSweepsUpTestFixturesRequestedAtOlderVersion()
      throws IOException, XMLStreamException {
    // With `version_conflict_policy = "pinned"` only the unclassified root is forced. Gradle
    // selects a single version per module regardless of classifier, so the test-fixtures root
    // requested at an older version must follow the forced module version.
    Coordinates mainCoordinates = new Coordinates("com.example:sample:2.0");
    Coordinates olderTestFixturesCoordinates =
        new Coordinates("com.example:sample:jar:test-fixtures:1.0");
    Coordinates selectedTestFixturesCoordinates =
        new Coordinates("com.example:sample:jar:test-fixtures:2.0");
    Coordinates mainDepCoordinates = new Coordinates("com.example:main-dep:1.0");
    Coordinates testFixturesDepCoordinates = new Coordinates("com.example:tf-dep:1.0");

    MavenRepo mavenRepo =
        MavenRepo.create().add(mainDepCoordinates).add(testFixturesDepCoordinates);
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);
    for (String version : List.of("1.0", "2.0")) {
      Path metadataPath =
          Paths.get(
              runfiles.rlocation(
                  "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/testFixturesVariant/sample-"
                      + version
                      + ".module"));
      moduleMetadataHelper.addToMavenRepo(
          new Coordinates("com.example:sample:" + version), Files.readString(metadataPath));
      mavenRepo.addArtifactOnly(new Coordinates("com.example:sample:jar:test-fixtures:" + version));
    }

    ResolutionRequest request =
        new ResolutionRequest()
            .addRepository(mavenRepo.getPath().toUri())
            .addArtifact(new Artifact(mainCoordinates, Set.of(), true))
            .addArtifact(new Artifact(olderTestFixturesCoordinates));

    Graph<Coordinates> resolved = resolver.resolve(request).getResolution();

    assertTrue(resolved.nodes().contains(mainCoordinates));
    assertTrue(resolved.nodes().contains(selectedTestFixturesCoordinates));
    assertFalse(resolved.nodes().contains(olderTestFixturesCoordinates));
    for (Coordinates node : resolved.nodes()) {
      assertFalse(node.toString(), node.getVersion() == null || node.getVersion().isEmpty());
    }

    // The variants keep their distinct dependency sets at the selected version.
    assertEquals(Set.of(mainDepCoordinates), resolved.successors(mainCoordinates));
    assertEquals(
        Set.of(mainCoordinates, testFixturesDepCoordinates),
        resolved.successors(selectedTestFixturesCoordinates));
  }

  @Test
  public void forcedVersionOverridesHigherTransitiveRequirementWithoutFailures() {
    // A forced root version must silently win over transitive requests for a higher version.
    // Expressing the pin as a strict constraint instead makes Gradle fail those selectors, and
    // the failures cascade into spurious graph nodes.
    Coordinates pinned = new Coordinates("com.example:lib:1.0");
    Coordinates higher = new Coordinates("com.example:lib:2.0");
    Coordinates app = new Coordinates("com.example:app:1.0");

    Path repo = MavenRepo.create().add(pinned).add(higher).add(app, higher).getPath();

    ResolutionRequest request =
        new ResolutionRequest()
            .addRepository(repo.toUri())
            .addArtifact(new Artifact(app))
            .addArtifact(new Artifact(pinned, Set.of(), true));

    Graph<Coordinates> resolved = resolver.resolve(request).getResolution();

    assertTrue(resolved.nodes().contains(pinned));
    assertFalse(resolved.nodes().contains(higher));
    for (Coordinates node : resolved.nodes()) {
      assertFalse(node.toString(), node.getVersion() == null || node.getVersion().isEmpty());
    }
  }

  @Test
  public void forcedVersionBelowBomConstraintKeepsBomManagedRootsResolvable() {
    // Mirrors the Cash Loanstar shape: a BOM manages `lib` at 2.0 while the root pins it at
    // 1.0 with force_version, and another root is versionless, relying on the BOM. The pin
    // must win without poisoning the BOM-managed root's resolution.
    Coordinates pinned = new Coordinates("com.example:lib:1.0");
    Coordinates higher = new Coordinates("com.example:lib:2.0");
    Coordinates managed = new Coordinates("com.example:managed:1.0");
    Coordinates bom = new Coordinates("com.example:bom:1.0");

    Model bomModel = createModel(bom);
    bomModel.setPackaging("pom");
    DependencyManagement managedDeps = new DependencyManagement();
    Dependency libDep = new Dependency();
    libDep.setGroupId(pinned.getGroupId());
    libDep.setArtifactId(pinned.getArtifactId());
    libDep.setVersion(higher.getVersion());
    managedDeps.addDependency(libDep);
    Dependency managedDep = new Dependency();
    managedDep.setGroupId(managed.getGroupId());
    managedDep.setArtifactId(managed.getArtifactId());
    managedDep.setVersion(managed.getVersion());
    managedDeps.addDependency(managedDep);
    bomModel.setDependencyManagement(managedDeps);

    Path repo =
        MavenRepo.create().add(pinned).add(higher).add(managed).add(bomModel).getPath();

    ResolutionRequest request =
        new ResolutionRequest()
            .addRepository(repo.toUri())
            .addBom(bom)
            .addArtifact(new Artifact(pinned, Set.of(), true))
            .addArtifact(new Artifact(new Coordinates("com.example:managed")));

    Graph<Coordinates> resolved = resolver.resolve(request).getResolution();

    assertTrue(resolved.nodes().contains(pinned));
    assertTrue(resolved.nodes().contains(managed));
    assertFalse(resolved.nodes().contains(higher));
    for (Coordinates node : resolved.nodes()) {
      assertFalse(node.toString(), node.getVersion() == null || node.getVersion().isEmpty());
    }
  }

  @Test
  public void forcedVersionSurvivesDetachedConfigurationRetries()
      throws IOException, XMLStreamException {
    // The plugin re-resolves all declared dependencies in a detached configuration, which does
    // not inherit `configurations.all { resolutionStrategy.force(...) }`. Without the force, a
    // transitive strict constraint deadlocks against the pinned version there, and the failed
    // selectors leak into the graph.
    Coordinates pinned = new Coordinates("com.example:lib:1.0");
    Coordinates higher = new Coordinates("com.example:lib:2.0");
    Coordinates appCoordinates = new Coordinates("com.example:app:1.0");

    MavenRepo mavenRepo = MavenRepo.create().add(pinned).add(higher);
    GradleModuleMetadataHelper moduleMetadataHelper = new GradleModuleMetadataHelper(mavenRepo);

    Runfiles runfiles =
        Runfiles.preload().withSourceRepository(AutoBazelRepository_GradleResolverTest.NAME);
    Path metadataPath =
        Paths.get(
            runfiles.rlocation(
                "rules_jvm_external/tests/com/github/bazelbuild/rules_jvm_external/resolver/gradle/fixtures/strictTransitiveConstraint/app-1.0.module"));
    // app depends on lib with `strictly 2.0` in its Gradle module metadata.
    moduleMetadataHelper.addToMavenRepo(appCoordinates, Files.readString(metadataPath));

    ResolutionRequest request =
        new ResolutionRequest()
            .addRepository(mavenRepo.getPath().toUri())
            .addArtifact(new Artifact(appCoordinates))
            .addArtifact(new Artifact(pinned, Set.of(), true));

    Graph<Coordinates> resolved = resolver.resolve(request).getResolution();

    assertTrue(resolved.nodes().contains(pinned));
    assertFalse(resolved.nodes().contains(higher));
    for (Coordinates node : resolved.nodes()) {
      assertFalse(node.toString(), node.getVersion() == null || node.getVersion().isEmpty());
    }
  }

  @Test
  public void shouldRecordCorrectShaForResolvedVersionNotConflictingVersion() {
    // When there's a version conflict, the paths map should contain only the resolved version,
    // not the conflicting lower version. This ensures we record the correct SHA for the artifact.
    Coordinates lowerVersion = new Coordinates("com.example:conflicted:2.8");
    Coordinates higherVersion = new Coordinates("com.example:conflicted:3.0.0");
    Coordinates dependsOnLower = new Coordinates("com.example:uses-lower:1.0");
    Coordinates dependsOnHigher = new Coordinates("com.example:uses-higher:1.0");

    Path repo =
        MavenRepo.create()
            .add(lowerVersion)
            .add(higherVersion)
            .add(dependsOnLower, lowerVersion)
            .add(dependsOnHigher, higherVersion)
            .getPath();

    ResolutionResult result =
        resolver.resolve(prepareRequestFor(repo.toUri(), dependsOnLower, dependsOnHigher));

    // Verify there's a conflict
    assertFalse("Expected a conflict to be recorded", result.getConflicts().isEmpty());

    // Verify the resolution graph contains only the higher version
    Graph<Coordinates> graph = result.getResolution();
    Set<Coordinates> conflictedNodes =
        graph.nodes().stream()
            .filter(c -> "conflicted".equals(c.getArtifactId()))
            .collect(Collectors.toSet());
    assertEquals("Should resolve to exactly one version", 1, conflictedNodes.size());
    assertTrue("Should resolve to higher version", conflictedNodes.contains(higherVersion));

    // Verify the resolved artifacts contain only the resolved (higher) version, not the lower one
    Map<Coordinates, ResolvedArtifact> artifacts = result.getArtifacts();
    assertTrue("Artifacts should contain resolved version", artifacts.containsKey(higherVersion));
    assertFalse(
        "Artifacts should not contain conflicting lower version",
        artifacts.containsKey(lowerVersion));
  }
}
