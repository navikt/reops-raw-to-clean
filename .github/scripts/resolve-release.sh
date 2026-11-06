#!/usr/bin/env bash
set -euo pipefail

# Resolve annotated deployment tags, never branch names or arbitrary Git expressions.
# Called before checking out the selected release's code and Nais manifest.
mode=${RELEASE_MODE:?}
environment=${ENVIRONMENT:?}
tag=${RELEASE_TAG:-}

case "$mode:$environment" in
  promote:prod) pattern=dev- ;;
  redeploy:dev|rebuild:dev) pattern=dev- ;;
  rebuild:prod) pattern=prod- ;;
  *) echo "::error::Unsupported release mode/environment: $mode/$environment"; exit 1 ;;
esac

if [[ -z "$tag" ]]; then
  tag=$(git for-each-ref --sort=-creatordate --count=1 --format='%(refname:short)' "refs/tags/$pattern*")
  if [[ -z "$tag" ]]; then
    if [[ "$mode" == rebuild && "$GITHUB_EVENT_NAME" == schedule ]]; then
      echo "::notice::No $pattern* tag yet; skipping refresh. Deploy a release first."
      exit 0
    fi
    echo "::error::No $pattern* release tag found. Deploy a release first."
    exit 1
  fi
fi

case "$mode:$tag" in
  promote:dev-*|promote:prod-*|redeploy:dev-*|rebuild:"$environment"-*) ;;
  *) echo "::error::Tag does not match the selected environment: $tag"; exit 1 ;;
esac

ref="refs/tags/$tag"
if ! git check-ref-format "$ref" || ! git show-ref --verify --quiet "$ref"; then
  echo "::error::Release tag not found or invalid: $tag"
  exit 1
fi
if [[ "$(git cat-file -t "$ref")" != tag ]]; then
  echo "::error::Expected an annotated deployment tag: $tag"
  exit 1
fi

sha=$(git rev-parse "$ref^{commit}")
if [[ "$mode" != rebuild ]]; then
  image=$(git for-each-ref --format='%(contents)' "$ref" | sed -n 's/^Image: //p')
  if [[ ! "$image" =~ ^europe-north1-docker\.pkg\.dev/[^[:space:]]+$ ]]; then
    echo "::error::Release tag has no valid Image metadata: $tag"
    exit 1
  fi
  echo "image=$image" >> "$GITHUB_OUTPUT"
fi

echo "Deploying $tag ($sha)"
git checkout --detach "$sha"
echo "sha=$sha" >> "$GITHUB_OUTPUT"
