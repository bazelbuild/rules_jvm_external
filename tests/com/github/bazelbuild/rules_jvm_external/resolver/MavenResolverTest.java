package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.resolver.events.EventListener;
import com.github.bazelbuild.rules_jvm_external.resolver.maven.MavenResolver;
import com.github.bazelbuild.rules_jvm_external.resolver.netrc.Netrc;

public class MavenResolverTest extends ResolverTestBase {

  @Override
  protected Resolver getResolver(Netrc netrc, EventListener listener) {
    return new MavenResolver(netrc, listener);
  }
}
