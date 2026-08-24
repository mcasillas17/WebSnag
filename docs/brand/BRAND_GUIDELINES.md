# WebSnag icon & wordmark assets

Mark: a single continuous stroke that reads as a **W** and as a strand of webbing zigzagged
between two anchors. Violet (#A58BFF) on a deep violet-ink ground.

## Drop-in for the Android app

Copy `android/res/` over your module's `src/main/res/`:

    res/drawable/ic_launcher_background.xml     radial-gradient ground (108dp)
    res/drawable/ic_launcher_foreground.xml     the W, stroked, 12dp weight
    res/drawable/ic_launcher_monochrome.xml     same path in white, for themed icons
    res/mipmap-anydpi-v26/ic_launcher.xml       adaptive icon (bg + fg + monochrome)
    res/mipmap-anydpi-v26/ic_launcher_round.xml identical adaptive definition
    res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png        pre-API-26 fallback, squircle
    res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png  pre-API-26 fallback, circular
    res/values/websnag_colors.xml               brand colors

Manifest (already the default in most templates):

    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"

Notes
- `ic_launcher_background.xml` uses the `aapt:attr` inline gradient (AAPT2, API 24+ / vector
  support library). If your build chokes on it, replace the path's fill with a flat
  `android:fillColor="#FF221C3A"`; the mark is unaffected.
- Geometry is authored on the 108dp adaptive canvas with every stroke end inside the central
  66dp safe circle, so no launcher mask clips the W.
- Stroke weight is 12dp, i.e. 5.3px at a 48px launcher icon.

## Store & docs

    play/ic_launcher-512.png              Play Store listing icon, 512x512, full square
    png/icon-1024-squircle.png            marketing / GitHub social preview
    png/icon-1024-circle.png              circular avatar (GitHub org, socials)
    png/foreground-432-transparent.png    mark only, transparent
    png/monochrome-432-transparent.png    white mark only, transparent
    svg/websnag-icon-full.svg             vector, ground + mark
    svg/websnag-icon-foreground.svg       vector, mark only
    svg/websnag-icon-background.svg       vector, ground only
    svg/websnag-icon-monochrome.svg       vector, white mark
    svg/websnag-wordmark.svg              lockup: mark + "WebSnag"

The wordmark is Space Grotesk Bold at -0.035em tracking, one word, no space, with "Snag" in
violet. `websnag-wordmark.svg` uses live text — install Space Grotesk or convert the text to
outlines before shipping it anywhere the font is not available. Export a PNG from it at whatever
width the README or Play listing needs.

## Palette

    violet          #A58BFF   mark, accent, "Snag"
    violet (print)  #6B4EF0   on light backgrounds
    ground core     #2A2246   icon ground, center
    ground edge     #181428   icon ground, corners
    type light      #F4F0FA
