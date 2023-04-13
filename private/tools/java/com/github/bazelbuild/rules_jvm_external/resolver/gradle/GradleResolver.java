package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import static com.github.bazelbuild.rules_jvm_external.resolver.gradle.ProjectFactory.createProject;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.github.bazelbuild.rules_jvm_external.resolver.Artifact;
import com.github.bazelbuild.rules_jvm_external.resolver.ResolutionRequest;
import com.github.bazelbuild.rules_jvm_external.resolver.Resolver;
import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.events.PhaseEvent;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.internal.artifacts.ArtifactDependencyResolver;
import org.gradle.api.internal.artifacts.BaseRepositoryFactory;
import org.gradle.api.internal.artifacts.ComponentSelectorConverter;
import org.gradle.api.internal.artifacts.DependencyServices;
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules;
import org.gradle.api.internal.artifacts.VariantTransformRegistry;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.artifacts.ivyservice.InMemoryResolutionResultBuilder;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyResolver;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.DependencyArtifactsVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.CompositeDependencyArtifactsVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.CompositeDependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult.ResolutionFailureCollector;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.api.internal.artifacts.transform.ArtifactTransforms;
import org.gradle.api.internal.artifacts.transform.ConsumerProvidedVariantFinder;
import org.gradle.api.internal.artifacts.transform.DefaultArtifactTransforms;
import org.gradle.api.internal.artifacts.transform.DefaultTransformedVariantFactory;
import org.gradle.api.internal.artifacts.transform.VariantSelector;
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.specs.Specs;
import org.gradle.execution.ProjectExecutionServices;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.services.LoggingServiceRegistry;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;

public class GradleResolver implements Resolver {

  private static final Logger LOG = Logger.getLogger(GradleResolver.class.getName());
  private final Netrc netrc;
  private final EventListener listener;

  public GradleResolver(Netrc netrc, EventListener listener) {
    this.netrc = netrc;
    this.listener = listener;
  }

  private ServiceRegistry initializeServices(Project project, Path homeDir) {
    LOG.fine("Initializing Gradle services");

    // We don't want to capture stdout
    LoggingServiceRegistry loggingServices = LoggingServiceRegistry.newEmbeddableLogging();

    LoggingManagerInternal loggingManager =
        loggingServices.getFactory(LoggingManagerInternal.class).create();
    loggingManager.setLevelInternal(LogLevel.WARN);
    loggingManager.start();

    NativeServices.initializeOnWorker(homeDir.toFile());
    return ServiceRegistryBuilder.builder()
        .displayName("Global services")
        .parent(loggingServices)
        .parent(NativeServices.getInstance())
        .parent(new ProjectExecutionServices((ProjectInternal) project))
        .provider(new ProjectBuilderServices())
        .provider(new DependencyServices())
        .build();
  }

  public ResolutionAwareRepository addRepository(
      ServiceRegistry services, Project project, URI uri) {
    MavenArtifactRepository maven =
        services.get(BaseRepositoryFactory.class).createMavenRepository();
    maven.setName(uri.toString());
    maven.setUrl(uri);
    maven.setAllowInsecureProtocol(true);
    if (netrc != null) {
      Netrc.Credential credential = netrc.credentials().get(uri.getHost());
      if (credential == null) {
        credential = netrc.defaultCredential();
      }

      if (credential != null) {
        PasswordCredentials creds = maven.getCredentials();
        creds.setUsername(credential.login());
        creds.setPassword(credential.password());
      }
    }
    project.getRepositories().add(maven);

    return (ResolutionAwareRepository) maven;
  }

  @Override
  public Graph<Coordinates> resolve(ResolutionRequest request) {
    listener.onEvent(new PhaseEvent("Initializing"));

    Path homeDir = request.getUserHome();
    System.setProperty("maven.repo.local", request.getLocalCache().toString());

    Project project = createProject(homeDir.toFile());

    ServiceRegistry services = initializeServices(project, homeDir);

    List<ResolutionAwareRepository> resolutionAwareRepositories = new LinkedList<>();

    request
        .getRepositories()
        .forEach(
            uri -> {
              ResolutionAwareRepository repo = addRepository(services, project, uri);
              resolutionAwareRepositories.add(repo);
            });

    ConfigurationContainer container = project.getConfigurations();
    ConfigurationInternal config = (ConfigurationInternal) container.create("resolve-config");

    // Required to enable BOMs to be resolved. I do not know why
    project.getPlugins().apply("java");

    listener.onEvent(new PhaseEvent("Preparing model"));
    LOG.fine("Parsing coordinates");

    DependencyHandler dependencyHandler = services.get(DependencyHandler.class);

    for (Coordinates bom : request.getBoms()) {
      Dependency bomDep =
          dependencyHandler.enforcedPlatform(
              String.format("%s:%s:%s", bom.getGroupId(), bom.getArtifactId(), bom.getVersion()));
      config.getDependencies().add(bomDep);
    }

    for (Artifact dependency : request.getDependencies()) {
      String gradleCoords = asGradleCoordinates(dependency.getCoordinates());
      Dependency dep = dependencyHandler.create(gradleCoords);
      if (!(dep instanceof ModuleDependency)) {
        throw new IllegalArgumentException(
            "Coordinates are not resolvable: " + dependency.getCoordinates());
      }

      dependency.getExclusions().stream()
          .map(ex -> Map.of("group", ex.getGroupId(), "module", ex.getArtifactId()))
          .forEach(ex -> ((ModuleDependency) dep).exclude(ex));

      config.getDependencies().add(dep);
    }
    request
        .getGlobalExclusions()
        .forEach(
            ex -> config.exclude(Map.of("group", ex.getGroupId(), "module", ex.getArtifactId())));

    // BuildActionsFactory.runBuildInProcess is where this all starts
    ArtifactDependencyResolver resolver = services.get(ArtifactDependencyResolver.class);
    GlobalDependencyResolutionRules resolutionRules =
        services.get(GlobalDependencyResolutionRules.class);
    DependencyGraphVisitor resultBuilder = new InMemoryResolutionResultBuilder();
    ComponentSelectorConverter componentSelectorConverter =
        services.get(ComponentSelectorConverter.class);
    ResolutionFailureCollector failureCollector =
        new ResolutionFailureCollector(componentSelectorConverter);
    AttributesSchemaInternal attributesSchema =
        (AttributesSchemaInternal) project.getDependencies().getAttributesSchema();
    ArtifactTypeRegistry typeRegistry = services.get(ArtifactTypeRegistry.class);

    ImmutableAttributesFactory attributesFactory = services.get(ImmutableAttributesFactory.class);
    VariantTransformRegistry variantTransformRegistry =
        services.get(VariantTransformRegistry.class);
    DefaultTransformedVariantFactory transformedVariantFactory =
        services.get(DefaultTransformedVariantFactory.class);

    // DefaultConfigurationResolver.resolveGraph:154
    ResolutionStrategyInternal resolutionStrategy = config.getResolutionStrategy();
    DependencyGraphVisitor graphVisitor =
        new CompositeDependencyGraphVisitor(resultBuilder, failureCollector);

    resolutionStrategy.confirmUnlockedConfigurationResolved(config.getName());

    ArtifactTransforms artifactTransforms =
        new DefaultArtifactTransforms(
            new ConsumerProvidedVariantFinder(
                variantTransformRegistry, attributesSchema, attributesFactory),
            attributesSchema,
            attributesFactory,
            transformedVariantFactory);
    VariantSelector variantSelector =
        artifactTransforms.variantSelector(
            ImmutableAttributes.EMPTY, false, false, config.getDependenciesResolver());

    CoordinatesVisitor coordinatesVisitor = new CoordinatesVisitor(variantSelector);
    List<DependencyArtifactsVisitor> allVisitors = ImmutableList.of(coordinatesVisitor);
    CompositeDependencyArtifactsVisitor artifactsVisitor =
        new CompositeDependencyArtifactsVisitor(allVisitors);

    ProjectDependencyResolver projectDependencyResolver =
        services.get(ProjectDependencyResolver.class);

    LOG.fine("Starting dependency resolution");
    listener.onEvent(new PhaseEvent("Resolving dependencies"));

    resolver.resolve(
        config,
        resolutionAwareRepositories,
        resolutionRules,
        Specs.satisfyAll(),
        graphVisitor,
        artifactsVisitor,
        attributesSchema,
        typeRegistry,
        projectDependencyResolver,
        true);

    LOG.fine("Resolution complete");
    Set<UnresolvedDependency> failures = failureCollector.complete(ImmutableSet.of());
    if (!failures.isEmpty()) {
      System.err.println("Unable to resolve inputs");
      for (UnresolvedDependency failure : failures) {
        LOG.warning(failure.getProblem().getMessage());
      }
      throw new IllegalStateException("Unable to resolve inputs");
    }



//    config.getDependencies().stream()
//            .filter(dep -> dep instanceof ExternalModuleDependency)
//            .map(dep -> (ExternalModuleDependency) dep)
//            .forEach(dep -> {
//              String requiredVersion = dep.getVersionConstraint().getRequiredVersion();
//              System.err.println(dep + " -> " + requiredVersion);
//            });

    return coordinatesVisitor.getDependencyGraph();
  }

  private String asGradleCoordinates(Coordinates coords) {
    StringBuilder gradleCoords = new StringBuilder();
    gradleCoords.append(coords.getGroupId()).append(":").append(coords.getArtifactId());
    if (!Strings.isNullOrEmpty(coords.getVersion())) {
      gradleCoords.append(":").append(coords.getVersion());
    }
    if (!Strings.isNullOrEmpty(coords.getClassifier())) {
      gradleCoords.append(":").append(coords.getClassifier());
    }
    if (!Strings.isNullOrEmpty(coords.getExtension()) && !"jar".equals(coords.getExtension())) {
      gradleCoords.append("@").append(coords.getExtension());
    }

    return gradleCoords.toString();
  }
}
