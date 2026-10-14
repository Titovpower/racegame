# Scene preview (no Android SDK needed)

Renders the game's real meshes and GLSL shader in headless Chromium (WebGL 1 = GLSL ES 1.00).

```sh
cd tools/preview
gradle test                                  # gameplay unit tests on the desktop JVM
gradle export -Pout=$PWD/build/street        # simulate a few seconds, export meshes + one frame
node render.js $PWD build/street street.png  # render it (needs Playwright + Chromium)
```

Environment variables for `export`: `PREVIEW_T` (seconds driven, default 6),
`PREVIEW_GRASS=1` (steer off onto the grass at the end), `PREVIEW_BRAKE=1` (brake at the end),
`PREVIEW_ORBIT=<degrees>` and `PREVIEW_DIST=<m>` (look at the car from around it instead of the chase camera).
The HUD and steering wheel drawn in the preview are an HTML canvas approximation of `ControlOverlayView`.
