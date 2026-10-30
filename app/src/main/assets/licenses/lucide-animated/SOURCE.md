# Lucide Animated Android adaptation

Retrieved 2026-10-26 from https://github.com/pqoqubbw/icons (main snapshot; commit API unavailable HTTP 403). Exact source files retained here with SHA-256; do not infer an immutable upstream revision.

home.tsx, credit-card.tsx, chart-pie.tsx, settings.tsx: https://raw.githubusercontent.com/pqoqubbw/icons/main/icons/NAME.tsx

Animation code: MIT (pqoqubbw); icon geometry: Lucide ISC / underlying Feather MIT (complete upstream license retained).

Compose port preserves paths and stroke width/caps/joins. Rect/line/circle map to equivalent paths. Hover becomes tab selection, one-shot, first render static; pie returns to rest, gear rests at rotationally identical 180°. Spring solvers differ across runtimes; this is an adaptation, not pixel-identical Motion playback.
