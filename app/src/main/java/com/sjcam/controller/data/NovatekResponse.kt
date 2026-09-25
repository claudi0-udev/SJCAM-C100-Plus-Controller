package com.sjcam.controller.data

data class NovatekResponse(
    val rawXml: String,
    val cmd: Int? = null,
    val status: Int? = null,
    val value: String? = null,
    val strings: List<String> = emptyList(),
    val settingsPairs: Map<Int, Int> = emptyMap(),
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val isSuccess: Boolean = (status == 0)
) {
    companion object {
        fun parse(xml: String): NovatekResponse {
            val cmdRegex = "<Cmd>(\\d+)</Cmd>".toRegex(RegexOption.IGNORE_CASE)
            val statusRegex = "<Status>(-?\\d+)</Status>".toRegex(RegexOption.IGNORE_CASE)
            val valueRegex = "<Value>(.*?)</Value>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val stringRegex = "<String>(.*?)</String>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val totalRegex = "<Total>(\\d+)</Total>".toRegex(RegexOption.IGNORE_CASE)
            val freeRegex = "<Free>(\\d+)</Free>".toRegex(RegexOption.IGNORE_CASE)

            // Extraer pares <Cmd>X</Cmd><Status>Y</Status> del volcado cmd=3014
            val pairRegex = "<Cmd>(\\d+)</Cmd>\\s*<Status>(-?\\d+)</Status>".toRegex(RegexOption.IGNORE_CASE)
            val settingsMap = mutableMapOf<Int, Int>()
            pairRegex.findAll(xml).forEach { match ->
                val c = match.groupValues[1].toIntOrNull()
                val s = match.groupValues[2].toIntOrNull()
                if (c != null && s != null) {
                    settingsMap[c] = s
                }
            }

            val cmd = cmdRegex.find(xml)?.groupValues?.get(1)?.toIntOrNull()
            val status = statusRegex.find(xml)?.groupValues?.get(1)?.toIntOrNull()
            val valueTag = valueRegex.find(xml)?.groupValues?.get(1)?.trim()
            val stringTags = stringRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()

            val totalBytes = totalRegex.find(xml)?.groupValues?.get(1)?.toLongOrNull()
            val freeBytes = freeRegex.find(xml)?.groupValues?.get(1)?.toLongOrNull()

            // Si no hay <Value>, usar el contenido de <String> o extraer el texto interno
            val effectiveValue = valueTag 
                ?: if (stringTags.isNotEmpty()) stringTags.joinToString(" ") 
                else null

            return NovatekResponse(
                rawXml = xml,
                cmd = cmd,
                status = status,
                value = effectiveValue,
                strings = stringTags,
                settingsPairs = settingsMap,
                totalBytes = totalBytes,
                freeBytes = freeBytes,
                isSuccess = (status == 0)
            )
        }
    }
}
