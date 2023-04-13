package com.github.bazelbuild.rules_jvm_external.jar;

import com.google.gson.Gson;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public class ListPackages {

  private static final Predicate<String> IS_NUMERIC_VERSION =
      Pattern.compile("[1-9][0-9]*").asPredicate();

  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--argsfile".equals(args[0])) {
      System.err.printf("Required args: --argsfile /path/to/argsfile%n");
      System.exit(1);
    }

    Path argsFile = Paths.get(args[1]);
    Map<String, SortedSet<String>> index = new ListPackages().getPackages(Files.lines(argsFile));
    System.out.println(new Gson().toJson(index));
  }

  public Map<String, SortedSet<String>> getPackages(Stream<String> source) {
    TreeMap<String, SortedSet<String>> index =
        source
            .parallel()
            .map(
                path -> {
                  try {
                    SortedSet<String> packages = getPackages(Paths.get(path));
                    return new AbstractMap.SimpleEntry<>(path, packages);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                })
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (left, right) -> {
                      throw new RuntimeException("Duplicate keys detected but not expected");
                    },
                    TreeMap::new));

    return index;
  }

  public SortedSet<String> getPackages(Path path) throws IOException {
    SortedSet<String> packages = new TreeSet<>();
    try (InputStream fis = new BufferedInputStream(Files.newInputStream(path));
        ZipInputStream zis = new ZipInputStream(fis)) {
      try {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
          if (!entry.getName().endsWith(".class")) {
            continue;
          }
          if ("module-info.class".equals(entry.getName())
              || entry.getName().endsWith("/module-info.class")) {
            continue;
          }
          packages.add(extractPackageName(entry.getName()));
        }
      } catch (ZipException e) {
        System.err.printf("Caught ZipException: %s%n", e);
      }
    }
    return packages;
  }

  private String extractPackageName(String zipEntryName) {
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

  private boolean isNumericVersion(String part) {
    return IS_NUMERIC_VERSION.test(part);
  }
}
