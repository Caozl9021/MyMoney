#!/usr/bin/env bash
# Generate all three launcher choices and the default notification/widget brand.
# Original PNG artwork is read-only. --dry-run writes nothing.
set -euo pipefail
cd "$(dirname "$0")"
SOURCE="${SOURCE:-logo/logo1.png}"
RES_DIR="${RES_DIR:-app/src/main/res}"
DRY_RUN=0
while (($#)); do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --source) SOURCE="${2:?--source requires a path}"; shift 2 ;;
        --res-dir) RES_DIR="${2:?--res-dir requires a path}"; shift 2 ;;
        --help|-h) echo 'Usage: generate-icons.sh [--dry-run] [--source default.png] [--res-dir directory]'; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; exit 1 ;;
        *) SOURCE="$1"; shift ;;
    esac
done
command -v magick >/dev/null || { echo 'ImageMagick 7 is required' >&2; exit 1; }
SOURCES=("$SOURCE" 'logo/logo2.png' 'logo/loso3.png')
MASK='logo/brand-monochrome.svg'
SAFE_DP=46
CANVAS_DP=108
LEGACY_PERCENT=47
BG_COLOR='#FFFFFF'
declare -A LEGACY=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
declare -A CANVAS=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
for FILE in "${SOURCES[@]}" "$MASK"; do
    [[ -f "$FILE" ]] || { echo "Source not found: $FILE" >&2; exit 1; }
done
echo "Sources: ${SOURCES[*]}; shared alpha mask: $MASK"
echo "Adaptive: ${SAFE_DP}/${CANVAS_DP}dp; legacy: ${LEGACY_PERCENT}%; background: $BG_COLOR"
echo 'Default only: small 18/24dp, brand color 40/48dp. Three color previews. Shared themed mask.'
if ((DRY_RUN)); then
    echo "[dry-run] $RES_DIR: 26 default PNGs + 15 logo2 PNGs + 15 logo3 PNGs"
    echo '[dry-run] adaptive XML for each choice, colors.xml; original files unchanged'
    echo 'Dry run: no files written.'
    exit 0
fi
TMP_ICON_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_ICON_DIR"' EXIT
OUT="$TMP_ICON_DIR/res"
mkdir -p "$OUT"
# A dedicated vector mask retains coin negative space even for gold painted artwork.
magick -background none "$MASK" -trim +repage "$TMP_ICON_DIR/mask.png"
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
        echo "Invalid bounds/centering: $FILE $CONTENT limit=$LIMIT offset=$DX,$DY" >&2; return 1;
    }
    echo "Validated ${FILE#"$OUT"/}: ${SIZE}x${SIZE} opaque=$EXPECT_OPAQUE bbox=$CONTENT"
}
for INDEX in 0 1 2; do
    INPUT=${SOURCES[$INDEX]}; SUFFIX=''
    ((INDEX==0)) || SUFFIX="_logo$((INDEX+1))"
    BBOX=$(magick "$INPUT" -alpha extract -threshold 1% -format '%@' info:)
    [[ "$BBOX" =~ ^[1-9][0-9]*x[1-9][0-9]*\+[0-9]+\+[0-9]+$ ]] || { echo "Empty input $INPUT" >&2; exit 1; }
    magick "$INPUT" -channel A -fx 'a <= 0.01 ? 0 : a' +channel -crop "$BBOX" +repage "$TMP_ICON_DIR/source.png"
    for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        S=${LEGACY[$D]}; C=${CANVAS[$D]}
        INNER=$(((S*LEGACY_PERCENT+50)/100)); FG_INNER=$(((C*SAFE_DP+CANVAS_DP/2)/CANVAS_DP))
        BASE="$OUT/mipmap-$D"; DRAW="$OUT/drawable-$D"
        mkdir -p "$BASE" "$DRAW"
        magick "$TMP_ICON_DIR/source.png" -resize "${INNER}x${INNER}" -background "$BG_COLOR" -gravity center \
            -extent "${S}x${S}" -alpha remove -alpha off "$BASE/ic_launcher$SUFFIX.png"
        magick "$TMP_ICON_DIR/source.png" -resize "${FG_INNER}x${FG_INNER}" -background none -gravity center \
            -extent "${C}x${C}" "$BASE/ic_launcher_foreground$SUFFIX.png"
        COLOR_INNER=$(((S*40+24)/48))
        magick "$TMP_ICON_DIR/source.png" -resize "${COLOR_INNER}x${COLOR_INNER}" -background none -gravity center \
            -extent "${S}x${S}" "$DRAW/ic_brand_color$SUFFIX.png"
        validate "$BASE/ic_launcher$SUFFIX.png" "$S" True "$INNER"
        validate "$BASE/ic_launcher_foreground$SUFFIX.png" "$C" False "$FG_INNER"
        validate "$DRAW/ic_brand_color$SUFFIX.png" "$S" False "$COLOR_INNER"
        if ((INDEX==0)); then
            cp "$BASE/ic_launcher.png" "$BASE/ic_launcher_round.png"
            validate "$BASE/ic_launcher_round.png" "$S" True "$INNER"
            SMALL=$((S/2)); SMALL_INNER=$(((SMALL*18+12)/24))
            magick "$TMP_ICON_DIR/mask.png" -resize "${SMALL_INNER}x${SMALL_INNER}" -background none -gravity center \
                -extent "${SMALL}x${SMALL}" -channel RGB -evaluate set 100% +channel "$DRAW/ic_brand_small.png"
            validate "$DRAW/ic_brand_small.png" "$SMALL" False "$SMALL_INNER"
            [[ "$(magick "$DRAW/ic_brand_small.png" -alpha off -format '%[fx:minima]' info:)" == 1 ]]
        fi
    done
    for API in 26 33; do
        mkdir -p "$OUT/mipmap-anydpi-v$API"
        FILE="$OUT/mipmap-anydpi-v$API/ic_launcher$SUFFIX.xml"
        cat > "$FILE" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground$SUFFIX" />
XML
        if ((API==33)); then echo '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' >> "$FILE"; fi
        echo '</adaptive-icon>' >> "$FILE"
        if ((INDEX==0)); then cp "$FILE" "$OUT/mipmap-anydpi-v$API/ic_launcher_round.xml"; fi
    done
done
mkdir -p "$OUT/drawable-nodpi" "$OUT/values"
MONO_INNER=$(((432*SAFE_DP+CANVAS_DP/2)/CANVAS_DP))
magick "$TMP_ICON_DIR/mask.png" -resize "${MONO_INNER}x${MONO_INNER}" -background none -gravity center \
    -extent 432x432 -channel RGB -evaluate set 100% +channel "$OUT/drawable-nodpi/ic_launcher_monochrome.png"
validate "$OUT/drawable-nodpi/ic_launcher_monochrome.png" 432 False "$MONO_INNER"
cat > "$OUT/values/colors.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">$BG_COLOR</color>
</resources>
XML
[[ "$(find "$OUT" -name '*.png' | wc -l)" == 56 ]]
# Publish only after every output has been validated.
mkdir -p "$RES_DIR"
cp -R "$OUT/." "$RES_DIR/"
echo 'Generated 56 PNGs, 8 adaptive XMLs and background. Pending user acceptance.'
