#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/bin:$PATH"

usage() {
  cat <<'EOF'
Usage:
  scripts/run_corpseflower.sh [--verbose] <input> <output> [log-file]

Examples:
  scripts/run_corpseflower.sh /c/tmp/input.jar /c/tmp/output
  scripts/run_corpseflower.sh --verbose /c/tmp/input.jar /c/tmp/output /c/tmp/corpseflower.log

Optional environment:
  CORPSEFLOWER_EXTRA_ARGS="--no-quality-gate --threads 1"
EOF
}

to_shell_path() {
  local value="$1"
  if [[ "$value" =~ ^[A-Za-z]:\\ ]]; then
    cygpath -u "$value"
  else
    printf '%s\n' "$value"
  fi
}

to_windows_path() {
  local value="$1"
  if [[ "$value" == /* ]]; then
    cygpath -w "$value"
  else
    printf '%s\n' "$value"
  fi
}

verbose_flag=""
if [[ "${1:-}" == "--verbose" ]]; then
  verbose_flag="--verbose"
  shift
fi

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 1
fi

input_path="$1"
output_path="$2"
log_file="${3:-}"

script_path="${BASH_SOURCE[0]}"
case "$script_path" in
  */*) script_dir="${script_path%/*}" ;;
  *) script_dir="." ;;
esac
script_dir="$(cd "$script_dir" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
input_shell_path="$(to_shell_path "$input_path")"
output_shell_path="$(to_shell_path "$output_path")"
log_shell_path=""
if [[ -n "$log_file" ]]; then
  log_shell_path="$(to_shell_path "$log_file")"
fi

input_java_path="$(to_windows_path "$input_shell_path")"
output_java_path="$(to_windows_path "$output_shell_path")"

if [[ -z "${JAVA_HOME:-}" && -d "/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot" ]]; then
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

shopt -s nullglob
cf_jars=("$repo_root"/build/libs/corpseflower-*-all.jar)
shopt -u nullglob
if [[ ${#cf_jars[@]} -eq 0 ]]; then
  echo "Corpseflower shadow jar not found under $repo_root/build/libs" >&2
  echo "Build it first with ./gradlew shadowJar" >&2
  exit 1
fi
cf_jar="${cf_jars[0]}"
cf_jar_java_path="$(to_windows_path "$cf_jar")"

mkdir -p "${output_shell_path%/*}"
rm -rf "$output_shell_path"
mkdir -p "$output_shell_path"

cmd=(java -cp "$cf_jar_java_path" org.corpseflower.CorpseflowerMain)
if [[ -n "${CORPSEFLOWER_EXTRA_ARGS:-}" ]]; then
  read -r -a extra_args <<< "$CORPSEFLOWER_EXTRA_ARGS"
  cmd+=("${extra_args[@]}")
fi
if [[ -n "$verbose_flag" ]]; then
  cmd+=("$verbose_flag")
fi
cmd+=("$input_java_path" "$output_java_path")

echo "Running: ${cmd[*]}"

if [[ -n "$log_file" ]]; then
  mkdir -p "${log_shell_path%/*}"
  "${cmd[@]}" >"$log_shell_path" 2>&1
  echo "Log written to $log_shell_path"
else
  "${cmd[@]}"
fi
