package com.example.mymoney.importer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** A bounded OOXML reader using platform ZIP/SAX only. No formulas or external resources. */
object QianjiXlsx {
    const val MAX_FILE = 10 * 1024 * 1024
    private const val MAX_EXPANDED = 24 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private const val MAX_TEXT = 16_384
    data class Row(val number: Int, val cells: Map<String, String>, val result: QianjiRow.Result)
    data class Document(val rows: List<Row>, val zone: String)

    fun read(input: InputStream, zone: ZoneId = ZoneId.systemDefault()): Document {
        val file = input.readNBytesBounded(MAX_FILE)
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(entries.size < 256) { "文件包含过多项目" }
                require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "文件路径无效" }
                require(entry.name !in entries) { "文件包含重复项目" }
                val bytes = zip.readNBytesBounded(MAX_EXPANDED - expanded)
                expanded += bytes.size
                entries[entry.name] = bytes
                entry = zip.nextEntry
            }
        }
        fun xml(name: String, handler: DefaultHandler) {
            val bytes = entries[name] ?: error("文件缺少必要内容：$name")
            // OOXML parts are XML UTF-8. Reject other encodings (including UTF-16) and declarations.
            require(bytes.none { it == 0.toByte() }) { "不支持的 XML 编码" }
            val text = bytes.toString(Charsets.UTF_8)
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "不支持 XML 外部声明" }
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> error("不支持外部引用") }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        }
        var sheetId: String? = null
        xml("xl/workbook.xml", object : DefaultHandler() {
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                if (local == "sheet" && a.getValue("name") == "账单") {
                    require(sheetId == null) { "账单工作表重名" }
                    sheetId = a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                }
            }
        })
        require(sheetId != null) { "没有找到「账单」工作表" }
        var target: String? = null
        xml("xl/_rels/workbook.xml.rels", object : DefaultHandler() {
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                require(a.getValue("TargetMode") != "External") { "不支持外部链接" }
                if (local == "Relationship" && a.getValue("Id") == sheetId) target = a.getValue("Target")
            }
        })
        val path = target?.let { if (it.startsWith('/')) it.removePrefix("/") else "xl/$it" } ?: error("工作表关联无效")
        require(path.startsWith("xl/worksheets/") && !path.contains("..")) { "工作表路径无效" }
        val strings = arrayListOf<String>()
        if ("xl/sharedStrings.xml" in entries) xml("xl/sharedStrings.xml", object : DefaultHandler() {
            var text: StringBuilder? = null
            var inText = false
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                if (local == "si") { require(strings.size < 200_000) { "文本过多" }; text = StringBuilder() }
                if (local == "t") inText = true
            }
            override fun characters(c: CharArray, start: Int, length: Int) {
                if (inText) { text?.append(c, start, length); require((text?.length ?: 0) <= MAX_TEXT) { "单元格文本过长" } }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                if (local == "t") inText = false
                if (local == "si") { strings += text.toString(); text = null }
            }
        })
        val rows = arrayListOf<Row>()
        var headers: List<String>? = null
        xml(path, object : DefaultHandler() {
            var number = 0; var previous = 0; var col = 0; var kind = ""; var inValue = false
            var value = StringBuilder(); var cells = linkedMapOf<Int, String>()
            override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                when (local) {
                    "row" -> {
                        number = a.getValue("r")?.toIntOrNull() ?: error("行号缺失")
                        require(number > previous && number <= MAX_ROWS + 1) { "行号无效或超过50000条限制" }
                        previous = number; cells = linkedMapOf()
                    }
                    "c" -> {
                        val ref = a.getValue("r") ?: error("单元格地址缺失")
                        require(ref.matches(Regex("[A-Z]{1,2}[0-9]+")) && ref.dropWhile { it.isLetter() }.toIntOrNull() == number) { "单元格地址无效" }
                        col = ref.takeWhile { it.isLetter() }.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
                        require(col < 64 && col !in cells) { "列超限或单元格重复" }
                        kind = a.getValue("t").orEmpty(); value = StringBuilder()
                        require(kind in listOf("", "n", "s", "inlineStr", "str")) { "不支持的单元格类型" }
                    }
                    "f" -> error("不支持含公式的账单，请导出原始值")
                    "v", "t" -> inValue = true
                }
            }
            override fun characters(c: CharArray, start: Int, length: Int) {
                if (inValue) { value.append(c, start, length); require(value.length <= MAX_TEXT) { "单元格文本过长" } }
            }
            override fun endElement(uri: String?, local: String, q: String?) {
                when (local) {
                    "v", "t" -> inValue = false
                    "c" -> cells[col] = if (kind == "s") strings.getOrNull(value.toString().toIntOrNull() ?: -1) ?: error("文本引用无效") else value.toString()
                    "row" -> {
                        if (cells.values.all { it.isBlank() }) return
                        if (headers == null) {
                            require(number == 1) { "首行必须是表头" }
                            headers = (0..(cells.keys.maxOrNull() ?: 0)).map { cells[it].orEmpty().trim() }
                            require(headers!!.none { it.isEmpty() } && headers!!.distinct().size == headers!!.size && headers!!.containsAll(QianjiRow.requiredHeaders)) { "不是支持的钱迹导出表头" }
                        } else {
                            require(cells.keys.all { it < headers!!.size }) { "数据超出表头范围" }
                            val mapped = headers!!.mapIndexed { i, h -> h to cells[i].orEmpty() }.toMap()
                            rows += Row(number, mapped, QianjiRow.map(mapped, zone))
                        }
                    }
                }
            }
        })
        require(headers != null) { "账单工作表为空" }
        return Document(rows, zone.id)
    }

    private fun InputStream.readNBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n == -1) break
            require(out.size().toLong() + n <= limit) { "文件超过安全读取大小限制" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
