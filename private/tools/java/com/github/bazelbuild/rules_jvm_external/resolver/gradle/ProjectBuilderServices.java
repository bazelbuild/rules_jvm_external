package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.internal.CacheFactory;
import org.gradle.cache.internal.DefaultCacheFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.installation.CurrentGradleInstallation;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.scopes.GlobalScopeServices;

public class ProjectBuilderServices extends GlobalScopeServices {
  public ProjectBuilderServices() {
    super(false);
  }

  protected CacheFactory createCacheFactory(
      FileLockManager fileLockManager,
      ExecutorFactory executorFactory,
      ProgressLoggerFactory progressLoggerFactory) {
    return new DefaultCacheFactory(fileLockManager, executorFactory, progressLoggerFactory);
  }

  ModuleRegistry createModuleRegistry(CurrentGradleInstallation currentGradleInstallation) {
    return new UberJarModuleRegistry(additionalModuleClassPath);
  }
}
