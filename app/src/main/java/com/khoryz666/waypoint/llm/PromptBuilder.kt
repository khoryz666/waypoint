package com.khoryz666.waypoint.llm

/**
 * Fills `{{name}}` placeholders and `{{#name}}…{{/name}}` sections (shown when the value is
 * non-null, non-blank and not false) in a prompt template from assets/prompts (spec 8.2).
 */
class PromptBuilder(private val load: (String) -> String) {
    private val cache = mutableMapOf<String, String>()

    fun template(name: String): String = synchronized(cache) { cache.getOrPut(name) { load(name) } }

    fun render(name: String, values: Map<String, Any?> = emptyMap()): String = fill(template(name), values)

    companion object {
        private val section = Regex("""\{\{#(\w+)}}(.*?)\{\{/\1}}""", RegexOption.DOT_MATCHES_ALL)
        private val placeholder = Regex("""\{\{(\w+)}}""")

        fun fill(template: String, values: Map<String, Any?>): String {
            val withSections = section.replace(template) { m ->
                val v = values[m.groupValues[1]]
                if (v == null || v == false || v.toString().isBlank()) "" else m.groupValues[2]
            }
            return placeholder.replace(withSections) { m -> values[m.groupValues[1]]?.toString() ?: "" }.trim() + "\n"
        }
    }
}
