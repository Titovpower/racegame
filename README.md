# Race Game

A small 3D racing game for Android. Pick a sports car, then race 8 opponents over 5 laps on a
track through grass, woods and hills, seen from a chase camera behind the car.

![Choosing a car](docs/picker.png)
![Racing](docs/race.png)
![On the grass](docs/grass.png)
![YOU WIN after 5 laps](docs/win.png)
![The six cars](docs/cars.png)

<sub>Screenshots are rendered from the game's own meshes and shader in headless Chromium (WebGL),
see `tools/preview`. The steering wheel and HUD in them are an approximation of the Android overlay.</sub>

## Cars

Before the race you choose your car with the arrows and press **START RACE**. Six models, styled
after real cars (proportions from published dimensions, brand names only, no logos):

| Car | Power | Weight | 0-100 km/h | Top speed | Drive |
|---|---|---|---|---|---|
| Lamborghini Huracán EVO | 639 hp | 1550 kg | 2.9 s | 325 km/h | AWD |
| Porsche 911 GT3 | 510 hp | 1435 kg | 3.4 s | 318 km/h | RWD |
| BMW M4 Competition | 530 hp | 1775 kg | 3.5 s | 290 km/h | AWD |
| Mercedes-AMG GT R | 584 hp | 1630 kg | 3.6 s | 318 km/h | RWD |
| Audi R8 V10 performance | 620 hp | 1595 kg | 3.1 s | 331 km/h | AWD |
| Aston Martin Vantage | 665 hp | 1605 kg | 3.5 s | 325 km/h | RWD |

The physics take these figures over directly. Engine power (minus drivetrain losses) is limited by
tire traction at low speed; air drag is solved from the top speed and launch traction from the
0-100 time, so every car reaches its published numbers (checked by the unit tests). Braking,
cornering grip and downforce differ per car, and turning follows the wheelbase and steering angle.

## Race

You race 8 AI opponents, each driving one of the six models, starting from the back of the grid
after a 3-2-1 countdown. Each opponent has its own skill level and preferred line, brakes for
corners according to its own car's grip, and swings out to overtake slower cars.

The **ranking** in the bottom-left corner shows the live order with each driver's car brand.
Crossing the finish line **5 times** completes the race: a red **YOU WIN** pops up above your car,
with your finishing place. Tap anywhere to pick a car and race again.

## Controls

A steering wheel sits at the bottom of the screen. Touch it and drag:

- **left / right**: steer
- **up**: accelerate (the further up, the more throttle)
- **down**: brake; keep holding it while stopped to reverse
- **let go**: the wheel re-centers and the car coasts

The ground matters. On **grass** the tires have under half the grip of asphalt and most
engine power is lost to wheelspin, so the car accelerates, steers and brakes much more slowly,
and top speed drops below ~110 km/h (`Surface.STREET` / `Surface.GRASS` in `CarPhysics.kt`).
On asphalt a car stops from 100 km/h in about 30 m.
The HUD shows speed, the current ground type, position, lap and lap times.

## Tech

Plain Kotlin with OpenGL ES 2.0 (`GLSurfaceView`), no game engine and no third-party
libraries. All geometry (terrain, track, trees, grandstand, car) is generated in code.
Rendering: real-time sun shadows for the cars (a depth map packed into an RGBA texture,
bilinear-filtered), per-pixel lighting in linear space with a filmic (ACES) tone curve,
hemisphere ambient, specular highlights and Fresnel sky reflections on paint and glass,
procedural grass and asphalt detail, a procedural sky with sun and clouds, and distance fog.

| File | What it does |
|---|---|
| `MainActivity.kt` | Fullscreen landscape activity, GL view + control overlay |
| `ControlOverlayView.kt` | Steering wheel touch control and HUD |
| `Scene.kt` | Chase camera and the per-frame draw list (API independent) |
| `GameRenderer.kt` | GL thread: simulation loop and OpenGL drawing |
| `ShaderSource.kt` | GLSL scene shader and shadow-map shader |
| `GameState.kt` | Race simulation: car choice, AI drivers, grid and countdown, collisions, laps, ranking |
| `CarPhysics.kt` | Real car specs, vehicle dynamics and street/grass parameters |
| `World.kt`, `Track.kt` | Track shape, hills, trees, bushes, rocks, grandstand |
| `CarModel.kt` | The six car styles: lofted bodies with wheel arches, grilles, lights, wings, exhausts |
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
