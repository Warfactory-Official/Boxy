#!/usr/bin/env bash
# Dark LOD water regression: builds a lake in the smoke world, streams it through VSS, audits Voxy's stored light per
# LOD level and measures starved water on screen. Exit status is the test result; summary lines are printed at the end.
#   tools/water-smoke.sh <path to the native Voxy 1.21.1 NeoForge jar>
# BOXY_SMOKE_WS=<n> moves the game window to that Hyprland workspace so it stays out of sight.
set -euo pipefail
cd "$(dirname "$0")/.."
voxy_jar=${1:?usage: tools/water-smoke.sh <voxy jar>}

if [[ ! -f run/smoke-server/boxy-migration-smoke/level.dat ]]; then
    ./gradlew -Psmoke -Pvoxy_jar="$voxy_jar" runSmokeServer
fi

if [[ -n "${BOXY_SMOKE_WS:-}" && -n "${HYPRLAND_INSTANCE_SIGNATURE:-}" ]]; then
    mkdir -p run/water-smoke
    wd=$(realpath run/water-smoke)
    (
        for _ in $(seq 1 600); do
            addr=$(hyprctl clients -j | python3 -I -c '
import json, os, sys
for c in json.load(sys.stdin):
    try:
        if os.path.realpath("/proc/%d/cwd" % c["pid"]) == sys.argv[1]:
            print(c["address"]); break
    except OSError:
        pass' "$wd")
            if [[ -n "$addr" ]]; then
                hyprctl dispatch "hl.dsp.window.move({ workspace = $BOXY_SMOKE_WS, follow = false, window = \"address:$addr\" })" >/dev/null
                break
            fi
            sleep 1
        done
    ) &
    trap 'kill $! 2>/dev/null || true' EXIT
fi

status=0
./gradlew -Psmoke -Pwater_smoke -Pvoxy_jar="$voxy_jar" runSmokeClient >/dev/null 2>&1 || status=$?
grep -hE 'BOXY_WATER_SMOKE (audit|result)|BOXY_CLIENT_SMOKE_OK|AssertionError: ' run/water-smoke/logs/latest.log \
    | sed -E 's/^.*(BOXY_|AssertionError: )/\1/' | sort -u
echo "frames: $(ls -d run/water-smoke/boxy-lodwater/*/ | tail -1)"
exit $status
