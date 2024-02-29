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

package com.github.bazelbuild.rules_jvm_external.jar;

import static org.junit.Assert.assertEquals;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Test;

public class IndexJarTest {
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
    SortedSet<String> expectedPackagesSet = sortedSet(expectedPackages);
    Path jar = Paths.get(Runfiles.create().rlocation(runfileJar));
    PerJarIndexResults perJarIndexResults = new IndexJar().index(jar);
    assertEquals(expectedPackagesSet, perJarIndexResults.getPackages());
  }

  private SortedSet<String> sortedSet(String... contents) {
    SortedSet<String> set = new TreeSet<>();
    for (String string : contents) {
      set.add(string);
    }
    return set;
  }
}
