# WebSnag icon & wordmark assets

Mark: a single asymmetrical stroke reading as a **W** and as a strand of webbing pulled taut —
lifted tail on the last arm for a "hand-flicked" feel. Violet gradient (light-to-dark) on a
deep violet-ink ground.

## Drop-in for the Android app

Copy `android/res/` over your module's `src/main/res/`:

    res/drawable/ic_launcher_background.xml     radial-gradient ground (108dp)
    res/drawable/ic_launcher_foreground.xml     the W, flat violet stroke, 13dp weight
    res/drawable/ic_launcher_monochrome.xml     same path in white, for themed icons
    res/mipmap-anydpi-v26/ic_launcher.xml       adaptive icon (bg + fg + monochrome)
    res/mipmap-anydpi-v26/ic_launcher_round.xml identical adaptive definition
    res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png        pre-API-26 fallback, squircle, full gradient/gloss treatment
    res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png  pre-API-26 fallback, circular, full gradient/gloss treatment
    res/values/websnag_colors.xml               brand colors

Manifest (already the default in most templates):

    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"

Notes
- The adaptive-icon foreground/monochrome vectors use a **flat** violet stroke — Android
  masks, animates, and tints this layer at runtime, so keeping it a single solid color is the
  safe choice. The full gradient + soft-shadow + gloss treatment lives in the pre-API-26 PNG
  fallbacks and every marketing asset below.
- `ic_launcher_background.xml` uses the `aapt:attr` inline gradient (AAPT2, API 24+). If your
  build chokes on it, swap the path's fill for a flat `android:fillColor="#FF221C3A"`.
- Geometry sits on the 108dp adaptive canvas with every stroke end inside the central 66dp
  safe circle, so no launcher mask clips the W.

## Store & docs

    play/ic_launcher-512.png                     Play Store listing icon, 512x512, full gradient
    png/icon-1024-squircle.png                   marketing / GitHub social preview
    png/icon-1024-circle.png                     circular avatar (GitHub org, socials)
    png/foreground-432-transparent.png           mark only, transparent, full gradient
    png/monochrome-432-transparent.png           white mark only, transparent, flat
    svg/websnag-icon-full.svg                    vector, ground + gradient mark
    svg/websnag-icon-foreground.svg              vector, gradient mark only
    svg/websnag-icon-background.svg              vector, ground only
    svg/websnag-icon-monochrome.svg              vector, white mark
    png/wordmark-unbounded-dark.png              "WebSnag" lockup, Unbounded ExtraBold, dark theme
    png/wordmark-unbounded-light.png             same, light theme
    svg/websnag-wordmark-unbounded-dark.svg      vector version, dark theme
    svg/websnag-wordmark-unbounded-light.svg     vector version, light theme

The wordmark is Unbounded ExtraBold (800), -0.6 letter-spacing at this size, one word, no
space, "Snag" in violet. The two SVGs use live text — install Unbounded or convert the text to
outlines before shipping them anywhere the font isn't guaranteed to load; the PNGs are already
rasterised and need nothing extra.

## Palette

    violet, light stop   #E2D6FF   gradient top
    violet, mid          #A58BFF   gradient middle / flat stroke / accent
    violet, dark stop    #5A3FD6   gradient bottom
    violet (print)       #6B4EF0   on light backgrounds
    ground core          #2A2246   icon ground, center
    ground edge          #181428   icon ground, corners / dark wordmark bg
    light bg             #EDE9F7   light wordmark bg
    type light           #F4F0FA
    type dark             #241D33
