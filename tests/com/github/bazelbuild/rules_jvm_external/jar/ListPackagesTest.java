package com.github.bazelbuild.rules_jvm_external.jar;

import static org.junit.Assert.assertEquals;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Test;

public class ListPackagesTest {
  @Test
  public void simplePackages() throws Exception {
    doTest(
        "hamcrest_core_for_test/file/hamcrest-core-1.3.jar",
        "org.hamcrest",
        "org.hamcrest.core",
        "org.hamcrest.internal");
  }

  @Test
  public void hasModuleInfo() throws Exception {
    doTest(
        "gson_for_test/file/gson-2.9.0.jar",
        "com.google.gson",
        "com.google.gson.annotations",
        "com.google.gson.internal",
        "com.google.gson.internal.bind",
        "com.google.gson.internal.bind.util",
        "com.google.gson.internal.reflect",
        "com.google.gson.internal.sql",
        "com.google.gson.reflect",
        "com.google.gson.stream");
  }

  @Test
  public void multiVersioned() throws Exception {
    doTest(
        "junit_platform_commons_for_test/file/junit-platform-commons-1.8.2.jar",
        "org.junit.platform.commons",
        "org.junit.platform.commons.annotation",
        "org.junit.platform.commons.function",
        "org.junit.platform.commons.logging",
        "org.junit.platform.commons.support",
        "org.junit.platform.commons.util");
  }

  @Test
  public void noPackages() throws Exception {
    doTest("hamcrest_core_srcs_for_test/file/hamcrest-core-1.3-sources.jar");
  }

  @Test
  public void invalidCRC() throws Exception {
    doTest(
        "google_api_services_compute_javadoc_for_test/file/google-api-services-compute-v1-rev235-1.25.0-javadoc.jar");
  }

  private void doTest(String runfileJar, String... expectedPackages) throws IOException {
    SortedSet<String> expected = sortedSet(expectedPackages);
    Path jar = Paths.get(Runfiles.create().rlocation(runfileJar));
    SortedSet<String> packages = new ListPackages().getPackages(jar);
    assertEquals(expected, packages);
  }

  private SortedSet<String> sortedSet(String... contents) {
    SortedSet<String> set = new TreeSet<>();
    Collections.addAll(set, contents);
    return set;
  }
}
