# Race Game

A small 3D racing game for Android. Drive a red sports car around a track through
grass, woods and hills, seen from a chase camera behind the car.

![On the street](docs/street.png)
![On the grass](docs/grass.png)
![The car](docs/car.png)

<sub>Screenshots are rendered from the game's own meshes and shader in headless Chromium (WebGL),
see `tools/preview`. The steering wheel and HUD in them are an approximation of the Android overlay.</sub>

## Controls

A steering wheel sits at the bottom of the screen. Touch it and drag:

- **left / right**: steer
- **up**: accelerate (the further up, the more throttle)
- **down**: brake; keep holding it while stopped to reverse
- **let go**: the wheel re-centers and the car coasts

The ground matters. On **grass** the car accelerates, steers and brakes much more
slowly than on the **street**, and its top speed is lower:

| | Street | Grass |
|---|---|---|
| Acceleration | 11 m/s² | 4.5 m/s² |
| Top speed | ~210 km/h | ~86 km/h |
| Braking | 24 m/s² | 9 m/s² |
| Max steering rate | 1.5 rad/s | 0.75 rad/s |

These live in `Surface.STREET` / `Surface.GRASS` in `CarPhysics.kt`.
The HUD shows speed, the current ground type and lap times.

## Tech

Plain Kotlin with OpenGL ES 2.0 (`GLSurfaceView`), no game engine and no third-party
libraries. All geometry (terrain, track, trees, grandstand, car) is generated in code.
One shader does per-pixel lighting with a hemisphere ambient, specular highlights and
sky reflections on the car paint and glass, procedural grass and asphalt detail,
a procedural sky with sun and clouds, and distance fog.

| File | What it does |
|---|---|
| `MainActivity.kt` | Fullscreen landscape activity, GL view + control overlay |
| `ControlOverlayView.kt` | Steering wheel touch control and HUD |
| `Scene.kt` | Chase camera and the per-frame draw list (API independent) |
| `GameRenderer.kt` | GL thread: simulation loop and OpenGL drawing |
| `ShaderSource.kt` | GLSL vertex and fragment shader |
| `GameState.kt` | Simulation: ground detection, collisions, laps |
| `CarPhysics.kt` | Car model and street/grass parameters |
| `World.kt`, `Track.kt` | Track shape, hills, trees, bushes, rocks, grandstand |
| `CarModel.kt` | The sports car: lofted body with wheel arches, glass cabin, alloy wheels |
| `SceneMeshes.kt`, `MeshBuilder.kt` | Procedural world meshes and mesh helpers |

## Build and install

Every push builds the APK on GitHub Actions: open the latest **Android build** run
under the Actions tab and download the `racegame-debug-apk` artifact, then unzip it
and install `app-debug.apk` on your phone (allow installs from unknown sources).

Locally, with the Android SDK installed (or from Android Studio):

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected device
./gradlew testDebugUnitTest      # gameplay unit tests
```

Requires Android 7.0 (API 24) or newer.
