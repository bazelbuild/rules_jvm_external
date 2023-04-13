package com.github.bazelbuild.rules_jvm_external.resolver;

import com.github.bazelbuild.rules_jvm_external.Coordinates;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public class Artifact {

  private final Coordinates coordinates;
  private final Set<Coordinates> exclusions;

  public Artifact(Coordinates coordinates, Coordinates... exclusions) {
    this(coordinates, ImmutableSet.copyOf(exclusions));
  }

  public Artifact(Coordinates coordinates, Collection<Coordinates> exclusions) {
    this.coordinates = Objects.requireNonNull(coordinates);
    this.exclusions = ImmutableSet.copyOf(exclusions);
  }

  public Coordinates getCoordinates() {
    return coordinates;
  }

  public Set<Coordinates> getExclusions() {
    return exclusions;
  }

  @Override
  public String toString() {
    if (exclusions.isEmpty()) {
      return "{" + coordinates + "}";
    }
    return "{" + coordinates + ", exclusions=" + exclusions + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Artifact that = (Artifact) o;
    return Objects.equals(this.getCoordinates(), that.getCoordinates())
        && Objects.equals(this.getExclusions(), that.getExclusions());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getCoordinates(), getExclusions());
  }
}
