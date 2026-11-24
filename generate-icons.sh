#!/usr/bin/env bash
# Generate single wheat-spike launcher icons, themed monochrome mask, notification/widget brand icons, and web icons.
# Original artwork in logo/ is read-only. --dry-run writes nothing.
set -euo pipefail
cd "$(dirname "$0")"
SOURCE="${SOURCE:-logo/wheat.png}"
RES_DIR="${RES_DIR:-app/src/main/res}"
WEB_PUBLIC="${WEB_PUBLIC:-web/public}"
DRY_RUN=0
SKIP_WEB=0
while (($#)); do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --skip-web) SKIP_WEB=1; shift ;;
        --source) SOURCE="${2:?--source requires a path}"; shift 2 ;;
        --res-dir) RES_DIR="${2:?--res-dir requires a path}"; shift 2 ;;
        --help|-h) echo 'Usage: generate-icons.sh [--dry-run] [--skip-web] [--source wheat.png] [--res-dir directory]'; exit 0 ;;
        -*) echo "Unknown option: $1" >&2; exit 1 ;;
        *) SOURCE="$1"; shift ;;
    esac
done
MASK='logo/brand-monochrome.svg'
SAFE_DP=58
CANVAS_DP=108
LEGACY_PERCENT=76
BRAND_INNER_DP=42
SMALL_INNER_DP=20
BG_COLOR='#FFFFFF'
for FILE in "$SOURCE" "$MASK"; do
    [[ -f "$FILE" ]] || { echo "Source not found: $FILE" >&2; exit 1; }
done
echo "Source: $SOURCE; monochrome mask: $MASK"
echo "Adaptive: ${SAFE_DP}/${CANVAS_DP}dp; legacy: ${LEGACY_PERCENT}%; brand: ${BRAND_INNER_DP}/48dp; small: ${SMALL_INNER_DP}/24dp; background: $BG_COLOR"
if ((DRY_RUN)); then
    echo "[dry-run] $RES_DIR: 26 PNGs + 4 adaptive XMLs + colors.xml"
    if ((!SKIP_WEB)); then echo "[dry-run] $WEB_PUBLIC: favicon.png (64x64) + apple-touch-icon.png (180x180)"; fi
    echo 'Dry run: no files written.'
    exit 0
fi

TMP_ICON_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_ICON_DIR"' EXIT
OUT="$TMP_ICON_DIR/res"
mkdir -p "$OUT"

if command -v magick >/dev/null; then
    declare -A LEGACY=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
    declare -A CANVAS=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
    magick -background none "$MASK" -trim +repage "$TMP_ICON_DIR/mask.png"
    BBOX=$(magick "$SOURCE" -alpha extract -threshold 2% -format '%@' info:)
    [[ "$BBOX" =~ ^[1-9][0-9]*x[1-9][0-9]*\+[0-9]+\+[0-9]+$ ]] || { echo "Empty input $SOURCE" >&2; exit 1; }
    magick "$SOURCE" -channel A -fx 'a <= 0.015 ? 0 : (a >= 0.92 ? 1 : a)' +channel -crop "$BBOX" +repage "$TMP_ICON_DIR/source.png"
    for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        S=${LEGACY[$D]}; C=${CANVAS[$D]}
        INNER=$(((S*LEGACY_PERCENT+50)/100))
        FG_INNER=$(((C*SAFE_DP+CANVAS_DP/2)/CANVAS_DP))
        BASE="$OUT/mipmap-$D"; DRAW="$OUT/drawable-$D"
        mkdir -p "$BASE" "$DRAW"
        magick "$TMP_ICON_DIR/source.png" -resize "${INNER}x${INNER}" -background "$BG_COLOR" -gravity center \
            -extent "${S}x${S}" -alpha remove -alpha off "$BASE/ic_launcher.png"
        cp "$BASE/ic_launcher.png" "$BASE/ic_launcher_round.png"
        magick "$TMP_ICON_DIR/source.png" -resize "${FG_INNER}x${FG_INNER}" -background none -gravity center \
            -extent "${C}x${C}" "$BASE/ic_launcher_foreground.png"
        COLOR_INNER=$(((S*BRAND_INNER_DP+24)/48))
        magick "$TMP_ICON_DIR/source.png" -resize "${COLOR_INNER}x${COLOR_INNER}" -background none -gravity center \
            -extent "${S}x${S}" "$DRAW/ic_brand_color.png"
        SMALL=$((S/2)); SMALL_INNER=$(((SMALL*SMALL_INNER_DP+12)/24))
        magick "$TMP_ICON_DIR/mask.png" -resize "${SMALL_INNER}x${SMALL_INNER}" -background none -gravity center \
            -extent "${SMALL}x${SMALL}" -channel RGB -evaluate set 100% +channel "$DRAW/ic_brand_small.png"
    done
    mkdir -p "$OUT/drawable-nodpi"
    MONO_INNER=$(((432*SAFE_DP+CANVAS_DP/2)/CANVAS_DP))
    magick "$TMP_ICON_DIR/mask.png" -resize "${MONO_INNER}x${MONO_INNER}" -background none -gravity center \
        -extent 432x432 -channel RGB -evaluate set 100% +channel "$OUT/drawable-nodpi/ic_launcher_monochrome.png"
    if ((!SKIP_WEB && -d "$WEB_PUBLIC")); then
        magick "$TMP_ICON_DIR/source.png" -resize 56x56 -background none -gravity center -extent 64x64 "$WEB_PUBLIC/favicon.png"
        magick "$TMP_ICON_DIR/source.png" -resize 138x138 -background "$BG_COLOR" -gravity center -extent 180x180 -alpha remove -alpha off "$WEB_PUBLIC/apple-touch-icon.png"
    fi
elif [[ "$(uname -s)" == "Darwin" ]] && command -v clang >/dev/null; then
    cat > "$TMP_ICON_DIR/gen.m" <<'OBJC'
#import <Cocoa/Cocoa.h>
#import <CoreGraphics/CoreGraphics.h>

static CGMutablePathRef parseSvgPaths(NSString *svgPath, CGRect *outBounds) {
    NSError *err = nil;
    NSString *xml = [NSString stringWithContentsOfFile:svgPath encoding:NSUTF8StringEncoding error:&err];
    if (!xml) { fprintf(stderr, "Cannot read SVG: %s\n", [svgPath UTF8String]); return NULL; }
    NSRegularExpression *re = [NSRegularExpression regularExpressionWithPattern:@"d=\"([^\"]+)\"" options:0 error:nil];
    NSArray<NSTextCheckingResult *> *matches = [re matchesInString:xml options:0 range:NSMakeRange(0, xml.length)];
    CGMutablePathRef path = CGPathCreateMutable();
    NSRegularExpression *tokRe = [NSRegularExpression regularExpressionWithPattern:@"[MCZ]|-?\\d+(?:\\.\\d+)?" options:0 error:nil];
    for (NSTextCheckingResult *m in matches) {
        NSString *d = [xml substringWithRange:[m rangeAtIndex:1]];
        NSArray<NSTextCheckingResult *> *toks = [tokRe matchesInString:d options:0 range:NSMakeRange(0, d.length)];
        NSMutableArray<NSString *> *t = [NSMutableArray arrayWithCapacity:toks.count];
        for (NSTextCheckingResult *tr in toks) [t addObject:[d substringWithRange:tr.range]];
        NSUInteger i = 0;
        while (i < t.count) {
            NSString *cmd = t[i++];
            if ([cmd isEqualToString:@"M"] && i + 1 < t.count) {
                CGFloat x = [t[i++] doubleValue], y = [t[i++] doubleValue];
                CGPathMoveToPoint(path, NULL, x, y);
            } else if ([cmd isEqualToString:@"C"]) {
                while (i + 5 < t.count && ![t[i] isEqualToString:@"M"] && ![t[i] isEqualToString:@"C"] && ![t[i] isEqualToString:@"Z"]) {
                    CGFloat x1 = [t[i++] doubleValue], y1 = [t[i++] doubleValue];
                    CGFloat x2 = [t[i++] doubleValue], y2 = [t[i++] doubleValue];
                    CGFloat x  = [t[i++] doubleValue], y  = [t[i++] doubleValue];
                    CGPathAddCurveToPoint(path, NULL, x1, y1, x2, y2, x, y);
                }
            } else if ([cmd isEqualToString:@"Z"]) {
                CGPathCloseSubpath(path);
            }
        }
    }
    if (outBounds) *outBounds = CGPathGetPathBoundingBox(path);
    return path;
}

static NSBitmapImageRep* makeBitmap(NSInteger size, BOOL opaqueWhite) {
    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc]
        initWithBitmapDataPlanes:NULL pixelsWide:size pixelsHigh:size bitsPerSample:8 samplesPerPixel:4
        hasAlpha:YES isPlanar:NO colorSpaceName:NSDeviceRGBColorSpace bytesPerRow:size*4 bitsPerPixel:32];
    NSGraphicsContext *raw = [NSGraphicsContext graphicsContextWithBitmapImageRep:rep];
    CGContextRef ctx = [raw CGContext];
    CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh);
    if (opaqueWhite) {
        CGContextSetRGBFillColor(ctx, 1, 1, 1, 1);
        CGContextFillRect(ctx, CGRectMake(0, 0, size, size));
    } else {
        CGContextClearRect(ctx, CGRectMake(0, 0, size, size));
    }
    return rep;
}

static BOOL writePng(NSBitmapImageRep *rep, NSString *outFile, BOOL stripAlpha) {
    NSBitmapImageRep *finalRep = rep;
    if (stripAlpha) {
        NSInteger w = rep.pixelsWide, h = rep.pixelsHigh;
        finalRep = [[NSBitmapImageRep alloc]
            initWithBitmapDataPlanes:NULL pixelsWide:w pixelsHigh:h bitsPerSample:8 samplesPerPixel:3
            hasAlpha:NO isPlanar:NO colorSpaceName:NSDeviceRGBColorSpace bytesPerRow:w*3 bitsPerPixel:24];
        const unsigned char *src = rep.bitmapData;
        unsigned char *dst = finalRep.bitmapData;
        NSInteger srcRow = rep.bytesPerRow, dstRow = finalRep.bytesPerRow;
        for (NSInteger y = 0; y < h; y++) {
            const unsigned char *sr = src + y * srcRow;
            unsigned char *dr = dst + y * dstRow;
            for (NSInteger x = 0; x < w; x++) {
                dr[x*3 + 0] = sr[x*4 + 0];
                dr[x*3 + 1] = sr[x*4 + 1];
                dr[x*3 + 2] = sr[x*4 + 2];
            }
        }
    }
    NSData *data = [finalRep representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
    return [data writeToFile:outFile atomically:YES];
}

static void drawSourceCentered(NSBitmapImageRep *rep, CGImageRef img, NSInteger size, NSInteger innerMax) {
    NSGraphicsContext *raw = [NSGraphicsContext graphicsContextWithBitmapImageRep:rep];
    CGContextRef ctx = [raw CGContext];
    CGFloat iw = CGImageGetWidth(img), ih = CGImageGetHeight(img);
    CGFloat scale = MIN((CGFloat)innerMax / iw, (CGFloat)innerMax / ih);
    CGFloat dw = iw * scale, dh = ih * scale;
    CGRect r = CGRectMake(((CGFloat)size - dw) * 0.5, ((CGFloat)size - dh) * 0.5, dw, dh);
    CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh);
    CGContextDrawImage(ctx, r, img);
}

static void drawWhiteMaskCentered(NSBitmapImageRep *rep, CGPathRef path, CGRect bounds, NSInteger size, NSInteger innerMax) {
    NSGraphicsContext *raw = [NSGraphicsContext graphicsContextWithBitmapImageRep:rep];
    CGContextRef ctx = [raw CGContext];
    CGContextSaveGState(ctx);
    // Flip top-down SVG coords into CoreGraphics bottom-up
    CGContextTranslateCTM(ctx, 0, size);
    CGContextScaleCTM(ctx, 1, -1);
    CGFloat scale = MIN((CGFloat)innerMax / bounds.size.width, (CGFloat)innerMax / bounds.size.height);
    CGFloat dw = bounds.size.width * scale, dh = bounds.size.height * scale;
    CGFloat ox = ((CGFloat)size - dw) * 0.5 - bounds.origin.x * scale;
    CGFloat oy = ((CGFloat)size - dh) * 0.5 - bounds.origin.y * scale;
    CGAffineTransform xf = CGAffineTransformTranslate(CGAffineTransformIdentity, ox, oy);
    xf = CGAffineTransformScale(xf, scale, scale);
    CGPathRef scaled = CGPathCreateCopyByTransformingPath(path, &xf);
    CGContextAddPath(ctx, scaled);
    CGContextSetRGBFillColor(ctx, 1, 1, 1, 1);
    CGContextFillPath(ctx);
    CGPathRelease(scaled);
    CGContextRestoreGState(ctx);

    // Ensure RGB channels are pure white (255,255,255) everywhere (unpremultiplied for Android tinting)
    unsigned char *px = rep.bitmapData;
    NSInteger total = rep.pixelsWide * rep.pixelsHigh;
    for (NSInteger i = 0; i < total; i++) {
        NSInteger o = i * 4;
        if (px[o+3] > 0) {
            px[o] = 255; px[o+1] = 255; px[o+2] = 255;
        }
    }
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc < 5) return 2;
        NSString *srcPath = [NSString stringWithUTF8String:argv[1]];
        NSString *svgPath = [NSString stringWithUTF8String:argv[2]];
        NSString *outDir  = [NSString stringWithUTF8String:argv[3]];
        NSString *webDir  = [NSString stringWithUTF8String:argv[4]];
        BOOL writeWeb = (argc >= 6 && strcmp(argv[5], "1") == 0);

        NSImage *nsImg = [[NSImage alloc] initWithContentsOfFile:srcPath];
        CGImageRef cgImg = [nsImg CGImageForProposedRect:NULL context:nil hints:nil];
        if (!cgImg) { fprintf(stderr, "Failed to load %s\n", argv[1]); return 1; }
        CGRect svgBounds = CGRectZero;
        CGMutablePathRef svgPathRef = parseSvgPaths(svgPath, &svgBounds);
        if (!svgPathRef || CGRectIsEmpty(svgBounds)) { fprintf(stderr, "Empty SVG path\n"); return 1; }

        NSArray *densities = @[ @[@"mdpi", @48, @108], @[@"hdpi", @72, @162], @[@"xhdpi", @96, @216], @[@"xxhdpi", @144, @324], @[@"xxxhdpi", @192, @432] ];
        NSFileManager *fm = [NSFileManager defaultManager];
        for (NSArray *d in densities) {
            NSString *name = d[0];
            NSInteger S = [d[1] integerValue], C = [d[2] integerValue];
            NSInteger INNER = (S * 76 + 50) / 100;
            NSInteger FG_INNER = (C * 58 + 54) / 108;
            NSInteger COLOR_INNER = (S * 42 + 24) / 48;
            NSInteger SMALL = S / 2;
            NSInteger SMALL_INNER = (SMALL * 20 + 12) / 24;

            NSString *mip = [outDir stringByAppendingPathComponent:[NSString stringWithFormat:@"mipmap-%@", name]];
            NSString *drw = [outDir stringByAppendingPathComponent:[NSString stringWithFormat:@"drawable-%@", name]];
            [fm createDirectoryAtPath:mip withIntermediateDirectories:YES attributes:nil error:nil];
            [fm createDirectoryAtPath:drw withIntermediateDirectories:YES attributes:nil error:nil];

            NSBitmapImageRep *leg = makeBitmap(S, YES);
            drawSourceCentered(leg, cgImg, S, INNER);
            writePng(leg, [mip stringByAppendingPathComponent:@"ic_launcher.png"], YES);
            writePng(leg, [mip stringByAppendingPathComponent:@"ic_launcher_round.png"], YES);

            NSBitmapImageRep *fg = makeBitmap(C, NO);
            drawSourceCentered(fg, cgImg, C, FG_INNER);
            writePng(fg, [mip stringByAppendingPathComponent:@"ic_launcher_foreground.png"], NO);

            NSBitmapImageRep *bc = makeBitmap(S, NO);
            drawSourceCentered(bc, cgImg, S, COLOR_INNER);
            writePng(bc, [drw stringByAppendingPathComponent:@"ic_brand_color.png"], NO);

            NSBitmapImageRep *sm = makeBitmap(SMALL, NO);
            drawWhiteMaskCentered(sm, svgPathRef, svgBounds, SMALL, SMALL_INNER);
            writePng(sm, [drw stringByAppendingPathComponent:@"ic_brand_small.png"], NO);
        }

        NSString *nodpi = [outDir stringByAppendingPathComponent:@"drawable-nodpi"];
        [fm createDirectoryAtPath:nodpi withIntermediateDirectories:YES attributes:nil error:nil];
        NSBitmapImageRep *mono = makeBitmap(432, NO);
        drawWhiteMaskCentered(mono, svgPathRef, svgBounds, 432, (432 * 58 + 54) / 108);
        writePng(mono, [nodpi stringByAppendingPathComponent:@"ic_launcher_monochrome.png"], NO);

        if (writeWeb) {
            [fm createDirectoryAtPath:webDir withIntermediateDirectories:YES attributes:nil error:nil];
            NSBitmapImageRep *fav = makeBitmap(64, NO);
            drawSourceCentered(fav, cgImg, 64, 56);
            writePng(fav, [webDir stringByAppendingPathComponent:@"favicon.png"], NO);

            NSBitmapImageRep *ati = makeBitmap(180, YES);
            drawSourceCentered(ati, cgImg, 180, 138);
            writePng(ati, [webDir stringByAppendingPathComponent:@"apple-touch-icon.png"], YES);
        }
        CGPathRelease(svgPathRef);
    }
    return 0;
}
OBJC
    clang -fobjc-arc -framework Cocoa -framework CoreGraphics "$TMP_ICON_DIR/gen.m" -o "$TMP_ICON_DIR/gen"
    WRITE_WEB=0
    if ((!SKIP_WEB)) && [[ -d "$WEB_PUBLIC" ]]; then WRITE_WEB=1; fi
    "$TMP_ICON_DIR/gen" "$SOURCE" "$MASK" "$OUT" "$WEB_PUBLIC" "$WRITE_WEB"
else
    echo 'ImageMagick 7 or macOS clang is required' >&2
    exit 1
fi

for API in 26 33; do
    mkdir -p "$OUT/mipmap-anydpi-v$API"
    FILE="$OUT/mipmap-anydpi-v$API/ic_launcher.xml"
    cat > "$FILE" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
XML
    if ((API==33)); then echo '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' >> "$FILE"; fi
    echo '</adaptive-icon>' >> "$FILE"
    cp "$FILE" "$OUT/mipmap-anydpi-v$API/ic_launcher_round.xml"
done
mkdir -p "$OUT/values"
cat > "$OUT/values/colors.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">$BG_COLOR</color>
</resources>
XML
COUNT=$(find "$OUT" -name '*.png' | wc -l | tr -d ' ')
[[ "$COUNT" == 26 ]] || { echo "Expected 26 PNGs, got $COUNT" >&2; exit 1; }
mkdir -p "$RES_DIR"
cp -R "$OUT/." "$RES_DIR/"
echo "Generated $COUNT Android PNGs, 4 adaptive XMLs, and colors.xml."
