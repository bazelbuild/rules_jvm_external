package com.github.bazelbuild.rules_jvm_external.resolver.maven;

import java.util.function.Consumer;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;

class DependencyNodeVisitor implements DependencyVisitor {

  private final Consumer<DependencyNode> onNode;

  public DependencyNodeVisitor(Consumer<DependencyNode> onNode) {
    this.onNode = onNode;
  }

  @Override
  public boolean visitEnter(DependencyNode node) {
    return !node.getDependency().isOptional();
  }

  @Override
  public boolean visitLeave(DependencyNode node) {
    onNode.accept(node);
    return true;
  }
}
