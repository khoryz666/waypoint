package com.khoryz666.waypoint.data

import androidx.room.TypeConverter
import com.khoryz666.waypoint.domain.DayTarget
import com.khoryz666.waypoint.domain.GoalCriterion
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.WaypointJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** Room columns for the JSON-shaped fields. Enums use Room's built-in name mapping. */
class Converters {
    @TypeConverter fun dateToText(date: LocalDate?): String? = date?.toString()
    @TypeConverter fun textToDate(text: String?): LocalDate? = text?.let(LocalDate::parse)

    @TypeConverter fun stringsToText(list: List<String>): String = WaypointJson.encodeToString(strings, list)
    @TypeConverter fun textToStrings(text: String): List<String> = WaypointJson.decodeFromString(strings, text)

    @TypeConverter fun longsToText(list: List<Long>): String = WaypointJson.encodeToString(longs, list)
    @TypeConverter fun textToLongs(text: String): List<Long> = WaypointJson.decodeFromString(longs, text)

    @TypeConverter fun intsToText(list: List<Int>): String = WaypointJson.encodeToString(ints, list)
    @TypeConverter fun textToInts(text: String): List<Int> = WaypointJson.decodeFromString(ints, text)

    @TypeConverter fun criteriaToText(list: List<GoalCriterion>): String = WaypointJson.encodeToString(criteria, list)
    @TypeConverter fun textToCriteria(text: String): List<GoalCriterion> = WaypointJson.decodeFromString(criteria, text)

    @TypeConverter fun targetToText(target: DayTarget): String = WaypointJson.encodeToString(DayTarget.serializer(), target)
    @TypeConverter fun textToTarget(text: String): DayTarget = WaypointJson.decodeFromString(DayTarget.serializer(), text)

    @TypeConverter fun structuredToText(value: Structured?): String? = value?.let(Structured::encode)
    @TypeConverter fun textToStructured(text: String?): Structured? = text?.let(Structured::decode)

    private companion object {
        val strings: KSerializer<List<String>> = ListSerializer(String.serializer())
        val longs: KSerializer<List<Long>> = ListSerializer(Long.serializer())
        val ints: KSerializer<List<Int>> = ListSerializer(Int.serializer())
        val criteria: KSerializer<List<GoalCriterion>> = ListSerializer(GoalCriterion.serializer())
    }
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
