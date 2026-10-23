#!/usr/bin/env bash
# Generate launcher, themed, notification/QS and colored brand variants from one source.
# SOURCE=other.png ./generate-icons.sh, --source other.png or positional other.png.
# --dry-run only reads metadata and prints operations; no files are written.
set -euo pipefail
cd "$(dirname "$0")"
SOURCE="${SOURCE:-Auto.jpg}"
RES_DIR="${RES_DIR:-app/src/main/res}"
DRY_RUN=0
while (($#)); do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --source) SOURCE="${2:?--source requires a path}"; shift 2 ;;
        --res-dir) RES_DIR="${2:?--res-dir requires a path}"; shift 2 ;;
        --help|-h) echo 'Usage: generate-icons.sh [--dry-run] [--source image.png] [--res-dir directory] [image.png]'; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; exit 1 ;;
        *) SOURCE="$1"; shift ;;
    esac
done
command -v magick >/dev/null || { echo 'ImageMagick 7 is required' >&2; exit 1; }
[[ -f "$SOURCE" ]] || { echo "Source not found: $SOURCE" >&2; exit 1; }
SAFE_DP=54
CANVAS_DP=108
LEGACY_PERCENT=55
BG_COLOR='#FFFFFF'
# Transparent input may contain near-invisible alpha specks. Remove only alpha<=1%,
# retain all higher alpha values and every RGB value; never make interior white transparent.
ALPHA_NOISE_PERCENT=1
OPAQUE=$(magick "$SOURCE" -format '%[opaque]' info:)
if [[ "$OPAQUE" == False ]]; then
    BBOX=$(magick "$SOURCE" -alpha extract -threshold "${ALPHA_NOISE_PERCENT}%" -format '%@' info:)
    MODE="alpha crop after removing alpha<=${ALPHA_NOISE_PERCENT}%"
else
    BBOX=$(magick "$SOURCE" -fuzz 5% -format '%@' info:)
    MODE='opaque input: trim and remove only edge-connected white background'
fi
[[ "$BBOX" =~ ^[1-9][0-9]*x[1-9][0-9]*\+[0-9]+\+[0-9]+$ ]] || { echo "Empty or invalid content: $BBOX" >&2; exit 1; }
echo "Source: $SOURCE ($(magick "$SOURCE" -format '%wx%h opaque=%[opaque]' info:))"
echo "Crop: $BBOX; $MODE"
echo "Adaptive: ${SAFE_DP}/${CANVAS_DP}dp; legacy: ${LEGACY_PERCENT}%; background: $BG_COLOR"
echo 'Brand small: 24dp canvas / 18dp white alpha; brand color: 48dp canvas / 40dp content'
echo 'Monochrome/small: remove near-white with 10% fuzz only in the separate mask, retain colored border and letters'
declare -A LEGACY=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
declare -A CANVAS=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
if ((DRY_RUN)); then
    for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        echo "[dry-run] $RES_DIR/mipmap-$D/{ic_launcher,ic_launcher_round,ic_launcher_foreground}.png"
        echo "[dry-run] $RES_DIR/drawable-$D/{ic_brand_small,ic_brand_color}.png"
    done
    echo "[dry-run] $RES_DIR/drawable-nodpi/ic_launcher_monochrome.png (432x432, separate near-white negative-space mask)"
    echo "[dry-run] $RES_DIR/values/colors.xml ($BG_COLOR)"
    echo 'Dry run: no files written.'
    exit 0
fi
TMP_ICON_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_ICON_DIR"' EXIT
if [[ "$OPAQUE" == False ]]; then
    magick "$SOURCE" -channel A -fx 'a <= 0.01 ? 0 : a' +channel \
        -crop "$BBOX" +repage "$TMP_ICON_DIR/source.png"
else
    # Add a white border so flood fill reaches background along every outer edge.
    # Interior white regions enclosed by the artwork keep their original colors.
    magick "$SOURCE" -crop "$BBOX" +repage -alpha set -bordercolor white -border 1 \
        -fuzz 5% -fill none -draw 'alpha 0,0 floodfill' -shave 1x1 +repage "$TMP_ICON_DIR/source.png"
fi
# Color source retains enclosed white artwork. Only themed/status masks use near-white
# negative space, so a closed colorful border cannot become a solid white square.
# The tolerance absorbs JPEG near-white noise before downsampling and antialiasing.
magick "$TMP_ICON_DIR/source.png" -fuzz 10% -transparent white \
    "$TMP_ICON_DIR/mask-source.png"
validate() {
    local FILE="$1" SIZE="$2" EXPECT_OPAQUE="$3" LIMIT="$4" CONTENT
    [[ "$(magick "$FILE" -format '%wx%h %[opaque]' info:)" == "${SIZE}x${SIZE} $EXPECT_OPAQUE" ]] || {
        echo "Invalid size/transparency: $FILE" >&2; return 1;
    }
    if [[ "$EXPECT_OPAQUE" == False ]]; then
        CONTENT=$(magick "$FILE" -alpha extract -threshold 0 -format '%@' info:)
    else
        CONTENT=$(magick "$FILE" -fuzz 5% -format '%@' info:)
    fi
    [[ "$CONTENT" =~ ^([0-9]+)x([0-9]+)\+([0-9]+)\+([0-9]+)$ ]] || return 1
    local W=${BASH_REMATCH[1]} H=${BASH_REMATCH[2]} X=${BASH_REMATCH[3]} Y=${BASH_REMATCH[4]}
    local DX=$((2*X+W-SIZE)) DY=$((2*Y+H-SIZE))
    ((W>0 && H>0 && W<=LIMIT && H<=LIMIT && DX>=-2 && DX<=2 && DY>=-2 && DY<=2)) || {
        echo "Invalid content bounds/centering: $FILE $CONTENT limit=$LIMIT doubled-center-offset=$DX,$DY" >&2; return 1;
    }
    echo "Validated $(basename "$FILE"): ${SIZE}x${SIZE}, opaque=$EXPECT_OPAQUE, bbox=$CONTENT, center<=1px"
}
for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    S=${LEGACY[$D]}; C=${CANVAS[$D]}
    INNER=$(((S*LEGACY_PERCENT+50)/100)); FG_INNER=$(((C*SAFE_DP+CANVAS_DP/2)/CANVAS_DP))
    mkdir -p "$TMP_ICON_DIR/mipmap-$D"
    BASE="$TMP_ICON_DIR/mipmap-$D"
    magick "$TMP_ICON_DIR/source.png" -resize "${INNER}x${INNER}" \
        -background "$BG_COLOR" -gravity center -extent "${S}x${S}" -alpha remove -alpha off "$BASE/ic_launcher.png"
    cp "$BASE/ic_launcher.png" "$BASE/ic_launcher_round.png"
    magick "$TMP_ICON_DIR/source.png" -resize "${FG_INNER}x${FG_INNER}" \
        -background none -gravity center -extent "${C}x${C}" "$BASE/ic_launcher_foreground.png"
    validate "$BASE/ic_launcher.png" "$S" True "$INNER"
    validate "$BASE/ic_launcher_round.png" "$S" True "$INNER"
    validate "$BASE/ic_launcher_foreground.png" "$C" False "$FG_INNER"
done
# Small notification/QS marks need their own 18/24dp geometry, not the sparse launcher canvas.
for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    S=${LEGACY[$D]}; SMALL=$((S/2)); SMALL_INNER=$(((SMALL*18+12)/24))
    COLOR_INNER=$(((S*40+24)/48))
    mkdir -p "$TMP_ICON_DIR/drawable-$D"
    magick "$TMP_ICON_DIR/mask-source.png" -resize "${SMALL_INNER}x${SMALL_INNER}" \
        -background none -gravity center -extent "${SMALL}x${SMALL}" \
        -channel RGB -evaluate set 100% +channel "$TMP_ICON_DIR/drawable-$D/ic_brand_small.png"
    magick "$TMP_ICON_DIR/source.png" -resize "${COLOR_INNER}x${COLOR_INNER}" \
        -background none -gravity center -extent "${S}x${S}" "$TMP_ICON_DIR/drawable-$D/ic_brand_color.png"
    validate "$TMP_ICON_DIR/drawable-$D/ic_brand_small.png" "$SMALL" False "$SMALL_INNER"
    validate "$TMP_ICON_DIR/drawable-$D/ic_brand_color.png" "$S" False "$COLOR_INNER"
    # Every RGB channel must be white, including antialiased edge pixels.
    [[ "$(magick "$TMP_ICON_DIR/drawable-$D/ic_brand_small.png" -alpha off -format '%[fx:minima]' info:)" == 1 ]] || {
        echo "Small icon contains nonwhite RGB: $D" >&2; exit 1;
    }
done
mkdir -p "$TMP_ICON_DIR/drawable-nodpi" "$TMP_ICON_DIR/values"
magick "$TMP_ICON_DIR/mask-source.png" -resize 216x216 \
    -background none -gravity center -extent 432x432 -channel RGB -evaluate set 100% +channel \
    "$TMP_ICON_DIR/drawable-nodpi/ic_launcher_monochrome.png"
validate "$TMP_ICON_DIR/drawable-nodpi/ic_launcher_monochrome.png" 432 False 216
# Exact alpha identity with the separate negative-space source, not the opaque color fill.
magick "$TMP_ICON_DIR/mask-source.png" -resize 216x216 -background none -gravity center -extent 432x432 -alpha extract "$TMP_ICON_DIR/foreground-alpha.png"
magick "$TMP_ICON_DIR/drawable-nodpi/ic_launcher_monochrome.png" -alpha extract "$TMP_ICON_DIR/mono-alpha.png"
magick compare -metric AE "$TMP_ICON_DIR/foreground-alpha.png" "$TMP_ICON_DIR/mono-alpha.png" null: 2>&1
echo ' (monochrome alpha difference; must be zero)'
cat > "$TMP_ICON_DIR/values/colors.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- White adaptive background; foreground and monochrome generated together. -->
    <color name="ic_launcher_background">$BG_COLOR</color>
</resources>
XML
# Publish only after every generated file passes validation.
for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    mkdir -p "$RES_DIR/mipmap-$D"
    cp "$TMP_ICON_DIR/mipmap-$D/"*.png "$RES_DIR/mipmap-$D/"
    mkdir -p "$RES_DIR/drawable-$D"
    cp "$TMP_ICON_DIR/drawable-$D/"*.png "$RES_DIR/drawable-$D/"
done
mkdir -p "$RES_DIR/drawable-nodpi" "$RES_DIR/values"
cp "$TMP_ICON_DIR/drawable-nodpi/ic_launcher_monochrome.png" "$RES_DIR/drawable-nodpi/"
cp "$TMP_ICON_DIR/values/colors.xml" "$RES_DIR/values/"
echo 'Generated 15 launcher PNGs, 10 small/color brand PNGs, independent monochrome mask and white adaptive background. Pending user acceptance.'
