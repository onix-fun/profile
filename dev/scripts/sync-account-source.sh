#!/bin/sh
set -eu

repo_url="${ACCOUNT_REPOSITORY_URL:-https://github.com/onix-fun/account.git}"
ref="${ACCOUNT_REPOSITORY_REF:-codex/account-organizations}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
target_dir="$script_dir/../../account"
patch_dir="$script_dir/../account-patches"

if [ -d "$target_dir/.git" ]; then
    if [ "${ACCOUNT_SOURCE_FORCE_SYNC:-0}" != "1" ] && [ -n "$(git -C "$target_dir" status --porcelain)" ]; then
        revision=$(git -C "$target_dir" rev-parse --short HEAD)
        branch=$(git -C "$target_dir" branch --show-current || true)
        echo "Account source has local changes; skipping sync at $target_dir (${branch:-detached}:$revision)"
        echo "Set ACCOUNT_SOURCE_FORCE_SYNC=1 to discard local checkout state and resync from $ref."
        exit 0
    fi

    if [ -d "$patch_dir" ]; then
        for patch in "$patch_dir"/*.patch; do
            [ -e "$patch" ] || continue
            if git -C "$target_dir" apply --reverse --check "$patch" >/dev/null 2>&1; then
                git -C "$target_dir" apply --reverse "$patch"
            fi
        done
    fi
    git -C "$target_dir" fetch --depth 1 origin "$ref"
    if git -C "$target_dir" show-ref --verify --quiet "refs/heads/$ref" &&
        ! git -C "$target_dir" merge-base --is-ancestor "$ref" FETCH_HEAD >/dev/null 2>&1; then
        revision=$(git -C "$target_dir" rev-parse --short "$ref")
        echo "Account source has local commits on $ref; keeping local branch at $revision"
        git -C "$target_dir" checkout -q "$ref"
    else
        git -C "$target_dir" checkout -q FETCH_HEAD
    fi
else
    rm -rf "$target_dir"
    git clone --depth 1 --branch "$ref" "$repo_url" "$target_dir"
fi

if [ -d "$patch_dir" ]; then
    for patch in "$patch_dir"/*.patch; do
        [ -e "$patch" ] || continue
        if git -C "$target_dir" apply --check "$patch" >/dev/null 2>&1; then
            git -C "$target_dir" apply "$patch"
            echo "Applied account patch $(basename "$patch")"
        elif git -C "$target_dir" apply --reverse --check "$patch" >/dev/null 2>&1; then
            echo "Account patch already applied $(basename "$patch")"
        else
            echo "Failed to apply account patch $(basename "$patch")" >&2
            exit 1
        fi
    done
fi

revision=$(git -C "$target_dir" rev-parse --short HEAD)
echo "Account source is ready at $target_dir ($revision)"
