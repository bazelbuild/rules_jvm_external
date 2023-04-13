package com.github.bazelbuild.rules_jvm_external.resolver.gradle;

import static com.google.common.graph.GraphBuilder.directed;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.MutableGraph;

import java.util.Collection;
import java.util.Objects;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.DefaultResolvableArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.DependencyArtifactsVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphEdge;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphNode;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.RootGraphNode;
import org.gradle.api.internal.artifacts.transform.VariantSelector;
import org.gradle.api.specs.Specs;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;

public class CoordinatesVisitor implements DependencyArtifactsVisitor {

  private final VariantSelector selector;
  private final MutableGraph<Holder> graph;

  public CoordinatesVisitor(VariantSelector selector) {
    this.selector = Objects.requireNonNull(selector, "Selector must be set");
    this.graph = directed().build();
  }

  public Graph<Coordinates> getDependencyGraph() {
    MutableGraph<Coordinates> toReturn = GraphBuilder.directed().build();

    graph.nodes().stream()
        .filter(h -> h.coordinates != null)
        .forEach(h -> toReturn.addNode(h.coordinates));
    graph.edges().stream()
        .filter(pair -> pair.source().coordinates != null)
        .filter(pair -> pair.target().coordinates != null)
        .forEach(
            pair -> {
              toReturn.putEdge(pair.source().coordinates, pair.target().coordinates);
            });

    return ImmutableGraph.copyOf(toReturn);
  }

  @Override
  public void startArtifacts(RootGraphNode root) {}

  @Override
  public void visitNode(DependencyGraphNode node) {}

  @Override
  public void visitArtifacts(
      DependencyGraphNode from, DependencyGraphNode to, int artifactSetId, ArtifactSet artifacts) {
    if (!to.isSelected()) {
      return;
    }

    Collection<? extends DependencyGraphEdge> edges = to.getIncomingEdges();
    edges.forEach(edge -> {
      System.out.println(edge);
      Dependency dep = edge.getOriginalDependency();
      if (dep instanceof ExternalModuleDependency) {
        ExternalModuleDependency emd = (ExternalModuleDependency) dep;
        String requiredVersion = emd.getVersionConstraint().getRequiredVersion();
        System.out.printf("%s:%s:%s -> %s%n", dep.getGroup(), dep.getName(), dep.getVersion(), requiredVersion);
      }
    });

    // The root node of the graph is a project, but all other nodes should be dependencies we want
    // to track
    Holder toHolder = getNode(to.getNodeId());
    graph.addNode(toHolder);

    if (!from.isRoot()) {
      Holder fromHolder = getNode(from.getNodeId());
      graph.addNode(fromHolder);
      graph.putEdge(fromHolder, toHolder);
    }

    if (toHolder.coordinates == null) {
      ResolvedArtifactSet selected = artifacts.select(Specs.SATISFIES_ALL, selector);
      selected.visitExternalArtifacts(
          resolvableArtifact -> {
            if (toHolder.coordinates != null) {
              return;
            }

            if (resolvableArtifact instanceof DefaultResolvableArtifact) {
              ModuleComponentIdentifier gav =
                  (ModuleComponentIdentifier) resolvableArtifact.getId().getComponentIdentifier();
              ResolvedArtifact artifact = resolvableArtifact.toPublicView();

              toHolder.coordinates =
                  new Coordinates(
                      gav.getGroup(),
                      gav.getModule(),
                      artifact.getExtension(),
                      artifact.getClassifier(),
                      gav.getVersion());
            }
          });
    }
  }

  @Override
  public void visitArtifacts(
      DependencyGraphNode from,
      LocalFileDependencyMetadata fileDependency,
      int artifactSetId,
      ArtifactSet artifactSet) {
    System.err.println("Hello");
  }

  @Override
  public void finishArtifacts() {}

  private Holder getNode(Long id) {
    return graph.nodes().stream().filter(h -> id.equals(h.id)).findFirst().orElse(new Holder(id));
  }

  private static class Holder {
    private final Long id;
    private Coordinates coordinates;

    public Holder(Long id) {
      this.id = id;
    }

    public void setCoordinates(Coordinates coordinates) {
      this.coordinates = coordinates;
    }
  }
}
