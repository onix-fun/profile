#!/bin/sh
set -eu

repo_url="${ACCOUNT_REPOSITORY_URL:-https://github.com/onix-fun/account.git}"
ref="${ACCOUNT_REPOSITORY_REF:-main}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
target_dir="$script_dir/../account-src"
patch_dir="$script_dir/../account-patches"

if [ -d "$target_dir/.git" ]; then
    if [ -d "$patch_dir" ]; then
        for patch in "$patch_dir"/*.patch; do
            [ -e "$patch" ] || continue
            if git -C "$target_dir" apply --reverse --check "$patch" >/dev/null 2>&1; then
                git -C "$target_dir" apply --reverse "$patch"
            fi
        done
    fi
    git -C "$target_dir" fetch --depth 1 origin "$ref"
    git -C "$target_dir" checkout -q FETCH_HEAD
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
