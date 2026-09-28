#!/usr/bin/env bash
# GitHub Actions only: installed API21 test APK and Pillow are required.
set -euo pipefail
mkdir -p evidence
package=com.dycomment.tv.android5
component="$package/com.dycomment.tv.FrameSwitchSelfTestActivity"
adb shell am force-stop "$package"
adb logcat -c
adb shell am start -W -n "$component" > evidence/frame-switch-launch.txt
process_pid=$(adb shell ps | tr -d '\r' | awk -v package="$package" '$NF == package {print $2}')
if [[ ! "$process_pid" =~ ^[0-9]+$ ]]; then
  echo 'Unable to identify the frame fixture process' >&2
  exit 1
fi
collect_logs() {
  adb logcat -d -v threadtime -s Android5FrameSwitchTest:I Android5Player:I AndroidRuntime:E \
    | awk -v pid="$process_pid" '$3 == pid' > evidence/frame-switch-test.txt
}
cleanup() {
  collect_logs || true
  adb shell am force-stop "$package" || true
}
trap cleanup EXIT
wait_marker() {
  local marker=$1
  for attempt in $(seq 1 45); do
    collect_logs
    if grep -q 'FAIL\|FATAL EXCEPTION' evidence/frame-switch-test.txt; then
      cat evidence/frame-switch-test.txt
      return 1
    fi
    if grep -q "$marker" evidence/frame-switch-test.txt; then return 0; fi
    sleep 0.25
  done
  cat evidence/frame-switch-test.txt
  echo "Timed out waiting for $marker" >&2
  return 1
}
phase() {
  adb shell am start -W -n "$component" --es phase "$1" >> evidence/frame-switch-launch.txt
}
capture() {
  adb exec-out screencap -p > "evidence/frame-$1.png"
}

wait_marker FRAME_RED_READY
capture red
phase blue_wait
wait_marker FRAME_BLUE_WAIT_MUTED
for frame in 1 2 3; do
  capture "wait-$frame"
  sleep 0.15
done
phase blue
wait_marker FRAME_BLUE_READY
capture blue
phase rapid
for frame in $(seq 1 8); do
  capture "rapid-$frame"
  sleep 0.05
done
wait_marker FRAME_RAPID_RED_READY
for frame in 1 2 3; do
  capture "final-red-$frame"
  sleep 0.1
done
phase done
wait_marker 'PASS API21_REAL_SURFACE_FRAME_SWITCH_AUDIO_HANDOFF'
grep -q OLD_AND_NEW_AUDIO_HANDOFF_OK evidence/frame-switch-test.txt

# Validate compositor pixels rather than View.draw(), which omits SurfaceView video.
python3 -B - <<'PY' | tee evidence/frame-switch-pixels.txt
from pathlib import Path
from PIL import Image

root = Path('evidence')

def matches(path, expected):
    with Image.open(path) as image:
        image = image.convert('RGB')
        width, height = image.size
        if width < 640 or width <= height:
            return False, 'fixture did not capture a landscape display'
        ratio = 16 / 9 if expected == 'red' else 9 / 16
        box_width, box_height = min(width, height * ratio), min(height, width / ratio)
        left, top = (width - box_width) / 2, (height - box_height) / 2
        right, bottom = left + box_width, top + box_height
        hits = total = inside_hits = inside_total = outside_hits = outside_total = 0
        pixels = image.load()
        # Ignore only the codec/scaler's four-pixel boundary, never the side bars.
        for y in range(4, height - 4, 4):
            for x in range(4, width - 4, 4):
                r, g, b = pixels[x, y]
                dark = max(r, g, b) < 28
                if expected == 'black':
                    hits += dark
                    total += 1
                    continue
                interior = left + 4 < x < right - 4 and top + 4 < y < bottom - 4
                exterior = x < left - 4 or x > right + 4 or y < top - 4 or y > bottom + 4
                if interior:
                    color = (r > 180 and g < 65 and b < 65) if expected == 'red' else (b > 180 and r < 65 and g < 65)
                    inside_hits += color
                    inside_total += 1
                elif exterior:
                    outside_hits += dark
                    outside_total += 1
        if expected == 'black':
            fraction = hits / max(1, total)
            return fraction >= 0.995, f'black={fraction:.5f}'
        inside = inside_hits / max(1, inside_total)
        outside = outside_hits / outside_total if outside_total else 1
        return inside >= 0.99 and outside >= 0.995, f'{expected}={inside:.5f} black-bars={outside:.5f}'

def require_frame(name, expected):
    path = root / f'frame-{name}.png'
    good, detail = matches(path, expected)
    if not good:
        raise SystemExit(f'FAIL {path.name}: expected {expected}; {detail}')
    print(f'PASS {path.name}: {detail}')

require_frame('red', 'red')
for index in range(1, 4):
    require_frame(f'wait-{index}', 'black')
require_frame('blue', 'blue')
for index in range(1, 9):
    path = root / f'frame-rapid-{index}.png'
    results = [(kind, *matches(path, kind)) for kind in ('black', 'red', 'blue')]
    valid = [kind for kind, good, _ in results if good]
    if not valid:
        raise SystemExit(f'FAIL {path.name}: stretched, mixed, or stale pixels; {results}')
    print(f'PASS {path.name}: {valid[0]} with correct geometry')
for index in range(1, 4):
    require_frame(f'final-red-{index}', 'red')
print('PASS API21_COMPOSITOR_RED_BLACK_BLUE_BARS_RAPID_RED')
PY
cat evidence/frame-switch-test.txt
