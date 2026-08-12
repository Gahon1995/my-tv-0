package com.lizongying.mytv0.models

import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale


class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val now = getDateTimestamp()

    // SimpleDateFormat 非线程安全，parse() 可能并发调用，这里每次解释用局部实例避免竞争
    private val dateFormatThreadLocal = ThreadLocal<SimpleDateFormat>()

    private fun formatFTime(s: String): Int {
        var df = dateFormatThreadLocal.get()
        if (df == null) {
            df = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
            dateFormatThreadLocal.set(df)
        }
        return df.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        inputStream.use { input ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parser.nextTag()
            var channel = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    parser.next()
                    continue
                }
                if (parser.name == CHANNEL_TAG) {
                    // 读取 id 属性：<programme channel="..."> 引用的是 id，不是 display-name
                    val id = parser.getAttributeValue(ns, ID_ATTRIBUTE)
                    parser.nextTag()
                    val displayName = parser.nextText()
                    val list = mutableListOf<EPG>()
                    // 以 id 为主 key（programme lookup），display-name 为别名（MainViewModel 匹配用）
                    if (!id.isNullOrEmpty()) {
                        epg[id] = list
                    }
                    epg[displayName] = list
                } else if (parser.name == PROGRAMME_TAG) {
                    val start = parser.getAttributeValue(ns, START_ATTRIBUTE)
                    val stop = parser.getAttributeValue(ns, STOP_ATTRIBUTE)
                    // 从 channel 属性获取所属频道，而非依赖最后遍历到的 <channel> 标签
                    val programmeChannel = parser.getAttributeValue(ns, CHANNEL_ATTRIBUTE)
                    parser.nextTag()
                    val title = parser.nextText()
                    val targetList = if (!programmeChannel.isNullOrEmpty()) {
                        epg[programmeChannel]
                    } else {
                        // 兼容不规范的 XML：channel 属性缺失时回退到最后看到的频道
                        epg[channel]
                    }
                    if (targetList != null && formatFTime(stop) > now) {
                        targetList.add(EPG(title, formatFTime(start), formatFTime(stop)))
                    }
                }
                parser.next()
            }
        }

        return epg.toSortedMap { a, b -> b.compareTo(a) }
    }

    companion object {
        private const val CHANNEL_TAG = "channel"
        private const val PROGRAMME_TAG = "programme"
        private const val START_ATTRIBUTE = "start"
        private const val STOP_ATTRIBUTE = "stop"
        private const val CHANNEL_ATTRIBUTE = "channel"
        private const val ID_ATTRIBUTE = "id"
    }
}