package se.fpq.remote.test

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object ZwoWorkoutParser {
    fun parse(inputStream: InputStream): Result<Workout> = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var author = ""
        var name = ""
        var description = ""
        val blocks = mutableListOf<WorkoutBlock>()
        var totalDuration = 0

        parser.nextTag()
        parser.require(XmlPullParser.START_TAG, null, "workout_file")

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "author" -> author = readText(parser)
                    "name" -> name = readText(parser)
                    "description" -> description = readText(parser)
                    "workout" -> {
                        val (parsedBlocks, duration) = parseWorkoutBlocks(parser)
                        blocks.addAll(parsedBlocks)
                        totalDuration = duration
                    }
                }
            }
        }

        Result.success(
            Workout(
                id = name.hashCode().toString(),
                name = name,
                author = author,
                description = description,
                blocks = blocks,
                totalDuration = totalDuration
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parseWorkoutBlocks(parser: XmlPullParser): Pair<List<WorkoutBlock>, Int> {
        val blocks = mutableListOf<WorkoutBlock>()
        var totalDuration = 0

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Warmup" -> {
                        val block = parseWarmup(parser)
                        blocks.add(block)
                        totalDuration += block.duration
                    }
                    "SteadyState" -> {
                        val block = parseSteadyState(parser)
                        blocks.add(block)
                        totalDuration += block.duration
                    }
                    "Cooldown" -> {
                        val block = parseCooldown(parser)
                        blocks.add(block)
                        totalDuration += block.duration
                    }
                    "IntervalsT" -> {
                        val expandedBlocks = parseIntervalsT(parser)
                        blocks.addAll(expandedBlocks)
                        totalDuration += expandedBlocks.sumOf { it.duration }
                    }
                    "Ramp" -> {
                        val block = parseRamp(parser)
                        blocks.add(block)
                        totalDuration += block.duration
                    }
                    "Freeride" -> {
                        val block = parseFreeride(parser)
                        blocks.add(block)
                        totalDuration += block.duration
                    }
                    else -> {
                        skipTag(parser)
                    }
                }
            }
        }

        return Pair(blocks, totalDuration)
    }

    private fun parseWarmup(parser: XmlPullParser): WorkoutBlock.Warmup {
        val duration = parser.getAttributeValue(null, "Duration")?.toIntOrNull() ?: 0
        val powerLow = parser.getAttributeValue(null, "PowerLow")?.toDoubleOrNull() ?: 0.0
        val powerHigh = parser.getAttributeValue(null, "PowerHigh")?.toDoubleOrNull() ?: 0.0
        val cadence = parser.getAttributeValue(null, "Cadence")?.toIntOrNull()

        skipTag(parser)

        return WorkoutBlock.Warmup(
            duration = duration,
            powerLow = powerLow,
            powerHigh = powerHigh,
            cadence = cadence
        )
    }

    private fun parseSteadyState(parser: XmlPullParser): WorkoutBlock.SteadyState {
        val duration = parser.getAttributeValue(null, "Duration")?.toIntOrNull() ?: 0
        val power = parser.getAttributeValue(null, "Power")?.toDoubleOrNull() ?: 0.0
        val cadence = parser.getAttributeValue(null, "Cadence")?.toIntOrNull()
        val messages = mutableListOf<TextEvent>()

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "textevent") {
                val timeOffset = parser.getAttributeValue(null, "timeoffset")?.toIntOrNull() ?: 0
                val message = parser.getAttributeValue(null, "message") ?: ""
                messages.add(TextEvent(timeOffset, message))
                parser.nextTag()
            }
        }

        return WorkoutBlock.SteadyState(
            duration = duration,
            power = power,
            cadence = cadence,
            messages = messages
        )
    }

    private fun parseCooldown(parser: XmlPullParser): WorkoutBlock.Cooldown {
        val duration = parser.getAttributeValue(null, "Duration")?.toIntOrNull() ?: 0
        val powerLow = parser.getAttributeValue(null, "PowerLow")?.toDoubleOrNull() ?: 0.0
        val powerHigh = parser.getAttributeValue(null, "PowerHigh")?.toDoubleOrNull() ?: 0.0
        val cadence = parser.getAttributeValue(null, "Cadence")?.toIntOrNull()

        skipTag(parser)

        return WorkoutBlock.Cooldown(
            duration = duration,
            powerLow = powerLow,
            powerHigh = powerHigh,
            cadence = cadence
        )
    }

    private fun parseIntervalsT(parser: XmlPullParser): List<WorkoutBlock> {
        val repeat = parser.getAttributeValue(null, "Repeat")?.toIntOrNull() ?: 1
        val onDuration = parser.getAttributeValue(null, "OnDuration")?.toIntOrNull() ?: 0
        val offDuration = parser.getAttributeValue(null, "OffDuration")?.toIntOrNull() ?: 0
        val onPower = parser.getAttributeValue(null, "OnPower")?.toDoubleOrNull() ?: 0.0
        val offPower = parser.getAttributeValue(null, "OffPower")?.toDoubleOrNull() ?: 0.0
        val onCadence = parser.getAttributeValue(null, "OnCadence")?.toIntOrNull()
        val offCadence = parser.getAttributeValue(null, "OffCadence")?.toIntOrNull()

        skipTag(parser)

        // Expand IntervalsT into individual ON/OFF blocks
        val expandedBlocks = mutableListOf<WorkoutBlock>()
        repeat(repeat) {
            expandedBlocks.add(
                WorkoutBlock.Interval(
                    duration = onDuration,
                    power = onPower,
                    cadence = onCadence,
                    isActive = true
                )
            )
            expandedBlocks.add(
                WorkoutBlock.Interval(
                    duration = offDuration,
                    power = offPower,
                    cadence = offCadence,
                    isActive = false
                )
            )
        }
        return expandedBlocks
    }

    private fun parseRamp(parser: XmlPullParser): WorkoutBlock.Ramp {
        val duration = parser.getAttributeValue(null, "Duration")?.toIntOrNull() ?: 0
        val powerLow = parser.getAttributeValue(null, "PowerLow")?.toDoubleOrNull() ?: 0.0
        val powerHigh = parser.getAttributeValue(null, "PowerHigh")?.toDoubleOrNull() ?: 0.0
        val cadence = parser.getAttributeValue(null, "Cadence")?.toIntOrNull()

        skipTag(parser)

        return WorkoutBlock.Ramp(
            duration = duration,
            powerLow = powerLow,
            powerHigh = powerHigh,
            cadence = cadence
        )
    }

    private fun parseFreeride(parser: XmlPullParser): WorkoutBlock.Freeride {
        val duration = parser.getAttributeValue(null, "Duration")?.toIntOrNull() ?: 0
        val cadence = parser.getAttributeValue(null, "Cadence")?.toIntOrNull()

        skipTag(parser)

        return WorkoutBlock.Freeride(
            duration = duration,
            cadence = cadence
        )
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skipTag(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var level = 1
        while (level > 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> level--
                XmlPullParser.START_TAG -> level++
            }
        }
    }
}

// Model classes needed by the parser
sealed class WorkoutBlock {
    abstract val duration: Int
    abstract val cadence: Int?

    data class Warmup(
        override val duration: Int,
        val powerLow: Double,
        val powerHigh: Double,
        override val cadence: Int? = null
    ) : WorkoutBlock()

    data class SteadyState(
        override val duration: Int,
        val power: Double,
        override val cadence: Int? = null,
        val messages: List<TextEvent> = emptyList()
    ) : WorkoutBlock()

    data class Cooldown(
        override val duration: Int,
        val powerLow: Double,
        val powerHigh: Double,
        override val cadence: Int? = null
    ) : WorkoutBlock()

    data class Interval(
        override val duration: Int,
        val power: Double,
        override val cadence: Int? = null,
        val isActive: Boolean = true  // true for ON interval, false for OFF interval
    ) : WorkoutBlock()

    data class Ramp(
        override val duration: Int,
        val powerLow: Double,
        val powerHigh: Double,
        override val cadence: Int? = null
    ) : WorkoutBlock()

    data class Freeride(
        override val duration: Int,
        override val cadence: Int? = null
    ) : WorkoutBlock()
}

data class TextEvent(
    val timeOffset: Int,
    val message: String
)

data class Workout(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val blocks: List<WorkoutBlock>,
    val totalDuration: Int
)

