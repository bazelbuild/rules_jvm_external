package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Jetifier {

  // Contains a mapping from old GA coordinates to the GAV coordinates that are keys in `includes`
  private final Map<Coordinates, Coordinates> versionlessIncludes;
  // The subset of JETIFIER_INCLUDES that the versionlessIncludes refer to
  private final Map<Coordinates, Coordinates> includes;

  public Jetifier() {
    this(JETIFIER_INCLUDES.keySet());
  }

  /**
   * Creates a new `Jetifier` using the `includes` given to us. Only those includes that are known
   * to have mappings to AndroidX alternatives will be considered, and the others will be silently
   * ignored.
   */
  public Jetifier(Collection<Coordinates> includes) {
    // Strip out any versions
    ImmutableSet<Coordinates> stripped =
        includes.stream()
            .map(c -> new Coordinates(String.format("%s:%s", c.getGroupId(), c.getArtifactId())))
            .filter(JETIFIER_VERSIONLESS_COORDINATES::containsKey)
            .collect(ImmutableSet.toImmutableSet());

    // Because `JETIFIER_VERSIONLESS_COORDINATES` was created from
    // `JETIFIER_INCLUDES` we know that all values in the former
    // are keys in the latter.

    this.versionlessIncludes =
        stripped.stream()
            .map(c -> new SimpleImmutableEntry<>(c, JETIFIER_VERSIONLESS_COORDINATES.get(c)))
            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

    // Grab the subset of the full JETIFIER_INCLUDES we'll need
    this.includes =
        this.versionlessIncludes.values().stream()
            .map(c -> new SimpleImmutableEntry<>(c, JETIFIER_INCLUDES.get(c)))
            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public ResolutionRequest amend(ResolutionRequest request) {
    Set<Artifact> amended = amend(request.getDependencies());
    return request.replaceDependencies(amended);
  }

  public Set<Artifact> amend(Collection<Artifact> toAmend) {
    // Apparently the expected behaviour is to keep everything, as
    // well as updating the set of artifacts to grab. This seems
    // a bit odd, but apparently it's expected behaviour.
    Set<Artifact> amended = new HashSet<>(toAmend);
    toAmend.stream().map(this::updateIfNecessary).forEach(amended::add);
    return ImmutableSet.copyOf(amended);
  }

  /**
   * Update a request, making sure all coordinates in `nodes` are added. If the request already has
   * a dependency on one of these coordinates the version of the existing dep will be overridden.
   * Dependencies in the original request that are not in `nodes` will be removed from the request.
   */
  public ResolutionRequest amend(ResolutionRequest request, Set<Coordinates> nodes) {
    Set<Artifact> existing = amend(request.getDependencies());

    System.err.println(existing);

    Map<String, Artifact> versionlessArtifacts =
        existing.stream()
            .map(a -> new SimpleImmutableEntry<>(a.getCoordinates().asKey(), a))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Set<Artifact> amended = new HashSet<>();

    for (Coordinates coords : nodes) {
      Artifact artifact = versionlessArtifacts.get(coords.asKey());
      if (artifact != null) {
        artifact = new Artifact(coords, artifact.getExclusions());
      } else {
        artifact = new Artifact(coords);
      }
      amended.add(artifact);
    }

    return request.replaceDependencies(ImmutableSet.copyOf(amended));
  }

  private Artifact updateIfNecessary(Artifact artifact) {
    Coordinates coords = artifact.getCoordinates();

    // We may be given coordinates where the version is going
    // to be pulled from a BOM. In this case, there won't be a
    // version and we should return the unchanged artifact.
    if (coords.getVersion() == null) {
      return artifact;
    }

    Coordinates refreshed = versionlessIncludes.getOrDefault(coords, artifact.getCoordinates());
    Coordinates replacement = includes.getOrDefault(refreshed, refreshed);

    ImmutableSet<Coordinates> exclusions =
        artifact.getExclusions().stream()
            .map(this::updateExclusionCoordinates)
            .flatMap(Collection::stream)
            .collect(ImmutableSet.toImmutableSet());

    return new Artifact(replacement, exclusions);
  }

  private Set<Coordinates> updateExclusionCoordinates(Coordinates coords) {
    // Exclusions don't have versions
    if (coords.getVersion() != null) {
      return ImmutableSet.of(coords);
    }

    Coordinates jetified = versionlessIncludes.get(coords);
    return jetified == null ? ImmutableSet.of(coords) : ImmutableSet.of(coords, jetified);
  }

  // Manually crafted from
  // https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-master-dev/jetifier/jetifier/core/src/main/resources/default.config
  // Take the "pomRules" section and transform it with inlined values from versions.latestReleased.
  // Comment out the com.android.databinding and androidx.databinding entries since they are
  // special-cased due to gradle integration.
  private static final Map<Coordinates, Coordinates> JETIFIER_INCLUDES =
      ImmutableMap.<String, String>builder()
          .put(
              "com.android.support:animated-vector-drawable:28.0.0",
              "androidx.vectordrawable:vectordrawable-animated:1.0.0")
          .put("com.android.support:appcompat-v7:28.0.0", "androidx.appcompat:appcompat:1.0.0")
          .put("com.android.support:cardview-v7:28.0.0", "androidx.cardview:cardview:1.0.0")
          .put("com.android.support:customtabs:28.0.0", "androidx.browser:browser:1.0.0")
          .put("com.android.support:design:28.0.0", "com.google.android.material:material:1.0.0")
          .put(
              "com.android.support:exifinterface:28.0.0",
              "androidx.exifinterface:exifinterface:1.0.0")
          .put("com.android.support:gridlayout-v7:28.0.0", "androidx.gridlayout:gridlayout:1.0.0")
          .put("com.android.support:leanback-v17:28.0.0", "androidx.leanback:leanback:1.0.0")
          .put(
              "com.android.support:mediarouter-v7:28.0.0-alpha5",
              "androidx.mediarouter:mediarouter:1.0.0-alpha5")
          .put("com.android.support:multidex:1.0.3", "androidx.multidex:multidex:2.0.0")
          .put(
              "com.android.support:multidex-instrumentation:1.0.3",
              "androidx.multidex:multidex-instrumentation:2.0.0")
          .put("com.android.support:palette-v7:28.0.0", "androidx.palette:palette:1.0.0")
          .put("com.android.support:percent:28.0.0", "androidx.percentlayout:percentlayout:1.0.0")
          .put(
              "com.android.support:preference-leanback-v17:28.0.0",
              "androidx.leanback:leanback-preference:1.0.0")
          .put(
              "com.android.support:preference-v14:28.0.0",
              "androidx.legacy:legacy-preference-v14:1.0.0")
          .put("com.android.support:preference-v7:28.0.0", "androidx.preference:preference:1.0.0")
          .put(
              "com.android.support:recommendation:28.0.0",
              "androidx.recommendation:recommendation:1.0.0")
          .put(
              "com.android.support:recyclerview-v7:28.0.0",
              "androidx.recyclerview:recyclerview:1.0.0")
          .put(
              "com.android.support:support-annotations:28.0.0",
              "androidx.annotation:annotation:1.0.0")
          .put("com.android.support:support-compat:28.0.0", "androidx.core:core:1.0.0")
          .put(
              "com.android.support:support-content:28.0.0",
              "androidx.contentpager:contentpager:1.0.0")
          .put(
              "com.android.support:support-core-ui:28.0.0",
              "androidx.legacy:legacy-support-core-ui:1.0.0")
          .put(
              "com.android.support:support-core-utils:28.0.0",
              "androidx.legacy:legacy-support-core-utils:1.0.0")
          .put(
              "com.android.support:support-dynamic-animation:28.0.0",
              "androidx.dynamicanimation:dynamicanimation:1.0.0")
          .put("com.android.support:support-emoji:28.0.0", "androidx.emoji:emoji:1.0.0")
          .put(
              "com.android.support:support-emoji-appcompat:28.0.0",
              "androidx.emoji:emoji-appcompat:1.0.0")
          .put(
              "com.android.support:support-emoji-bundled:28.0.0",
              "androidx.emoji:emoji-bundled:1.0.0")
          .put("com.android.support:support-fragment:28.0.0", "androidx.fragment:fragment:1.0.0")
          .put("com.android.support:support-media-compat:28.0.0", "androidx.media:media:1.0.0")
          .put(
              "com.android.support:support-tv-provider:28.0.0",
              "androidx.tvprovider:tvprovider:1.0.0")
          .put("com.android.support:support-v13:28.0.0", "androidx.legacy:legacy-support-v13:1.0.0")
          .put("com.android.support:support-v4:28.0.0", "androidx.legacy:legacy-support-v4:1.0.0")
          .put(
              "com.android.support:support-vector-drawable:28.0.0",
              "androidx.vectordrawable:vectordrawable:1.0.0")
          .put(
              "com.android.support:textclassifier:28.0.0",
              "androidx.textclassifier:textclassifier:1.0.0")
          .put("com.android.support:transition:28.0.0", "androidx.transition:transition:1.0.0")
          .put("com.android.support:wear:28.0.0", "androidx.wear:wear:1.0.0")
          .put(
              "com.android.support:asynclayoutinflater:28.0.0",
              "androidx.asynclayoutinflater:asynclayoutinflater:1.0.0")
          .put("com.android.support:collections:28.0.0", "androidx.collection:collection:1.0.0")
          .put(
              "com.android.support:coordinatorlayout:28.0.0",
              "androidx.coordinatorlayout:coordinatorlayout:1.0.0")
          .put(
              "com.android.support:cursoradapter:28.0.0",
              "androidx.cursoradapter:cursoradapter:1.0.0")
          .put("com.android.support:customview:28.0.0", "androidx.customview:customview:1.0.0")
          .put(
              "com.android.support:documentfile:28.0.0", "androidx.documentfile:documentfile:1.0.0")
          .put(
              "com.android.support:drawerlayout:28.0.0", "androidx.drawerlayout:drawerlayout:1.0.0")
          .put(
              "com.android.support:interpolator:28.0.0", "androidx.interpolator:interpolator:1.0.0")
          .put("com.android.support:loader:28.0.0", "androidx.loader:loader:1.0.0")
          .put(
              "com.android.support:localbroadcastmanager:28.0.0",
              "androidx.localbroadcastmanager:localbroadcastmanager:1.0.0")
          .put("com.android.support:print:28.0.0", "androidx.print:print:1.0.0")
          .put(
              "com.android.support:slidingpanelayout:28.0.0",
              "androidx.slidingpanelayout:slidingpanelayout:1.0.0")
          .put(
              "com.android.support:swiperefreshlayout:28.0.0",
              "androidx.swiperefreshlayout:swiperefreshlayout:1.0.0")
          .put("com.android.support:viewpager:28.0.0", "androidx.viewpager:viewpager:1.0.0")
          .put(
              "com.android.support:versionedparcelable:28.0.0",
              "androidx.versionedparcelable:versionedparcelable:1.0.0")
          .put("android.arch.work:work-runtime:1.0.0", "androidx.work:work-runtime:2.0.0")
          .put("android.arch.work:work-runtime-ktx:1.0.0", "androidx.work:work-runtime-ktx:2.0.0")
          .put("android.arch.work:work-rxjava2:1.0.0", "androidx.work:work-rxjava2:2.0.0")
          .put("android.arch.work:work-testing:1.0.0", "androidx.work:work-testing:2.0.0")
          .put(
              "android.arch.navigation:navigation-common:1.0.0",
              "androidx.navigation:navigation-common:2.0.0")
          .put(
              "android.arch.navigation:navigation-common-ktx:1.0.0",
              "androidx.navigation:navigation-common-ktx:2.0.0")
          .put(
              "android.arch.navigation:navigation-dynamic-features-fragment:1.0.0",
              "androidx.navigation:navigation-dynamic-features-fragment:2.0.0")
          .put(
              "android.arch.navigation:navigation-dynamic-features-runtime:1.0.0",
              "androidx.navigation:navigation-dynamic-features-runtime:2.0.0")
          .put(
              "android.arch.navigation:navigation-fragment:1.0.0",
              "androidx.navigation:navigation-fragment:2.0.0")
          .put(
              "android.arch.navigation:navigation-fragment-ktx:1.0.0",
              "androidx.navigation:navigation-fragment-ktx:2.0.0")
          .put(
              "android.arch.navigation:navigation-runtime:1.0.0",
              "androidx.navigation:navigation-runtime:2.0.0")
          .put(
              "android.arch.navigation:navigation-runtime-ktx:1.0.0",
              "androidx.navigation:navigation-runtime-ktx:2.0.0")
          .put(
              "android.arch.navigation:navigation-ui:1.0.0",
              "androidx.navigation:navigation-ui:2.0.0")
          .put(
              "android.arch.navigation:navigation-ui-ktx:1.0.0",
              "androidx.navigation:navigation-ui-ktx:2.0.0")
          .put("android.arch.core:common:1.1.1", "androidx.arch.core:core-common:2.0.0")
          .put("android.arch.core:core:1.0.0-alpha3", "androidx.arch.core:core:2.0.0")
          .put("android.arch.core:core-testing:1.1.1", "androidx.arch.core:core-testing:2.0.0")
          .put("android.arch.core:runtime:1.1.1", "androidx.arch.core:core-runtime:2.0.0")
          .put("android.arch.lifecycle:common:1.1.1", "androidx.lifecycle:lifecycle-common:2.0.0")
          .put(
              "android.arch.lifecycle:common-java8:1.1.1",
              "androidx.lifecycle:lifecycle-common-java8:2.0.0")
          .put(
              "android.arch.lifecycle:compiler:1.1.1",
              "androidx.lifecycle:lifecycle-compiler:2.0.0")
          .put(
              "android.arch.lifecycle:extensions:1.1.1",
              "androidx.lifecycle:lifecycle-extensions:2.0.0")
          .put(
              "android.arch.lifecycle:reactivestreams:1.1.1",
              "androidx.lifecycle:lifecycle-reactivestreams:2.0.0")
          .put("android.arch.lifecycle:runtime:1.1.1", "androidx.lifecycle:lifecycle-runtime:2.0.0")
          .put(
              "android.arch.lifecycle:viewmodel:1.1.1",
              "androidx.lifecycle:lifecycle-viewmodel:2.0.0")
          .put(
              "android.arch.lifecycle:livedata:1.1.1",
              "androidx.lifecycle:lifecycle-livedata:2.0.0")
          .put(
              "android.arch.lifecycle:livedata-core:1.1.1",
              "androidx.lifecycle:lifecycle-livedata-core:2.0.0")
          .put("android.arch.paging:common:1.0.0", "androidx.paging:paging-common:2.0.0")
          .put("android.arch.paging:runtime:1.0.0", "androidx.paging:paging-runtime:2.0.0")
          .put("android.arch.paging:rxjava2:1.0.0-alpha1", "androidx.paging:paging-rxjava2:2.0.0")
          .put("android.arch.persistence:db:1.1.0", "androidx.sqlite:sqlite:2.0.0")
          .put(
              "android.arch.persistence:db-framework:1.1.0",
              "androidx.sqlite:sqlite-framework:2.0.0")
          .put("android.arch.persistence.room:common:1.1.0", "androidx.room:room-common:2.0.0")
          .put("android.arch.persistence.room:compiler:1.1.0", "androidx.room:room-compiler:2.0.0")
          .put(
              "android.arch.persistence.room:migration:1.1.0", "androidx.room:room-migration:2.0.0")
          .put("android.arch.persistence.room:runtime:1.1.0", "androidx.room:room-runtime:2.0.0")
          .put("android.arch.persistence.room:rxjava2:1.1.0", "androidx.room:room-rxjava2:2.0.0")
          .put("android.arch.persistence.room:testing:1.1.0", "androidx.room:room-testing:2.0.0")
          .put("android.arch.persistence.room:guava:1.1.0", "androidx.room:room-guava:2.0.0")
          .put(
              "com.android.support.constraint:constraint-layout:1.1.0",
              "androidx.constraintlayout:constraintlayout:1.1.3")
          .put(
              "com.android.support.constraint:constraint-layout-solver:1.1.0",
              "androidx.constraintlayout:constraintlayout-solver:1.1.3")
          .put(
              "com.android.support.test:orchestrator:1.0.2",
              "androidx.test:orchestrator:1.1.0-alpha3")
          .put("com.android.support.test:rules:1.0.2", "androidx.test:rules:1.1.0-alpha3")
          .put("com.android.support.test:runner:1.0.2", "androidx.test:runner:1.1.0-alpha3")
          .put("com.android.support.test:monitor:1.0.2", "androidx.test:monitor:1.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-accessibility:3.0.2",
              "androidx.test.espresso:espresso-accessibility:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-contrib:3.0.2",
              "androidx.test.espresso:espresso-contrib:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-core:3.0.2",
              "androidx.test.espresso:espresso-core:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-idling-resource:3.0.2",
              "androidx.test.espresso:espresso-idling-resource:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-intents:3.0.2",
              "androidx.test.espresso:espresso-intents:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-remote:3.0.2",
              "androidx.test.espresso:espresso-remote:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso:espresso-web:3.0.2",
              "androidx.test.espresso:espresso-web:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso.idling:idling-concurrent:3.0.2",
              "androidx.test.espresso.idling:idling-concurrent:3.1.0-alpha3")
          .put(
              "com.android.support.test.espresso.idling:idling-net:3.0.2",
              "androidx.test.espresso.idling:idling-net:3.1.0-alpha3")
          .put(
              "com.android.support.test.janktesthelper:janktesthelper:1.0.1",
              "androidx.test.jank:janktesthelper:1.0.1-alpha3")
          .put(
              "com.android.support.test.services:test-services:1.0.2",
              "androidx.test:test-services:1.1.0-alpha3")
          .put(
              "com.android.support.test.uiautomator:uiautomator:2.1.3",
              "androidx.test.uiautomator:uiautomator:2.2.0-alpha3")
          .put(
              "com.android.support.test.uiautomator:uiautomator-v18:2.1.3",
              "androidx.test.uiautomator:uiautomator:2.2.0-alpha3")
          .put("com.android.support:car:28.0.0-alpha5", "androidx.car:car:1.0.0-alpha5")
          .put("com.android.support:slices-core:28.0.0", "androidx.slice:slice-core:1.0.0")
          .put("com.android.support:slices-builders:28.0.0", "androidx.slice:slice-builders:1.0.0")
          .put("com.android.support:slices-view:28.0.0", "androidx.slice:slice-view:1.0.0")
          .put("com.android.support:heifwriter:28.0.0", "androidx.heifwriter:heifwriter:1.0.0")
          .put(
              "com.android.support:recyclerview-selection:28.0.0",
              "androidx.recyclerview:recyclerview-selection:1.0.0")
          .put("com.android.support:webkit:28.0.0", "androidx.webkit:webkit:1.0.0")
          .put(
              "com.android.support:biometric:28.0.0-alpha03",
              "androidx.biometric:biometric:1.0.0-alpha03")
          .build()
          .entrySet()
          .stream()
          .map(
              entry ->
                  new SimpleImmutableEntry<>(
                      new Coordinates(entry.getKey()), new Coordinates(entry.getValue())))
          .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

  private final Map<Coordinates, Coordinates> JETIFIER_VERSIONLESS_COORDINATES =
      JETIFIER_INCLUDES.keySet().stream()
          .map(
              key ->
                  new SimpleImmutableEntry<>(
                      new Coordinates(key.getGroupId() + ":" + key.getArtifactId()), key))
          .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
}
