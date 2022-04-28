#!/bin/bash -e
#
# Replace the no-op fallback license classifier with one implemented in
# a repository of our choosing


if [[ $# != 1 ]] ; then
  echo 'usage: use_license_classifier.sh REPOSITORY_NAME'
  exit 1
fi

cat >license_classifier.bzl  <<INP
# This file is generated by the patch command "use_license_classifier.sh".
# It overwrites the default license_classifier.bzl

load("@${1}//:license_classifier.bzl", _ll = "lookup_license")

def lookup_license(url=None, sha256=None, maven_id=None):
    return _ll(url=url, sha256=sha256, maven_id=maven_id)
INP