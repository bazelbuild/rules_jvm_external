package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.classpath.EffectiveClassPath;
import org.gradle.api.internal.classpath.Module;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.classpath.UnknownModuleException;
import org.gradle.api.specs.Spec;
import org.gradle.cache.GlobalCache;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.util.internal.GUtil;

// Largely lifted from Gradle's org.gradle.api.internal.classpath.DefaultModuleRegistry
public class UberJarModuleRegistry implements ModuleRegistry, GlobalCache {

  private final List<File> classpath;

  private final Map<String, Module> modules = new HashMap<>();

  public UberJarModuleRegistry(ClassPath additionalModuleClassPath) {
    ImmutableList.Builder<File> classpath = ImmutableList.builder();
    ClassLoader classLoader = UberJarModuleRegistry.class.getClassLoader();
    for (File classpathFile :
        new EffectiveClassPath(classLoader).plus(additionalModuleClassPath).getAsFiles()) {
      classpath.add(classpathFile);
    }
    this.classpath = classpath.build();
  }

  @Override
  public Module getExternalModule(String name) throws UnknownModuleException {
    throw new UnsupportedOperationException("Cannot load external module: " + name);
  }

  @Override
  public Module getModule(String name) throws UnknownModuleException {
    Module module = modules.get(name);
    if (module == null) {
      module = loadModule(name);
      modules.put(name, module);
    }
    return module;
  }

  private Module loadModule(String moduleName) {
    Module module = loadOptionalModule(moduleName);
    if (module != null) {
      return module;
    }
    throw new UnknownModuleException(
        String.format("Cannot locate manifest for module '%s'.", moduleName));
  }

  @Override
  public Module findModule(String name) throws UnknownModuleException {
    Module module = modules.get(name);
    if (module == null) {
      module = loadOptionalModule(name);
      if (module != null) {
        modules.put(name, module);
      }
    }
    return module;
  }

  @Override
  public ClassPath getAdditionalClassPath() {
    return DefaultClassPath.of(classpath);
  }

  @Override
  public List<File> getGlobalCacheRoots() {
    ImmutableList.Builder<File> builder = ImmutableList.builder();
    builder.addAll(classpath);
    return builder.build();
  }

  private Module loadOptionalModule(final String moduleName) {
    File jarFile = findJar(jarFile1 -> hasModuleProperties(moduleName, jarFile1));
    if (jarFile != null) {
      Set<File> implementationClasspath = new LinkedHashSet<>();
      implementationClasspath.add(jarFile);
      Properties properties = loadModuleProperties(moduleName, jarFile);
      return module(moduleName, properties, implementationClasspath);
    }

    return null;
  }

  private Module module(
      String moduleName, Properties properties, Set<File> implementationClasspath) {
    Set<File> runtimeClasspath = Set.copyOf(classpath);

    String[] projects = split(properties.getProperty("projects"));
    String[] optionalProjects = split(properties.getProperty("optional"));
    return new DefaultModule(
        moduleName, implementationClasspath, runtimeClasspath, projects, optionalProjects);
  }

  private String[] split(String value) {
    if (value == null) {
      return new String[0];
    }
    value = value.trim();
    if (value.length() == 0) {
      return new String[0];
    }
    return value.split(",");
  }

  private Properties loadModuleProperties(String name, File jarFile) {
    try (ZipFile zipFile = new ZipFile(jarFile)) {
      String entryName = getClasspathManifestName(name);
      ZipEntry entry = zipFile.getEntry(entryName);
      if (entry == null) {
        throw new IllegalStateException(
            "Did not find " + entryName + " in " + jarFile.getAbsolutePath());
      }
      try (InputStream is = zipFile.getInputStream(entry)) {
        return GUtil.loadProperties(is);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(
          String.format("Could not load properties for module '%s' from %s", name, jarFile), e);
    }
  }

  private File findJar(Spec<File> allowedJarFiles) {
    for (File file : classpath) {
      if (allowedJarFiles.isSatisfiedBy(file)) {
        return file;
      }
    }
    return null;
  }

  private boolean hasModuleProperties(String name, File jarFile) {
    try (ZipFile zipFile = new ZipFile(jarFile)) {
      String entryName = getClasspathManifestName(name);
      ZipEntry entry = zipFile.getEntry(entryName);
      return entry != null;
    } catch (IOException e) {
      throw new UncheckedIOException(
          String.format("Could not load properties for module '%s' from %s", name, jarFile), e);
    }
  }

  private String getClasspathManifestName(String moduleName) {
    return moduleName + "-classpath.properties";
  }

  private Set<Module> getModules(String[] projectNames) {
    Set<Module> modules = new LinkedHashSet<>();
    for (String project : projectNames) {
      modules.add(getModule(project));
    }
    return modules;
  }

  private class DefaultModule implements Module {

    private final String name;
    private final String[] projects;
    private final String[] optionalProjects;
    private final ClassPath implementationClasspath;
    private final ClassPath runtimeClasspath;
    private final ClassPath classpath;

    public DefaultModule(
        String name,
        Set<File> implementationClasspath,
        Set<File> runtimeClasspath,
        String[] projects,
        String[] optionalProjects) {
      this.name = name;
      this.projects = projects;
      this.optionalProjects = optionalProjects;
      this.implementationClasspath = DefaultClassPath.of(implementationClasspath);
      this.runtimeClasspath = DefaultClassPath.of(runtimeClasspath);
      Set<File> classpath = new LinkedHashSet<>();
      classpath.addAll(implementationClasspath);
      classpath.addAll(runtimeClasspath);
      this.classpath = DefaultClassPath.of(classpath);
    }

    @Override
    public String toString() {
      return "module '" + name + "'";
    }

    @Override
    public Set<Module> getRequiredModules() {
      return getModules(projects);
    }

    @Override
    public ClassPath getImplementationClasspath() {
      return implementationClasspath;
    }

    @Override
    public ClassPath getRuntimeClasspath() {
      return runtimeClasspath;
    }

    @Override
    public ClassPath getClasspath() {
      return classpath;
    }

    @Override
    public Set<Module> getAllRequiredModules() {
      Set<Module> modules = new LinkedHashSet<>();
      collectRequiredModules(modules);
      return modules;
    }

    @Override
    public ClassPath getAllRequiredModulesClasspath() {
      ClassPath classPath = ClassPath.EMPTY;
      for (Module module : getAllRequiredModules()) {
        classPath = classPath.plus(module.getClasspath());
      }
      return classPath;
    }

    private void collectRequiredModules(Set<Module> modules) {
      if (!modules.add(this)) {
        return;
      }
      for (Module module : getRequiredModules()) {
        collectDependenciesOf(module, modules);
      }
      for (String optionalProject : optionalProjects) {
        Module module = findModule(optionalProject);
        if (module != null) {
          collectDependenciesOf(module, modules);
        }
      }
    }

    private void collectDependenciesOf(Module module, Set<Module> modules) {
      ((DefaultModule) module).collectRequiredModules(modules);
    }

    private Module findModule(String optionalProject) {
      try {
        return getModule(optionalProject);
      } catch (UnknownModuleException ex) {
        return null;
      }
    }
  }
}
