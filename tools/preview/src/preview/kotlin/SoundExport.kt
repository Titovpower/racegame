import com.titovpower.racegame.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Renders the engine sound of a full-throttle run (idle, launch, upshifts to ~250 km/h, lift off)
// for each car to WAV, so the synthesis can be listened to without a phone.
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val rate = 22050
    val chunk = 512
    val dt = chunk.toFloat() / rate
    for (spec in CarSpec.ALL) {
        val car = CarPhysics(spec = spec)
        val box = Gearbox { spec }
        val synth = EngineSynth(rate)
        val pcm = ArrayList<Short>()
        val buf = ShortArray(chunk)
        var t = 0f
        while (t < 16f) {
            val throttle = when {
                t < 1.0f -> 0f
                t < 1.6f -> 0.6f // blip on the line
                t < 2.2f -> 0f
                t < 13f -> 1f
                else -> 0f
            }
            val moving = t >= 2.2f
            var k = 0f
            while (k < dt) {
                if (moving) car.update(0.002f, 0f, throttle, 0f, Surface.STREET)
                box.update(0.002f, car.speed, throttle)
                k += 0.002f
            }
            val load = if (box.shifting > 0f) 0.1f else throttle
            synth.render(buf, spec.engine, spec.redline, box.rpm, load, if (box.shifting > 0f) 0.65f else 0.85f)
            pcm.addAll(buf.toList())
            t += dt
        }
        val data = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(pcm.size * 2)
        for (s in pcm) data.putShort(s)
        val name = spec.brand.lowercase().replace(Regex("[^a-z]+"), "-") + ".wav"
        File(out, name).writeBytes(data.array())
        println("$name: ${spec.engine.label}, ${spec.gears} gears, ${"%.0f".format(car.speed * 3.6f)} km/h at the end")
    }
}
