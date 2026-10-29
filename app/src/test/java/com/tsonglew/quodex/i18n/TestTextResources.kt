package com.tsonglew.quodex.i18n

import com.tsonglew.quodex.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Use the shipped catalog in JVM presentation tests rather than mocking translations. */
class TestTextResources(language: String) : TextResources {
    override val locale: Locale = Locale.forLanguageTag(language)
    private val strings = readCatalog(language).mapKeys { (name, _) -> R.string::class.java.getField(name).getInt(null) }
    override fun invoke(id: Int, vararg arguments: Any): String = strings.getValue(id).let {
        if (arguments.isEmpty()) it else String.format(locale, it, *arguments)
    }

    companion object {
        fun readCatalog(language: String): Map<String, String> {
            val folder = if (language == "en") "values" else "values-$language"
            val file = sequenceOf(File("src/main/res/$folder/strings.xml"), File("app/src/main/res/$folder/strings.xml"))
                .first { it.isFile }
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as org.w3c.dom.Element
                val value = element.textContent.removeSurrounding("\"")
                    .replace(Regex("\\\\(.)")) { match -> when (match.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> match.groupValues[1] } }
                element.getAttribute("name") to value
            }
        }
    }
}

val testTexts: TextResources = TestTextResources("zh")
