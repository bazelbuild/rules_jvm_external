package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.initialization.DefaultBuildRequestMetaData;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.initialization.NoOpBuildEventConsumer;
import org.gradle.initialization.ProjectDescriptorRegistry;
import org.gradle.internal.Pair;
import org.gradle.internal.build.AbstractBuildState;
import org.gradle.internal.build.BuildModelControllerServices;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.RootBuildState;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.buildtree.BuildTreeModelControllerServices;
import org.gradle.internal.buildtree.BuildTreeState;
import org.gradle.internal.buildtree.RunTasksRequirements;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.composite.IncludedBuildInternal;
import org.gradle.internal.logging.services.LoggingServiceRegistry;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.resources.DefaultResourceLockCoordinationService;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import org.gradle.internal.session.BuildSessionState;
import org.gradle.internal.session.CrossBuildSessionState;
import org.gradle.internal.time.Time;
import org.gradle.internal.work.WorkerLeaseRegistry;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.testfixtures.internal.TestBuildScopeServices;
import org.gradle.util.Path;

// Largely lifted from org.gradle.testfixtures.internal.ProjectBuilderImpl
public class ProjectFactory {

  private final File userHomeDir;

  private ProjectFactory(File userHomeDir) {
    this.userHomeDir = userHomeDir;
  }

  public static Project createProject(File userHomeDir) {
    return new ProjectFactory(userHomeDir).actuallyCreateProject();
  }

  private Project actuallyCreateProject() {
    String name = "resolver-project";
    File projectDir = createTempDirectory("project-dir");

    StartParameterInternal startParameter = new StartParameterInternal();
    startParameter.setGradleUserHomeDir(userHomeDir);
    NativeServices.initializeOnDaemon(userHomeDir);

    final ServiceRegistry globalServices = getGlobalServices();

    BuildRequestMetaData buildRequestMetaData =
        new DefaultBuildRequestMetaData(Time.currentTimeMillis());
    CrossBuildSessionState crossBuildSessionState =
        new CrossBuildSessionState(globalServices, startParameter);
    GradleUserHomeScopeServiceRegistry userHomeServices = userHomeServicesOf(globalServices);
    BuildSessionState buildSessionState =
        new BuildSessionState(
            userHomeServices,
            crossBuildSessionState,
            startParameter,
            buildRequestMetaData,
            ClassPath.EMPTY,
            new DefaultBuildCancellationToken(),
            buildRequestMetaData.getClient(),
            new NoOpBuildEventConsumer());
    BuildTreeModelControllerServices.Supplier modelServices =
        buildSessionState
            .getServices()
            .get(BuildTreeModelControllerServices.class)
            .servicesForBuildTree(new RunTasksRequirements(startParameter));
    BuildTreeState buildTreeState =
        new BuildTreeState(buildSessionState.getServices(), modelServices);
    TestRootBuild build = new TestRootBuild(projectDir, startParameter, buildTreeState);

    BuildScopeServices buildServices = build.getBuildServices();
    buildServices.get(BuildStateRegistry.class).attachRootBuild(build);

    // Take a root worker lease; this won't ever be released as ProjectBuilder has no lifecycle
    ResourceLockCoordinationService coordinationService =
        buildServices.get(ResourceLockCoordinationService.class);
    WorkerLeaseService workerLeaseService = buildServices.get(WorkerLeaseService.class);
    WorkerLeaseRegistry.WorkerLeaseCompletion workerLease = workerLeaseService.maybeStartWorker();

    GradleInternal gradle = build.getMutableModel();
    gradle.setIncludedBuilds(Collections.emptyList());

    ProjectDescriptorRegistry projectDescriptorRegistry =
        buildServices.get(ProjectDescriptorRegistry.class);
    DefaultProjectDescriptor projectDescriptor =
        new DefaultProjectDescriptor(
            null,
            name,
            projectDir,
            projectDescriptorRegistry,
            buildServices.get(FileResolver.class));
    projectDescriptorRegistry.addProject(projectDescriptor);

    ClassLoaderScope baseScope = gradle.getClassLoaderScope();
    ClassLoaderScope rootProjectScope = baseScope.createChild("root-project");

    ProjectStateRegistry projectStateRegistry = buildServices.get(ProjectStateRegistry.class);
    ProjectState projectState = projectStateRegistry.registerProject(build, projectDescriptor);
    projectState.createMutableModel(rootProjectScope, baseScope);
    ProjectInternal project = projectState.getMutableModel();

    gradle.setRootProject(project);
    gradle.setDefaultProject(project);

    // Lock root project; this won't ever be released as ProjectBuilder has no lifecycle
    coordinationService.withStateLock(
        DefaultResourceLockCoordinationService.lock(project.getOwner().getAccessLock()));

    return project;
  }

  private File createTempDirectory(String purpose) {
    try {
      return Files.createTempDirectory("resolver-" + purpose).toFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ServiceRegistry getGlobalServices() {
    return ServiceRegistryBuilder.builder()
        .displayName("global services")
        .parent(LoggingServiceRegistry.newNestedLogging())
        .parent(NativeServices.getInstance())
        .provider(new ProjectBuilderServices())
        .build();
  }

  private GradleUserHomeScopeServiceRegistry userHomeServicesOf(ServiceRegistry globalServices) {
    return globalServices.get(GradleUserHomeScopeServiceRegistry.class);
  }

  private static class TestRootBuild extends AbstractBuildState implements RootBuildState {
    private final GradleInternal gradle;
    final BuildScopeServices buildServices;

    public TestRootBuild(
        File rootProjectDir, StartParameterInternal startParameter, BuildTreeState buildTreeState) {
      super(
          buildTreeState,
          BuildDefinition.fromStartParameter(startParameter, rootProjectDir, null),
          null);
      this.buildServices = getBuildServices();
      this.gradle = buildServices.get(GradleInternal.class);
    }

    @Override
    protected BuildScopeServices prepareServices(
        BuildTreeState buildTree,
        BuildDefinition buildDefinition,
        BuildModelControllerServices.Supplier supplier) {
      final File homeDir = new File(buildDefinition.getBuildRootDir(), "gradleHome");
      return new TestBuildScopeServices(buildTree.getServices(), homeDir, supplier);
    }

    @Override
    public BuildScopeServices getBuildServices() {
      return super.getBuildServices();
    }

    @Override
    public void ensureProjectsLoaded() {}

    @Override
    public void ensureProjectsConfigured() {}

    @Override
    public BuildIdentifier getBuildIdentifier() {
      return DefaultBuildIdentifier.ROOT;
    }

    @Override
    public Path getIdentityPath() {
      return Path.ROOT;
    }

    @Override
    public boolean isImplicitBuild() {
      return false;
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
      return Path.ROOT;
    }

    @Override
    public Path calculateIdentityPathForProject(Path projectPath) {
      return projectPath;
    }

    @Override
    public StartParameterInternal getStartParameter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T run(Function<? super BuildTreeLifecycleController, T> action) {
      throw new UnsupportedOperationException();
    }

    @Override
    public IncludedBuildInternal getModel() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> getAvailableModules() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProjectComponentIdentifier idToReferenceProjectFromAnotherBuild(
        ProjectComponentIdentifier identifier) {
      throw new UnsupportedOperationException();
    }

    @Override
    public File getBuildRootDir() {
      return getBuildServices().get(BuildDefinition.class).getBuildRootDir();
    }

    @Override
    public GradleInternal getMutableModel() {
      return gradle;
    }
  }
}
