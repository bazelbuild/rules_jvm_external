package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.graph.Graph;

public interface Resolver {

  Graph<Coordinates> resolve(ResolutionRequest request);
}
