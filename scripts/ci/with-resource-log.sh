#!/usr/bin/env bash
# Runs a command while sampling the runner's memory, swap, disk and heaviest processes.
#
# A hosted runner that runs out of memory is shut down with no error from Gradle, and the
# log of a run that is cancelled that way is often lost entirely. Samples go to the step
# output as they are taken, so whatever the runner flushed before it died still shows the
# trend, and the complete series is kept in $CI_DIAGNOSTICS_DIR for the artifact upload.
#
# Usage: with-resource-log.sh COMMAND [ARGS...]
set -uo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: ${0##*/} COMMAND [ARGS...]" >&2
  exit 2
fi

interval="${CI_RESOURCE_SAMPLE_SECONDS:-20}"
diagnostics_dir="${CI_DIAGNOSTICS_DIR:-${RUNNER_TEMP:-/tmp}/ci-diagnostics}"
mkdir -p "$diagnostics_dir"
series="$diagnostics_dir/resources.log"

sample() {
  local available_mb swap_used_mb disk_free_gb load
  available_mb="$(awk '/MemAvailable/ {printf "%d", $2 / 1024}' /proc/meminfo)"
  swap_used_mb="$(awk '/SwapTotal/ {t = $2} /SwapFree/ {f = $2} END {printf "%d", (t - f) / 1024}' /proc/meminfo)"
  disk_free_gb="$(df --output=avail -BG / | awk 'NR == 2 {gsub("G", ""); print}')"
  load="$(cut -d' ' -f1-3 /proc/loadavg)"
  printf '%s available=%sMB swap_used=%sMB disk_free=%sGB load=%s top_rss=' \
    "$(date -u +%H:%M:%S)" "$available_mb" "$swap_used_mb" "$disk_free_gb" "$load"
  ps -eo rss=,comm=,args= --sort=-rss | head -n 5 \
    | awk '{c = $2; if (c == "java") {for (i = 3; i <= NF; i++) if ($i ~ /^org\.|^-jar$|Daemon|Worker|Compiler|Karma/) {c = "java:" substr($i, length($i) > 40 ? length($i) - 39 : 1); break}} printf "%s=%dMB ", c, $1 / 1024}'
  printf '\n'
}

report_kernel_kills() {
  echo "::group::Kernel memory events"
  { sudo -n dmesg 2> /dev/null || dmesg 2> /dev/null || echo "dmesg is not readable on this runner"; } \
    | grep -iE 'out of memory|oom-kill|killed process|invoked oom' | tail -n 20 \
    | tee -a "$diagnostics_dir/kernel-memory-events.log"
  echo "::endgroup::"
}

{
  echo "::group::Runner resources before the build"
  echo "cpus=$(nproc) $(grep -E 'MemTotal|SwapTotal' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
  df -h / /mnt 2> /dev/null
  echo "::endgroup::"
} | tee -a "$series"

(
  while true; do
    sample | tee -a "$series"
    sleep "$interval"
  done
) &
sampler=$!
trap 'kill "$sampler" 2>/dev/null' EXIT

"$@"
status=$?

kill "$sampler" 2> /dev/null
wait "$sampler" 2> /dev/null
sample | tee -a "$series"
report_kernel_kills
exit "$status"
