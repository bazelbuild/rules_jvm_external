package com.github.bazelbuild.rules_jvm_external.jar;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class IndexJar {

  private static final Predicate<String> IS_NUMERIC_VERSION =
      Pattern.compile("[1-9][0-9]*").asPredicate();

  private static final String SERVICES_DIRECTORY_PREFIX = "META-INF/services/";

  public static class PerJarIndexResults {
    private final SortedSet<String> packages;
    private final SortedMap<String, SortedSet<String>> serviceImplementations;

    public PerJarIndexResults(SortedSet<String> packages, SortedMap<String, SortedSet<String>> serviceImplementations) {
      this.packages = packages;
      this.serviceImplementations = serviceImplementations;
    }

    public SortedSet<String> getPackages() {
      return this.packages;
    }

    public SortedMap<String, SortedSet<String>> getServiceImplementations() {
      return this.serviceImplementations;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--argsfile".equals(args[0])) {
      System.err.printf("Required args: --argsfile /path/to/argsfile%n");
      System.exit(1);
    }

    TreeMap<String, PerJarIndexResults> index =
        Files.lines(Paths.get(args[1]))
            .parallel()
            .map(
                path -> {
                  try {
                    PerJarIndexResults results = index(Paths.get(path));
                    return new AbstractMap.SimpleEntry<>(path, results);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                })
            .collect(
                Collectors.toMap(
                    entry -> entry.getKey(),
                    entry -> entry.getValue(),
                    (left, right) -> {
                      throw new RuntimeException("Duplicate keys detected but not expected");
                    },
                    TreeMap::new));
    System.out.println(new Gson().toJson(index));
  }

  public static PerJarIndexResults index(Path path) throws IOException {
    SortedSet<String> packages = new TreeSet<>();
    SortedMap<String, SortedSet<String>> serviceImplementations = new TreeMap<>();
    try {
      try (ZipFile zipFile = new ZipFile(path.toFile())) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (entry.getName().startsWith(SERVICES_DIRECTORY_PREFIX) && !SERVICES_DIRECTORY_PREFIX.equals(entry.getName())) {
            String serviceInterface = entry.getName().substring(SERVICES_DIRECTORY_PREFIX.length());
            SortedSet<String> implementingClasses = new TreeSet<>();
            try (InputStream inputStream = zipFile.getInputStream(entry) ; BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream) ; BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(bufferedInputStream))) {
              String implementingClass = bufferedReader.readLine();
              while (implementingClass != null) {
                if (!implementingClass.isEmpty() && !implementingClass.startsWith("#")) {
                  implementingClasses.add(implementingClass);
                }
                implementingClass = bufferedReader.readLine();
              }
            }
            serviceImplementations.put(serviceInterface, implementingClasses);
          }
          if (!entry.getName().endsWith(".class")) {
            continue;
          }
          if ("module-info.class".equals(entry.getName())
              || entry.getName().endsWith("/module-info.class")) {
            continue;
          }
          packages.add(extractPackageName(entry.getName()));
        }
      }
    } catch (ZipException e) {
      System.err.printf("Caught ZipException: %s%n", e);
    }
    return new PerJarIndexResults(packages, serviceImplementations);
  }

  private static String extractPackageName(String zipEntryName) {
    String[] parts = zipEntryName.split("/");
    if (parts.length == 1) {
      return "";
    }
    int skip = 0;
    // As per https://docs.oracle.com/en/java/javase/13/docs/specs/jar/jar.html
    if (parts.length > 3
        && "META-INF".equals(parts[0])
        && "versions".equals(parts[1])
        && isNumericVersion(parts[2])) {
      skip = 3;
    }

    // -1 for the class name, -skip for the skipped META-INF prefix.
    int limit = parts.length - 1 - skip;
    return Arrays.stream(parts).skip(skip).limit(limit).collect(Collectors.joining("."));
  }

  private static boolean isNumericVersion(String part) {
    return IS_NUMERIC_VERSION.test(part);
  }
}
