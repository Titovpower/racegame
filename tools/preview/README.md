# Scene preview (no Android SDK needed)

Renders the game's real glTF assets and sky with WebGL 2 in headless Chromium, with GLSL shaders
that approximate Filament's lighting (`src/preview/kotlin/GlShaders.kt`), and renders the engine
sound to WAV. Tests read the generated glTF back with a small parser (`app/src/test/.../Gltf.kt`).

```sh
cd tools/preview
gradle test                                  # gameplay unit tests on the desktop JVM
gradle export -Pout=$PWD/build/street        # simulate a few seconds, export assets + one frame
node render.js $PWD build/street street.png  # render it (needs Playwright + Chromium)
gradle sound -Pout=$PWD/build/sound          # engine sound of each car, as WAV
```

Environment variables for `export`: `PREVIEW_T` (seconds driven, default 6; 0 = car picker),
`PREVIEW_CAR=<brand>` (pick that car), `PREVIEW_GRASS=1` (steer off onto the grass at the end),
`PREVIEW_BRAKE=1` (brake at the end), `PREVIEW_WIN=1` (show the YOU WIN banner),
`PREVIEW_ORBIT=<degrees>` and `PREVIEW_DIST=<m>` (look at the car from around it instead of the
chase camera). The HUD and steering wheel drawn in the preview are an HTML canvas approximation
of `ControlOverlayView`.
