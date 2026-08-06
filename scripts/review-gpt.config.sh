#!/usr/bin/env bash

review_gpt_config_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
review_gpt_repo_root="$(CDPATH= cd -- "$review_gpt_config_dir/.." && pwd -P)"
source "$review_gpt_config_dir/repo-tools.config.sh"

review_gpt_invalid_browser_lane() {
  echo "Error: unsupported ReviewGPT browser lane '$1'. Use random, eragon, phlebas, or mountain." >&2
}

review_gpt_browser_lane_display_name() {
  case "$1" in
    eragon) printf '%s\n' "Eragon" ;;
    phlebas) printf '%s\n' "Phlebas" ;;
    mountain) printf '%s\n' "Mountain" ;;
    *)
      review_gpt_invalid_browser_lane "$1"
      return 1
      ;;
  esac
}

review_gpt_browser_lane_port() {
  case "$1" in
    eragon) printf '%s\n' "9448" ;;
    phlebas) printf '%s\n' "9442" ;;
    mountain) printf '%s\n' "9450" ;;
    *)
      review_gpt_invalid_browser_lane "$1"
      return 1
      ;;
  esac
}

review_gpt_browser_lane_data_dir() {
  local lane_display
  lane_display="$(review_gpt_browser_lane_display_name "$1")" || return 1
  printf '%s\n' "$HOME/Library/Application Support/MurphReviewGPT/$lane_display"
}

review_gpt_browser_lane_endpoint_responds() {
  local lane_port
  command -v curl >/dev/null 2>&1 || return 1
  lane_port="$(review_gpt_browser_lane_port "$1")" || return 1
  curl --silent --show-error --fail --max-time 1 \
    "http://127.0.0.1:$lane_port/json/version" >/dev/null 2>&1
}

review_gpt_browser_lane_listener_pid() {
  local lane_port
  local listener_pids
  command -v lsof >/dev/null 2>&1 || return 1
  lane_port="$(review_gpt_browser_lane_port "$1")" || return 1
  listener_pids="$(
    lsof -nP -iTCP:"$lane_port" -sTCP:LISTEN -Fp 2>/dev/null \
      | sed -nE 's/^p([0-9]+)$/\1/p'
  )"
  [[ "$listener_pids" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$listener_pids"
}

review_gpt_browser_lane_has_owned_cdp() {
  local lane_port
  local lane_data_dir
  local listener_pid
  local listener_command
  command -v ps >/dev/null 2>&1 || return 1
  review_gpt_browser_lane_endpoint_responds "$1" || return 1
  lane_port="$(review_gpt_browser_lane_port "$1")" || return 1
  lane_data_dir="$(review_gpt_browser_lane_data_dir "$1")" || return 1
  listener_pid="$(review_gpt_browser_lane_listener_pid "$1")" || return 1
  listener_command="$(ps -p "$listener_pid" -o command= 2>/dev/null)" || return 1
  [[ "$listener_command" == *"--remote-debugging-port=$lane_port"* ]] || return 1
  [[ "$listener_command" == *"--user-data-dir=$lane_data_dir"* ]] || return 1
}

review_gpt_browser_lane_is_usable() {
  local lane_data_dir
  lane_data_dir="$(review_gpt_browser_lane_data_dir "$1")" || return 1
  if review_gpt_browser_lane_has_owned_cdp "$1"; then
    return 0
  fi
  review_gpt_browser_lane_endpoint_responds "$1" && return 1
  review_gpt_browser_lane_listener_pid "$1" >/dev/null 2>&1 && return 1
  [[ ! -e "$lane_data_dir/SingletonLock" && ! -L "$lane_data_dir/SingletonLock" ]]
}

requested_lane="${REVIEW_GPT_BROWSER_LANE:-${MURPH_REVIEW_GPT_BROWSER_LANE:-random}}"
requested_lane="$(printf '%s' "$requested_lane" | tr '[:upper:]' '[:lower:]')"

case "$requested_lane" in
  "" | auto | random)
    browser_lanes=(eragon phlebas mountain)
    usable_lanes=()
    for candidate_lane in "${browser_lanes[@]}"; do
      if review_gpt_browser_lane_is_usable "$candidate_lane"; then
        usable_lanes+=("$candidate_lane")
      fi
    done
    if [[ "${#usable_lanes[@]}" -gt 0 ]]; then
      selected_lane="${usable_lanes[$((RANDOM % ${#usable_lanes[@]}))]}"
    else
      echo "Error: no usable ReviewGPT browser lane is available; close or pin a healthy lane and retry." >&2
      return 1 2>/dev/null || exit 1
    fi
    ;;
  aragon | eragon)
    selected_lane="eragon"
    ;;
  phlebas | mountain)
    selected_lane="$requested_lane"
    ;;
  *)
    review_gpt_invalid_browser_lane "$requested_lane"
    return 1 2>/dev/null || exit 1
    ;;
esac

if ! review_gpt_browser_lane_is_usable "$selected_lane"; then
  echo "Error: selected ReviewGPT browser lane '$selected_lane' is not the owned managed profile or is locked." >&2
  return 1 2>/dev/null || exit 1
fi

selected_display="$(review_gpt_browser_lane_display_name "$selected_lane")" || {
  return 1 2>/dev/null || exit 1
}
selected_port="$(review_gpt_browser_lane_port "$selected_lane")" || {
  return 1 2>/dev/null || exit 1
}
profiles_root="${MURPH_REVIEW_GPT_PROFILES_ROOT:-$review_gpt_repo_root/../murph/output-packages/review-gpt-profiles}"
selected_app="$profiles_root/$selected_lane/$selected_display.app"

selected_binary="$selected_app/Contents/MacOS/Brave Browser"
if [[ -x "$selected_binary" ]]; then
  browser_binary_path="${browser_binary_path:-$selected_binary}"
else
  browser_binary_path="${browser_binary_path:-/Applications/Brave Browser.app/Contents/MacOS/Brave Browser}"
fi

managed_browser_user_data_dir="${managed_browser_user_data_dir:-$(review_gpt_browser_lane_data_dir "$selected_lane")}"
managed_browser_profile="${managed_browser_profile:-Default}"
managed_browser_port="${managed_browser_port:-$selected_port}"
managed_browser_background_mode="${managed_browser_background_mode:-balanced}"
export REVIEW_GPT_SELECTED_BROWSER_LANE="$selected_lane"

name_prefix="murph-android-$selected_lane-chatgpt-audit"
package_script="scripts/package-review-context.sh"
repo_context_url=""
attach_artifacts=1
include_tests=1
include_docs=1
preset_dir="scripts/chatgpt-review-presets"
app_connector="current"
model="gpt-5.6-sol"
thinking="current"

review_gpt_register_dir_preset "android-review" "android-deep-review.md" \
  "Production Android review for reachable bugs, trust-boundary failures, lifecycle errors, and material simplification." \
  "deep-review" \
  "completion"

review_gpt_register_dir_preset "android-pr-review" "android-deep-review.md" \
  "Exact-pushed-head Android PR review with repository, base/head, body, prompt, and response attestation." \
  "pr-review"
