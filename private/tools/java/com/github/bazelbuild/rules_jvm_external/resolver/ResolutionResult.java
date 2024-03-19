package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.graph.Graph;
import java.util.Set;

public class ResolutionResult {

  private final Graph<Coordinates> resolution;
  private final Set<Conflict> conflicts;

  public ResolutionResult(Graph<Coordinates> resolution, Set<Conflict> conflicts) {
    this.resolution = resolution;
    this.conflicts = conflicts;
  }

  public Graph<Coordinates> getResolution() {
    return resolution;
  }

  public Set<Conflict> getConflicts() {
    return conflicts;
  }
}
