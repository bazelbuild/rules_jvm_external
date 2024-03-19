package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import java.util.Objects;

public class Conflict {

  private final Coordinates resolved;
  private final Coordinates requested;

  public Conflict(Coordinates resolved, Coordinates requested) {
    this.resolved = resolved;
    this.requested = requested;
  }

  public Coordinates getResolved() {
    return resolved;
  }

  public Coordinates getRequested() {
    return requested;
  }

  @Override
  public String toString() {
    return resolved + " -> " + requested;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Conflict)) {
      return false;
    }
    Conflict that = (Conflict) o;
    return Objects.equals(this.resolved, that.resolved)
        && Objects.equals(this.requested, that.requested);
  }

  @Override
  public int hashCode() {
    return Objects.hash(resolved, requested);
  }
}
