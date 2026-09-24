package com.reandroid.weather;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 中国气象局那一路的解析。
 *
 * <p>只测**纯的部分**：从 HTML 里抠 JS 变量、剥 JSONP 外壳、天气码映射、大陆判定。
 * 真正的 {@code JSONObject} 反序列化不在这里 —— 单测跑在 {@code android.jar} 上，
 * 那一份 {@code org.json} 是 stub（构造会抛 {@code Stub!}），所以 JSON 那一步留在设备上验。
 *
 * <p>这个数据源是**网页抓取**，不是 API。抓取最怕的就是"页面结构变了，但我们静默地
 * 解析出个错东西" —— 所以下面把边界钉死：抠不到就返回 null，绝不给半个对象。
 */
public class CmaWeatherSourceTest {

    // ---- extractVar：从 HTML 里抠 JS 变量 ----

    @Test
    public void extractsASimpleObject() {
        String html = "var a=1;\nvar dataSK={\"temp\":\"19.6\",\"weather\":\"阴\"};\nvar b=2;";
        assertEquals("{\"temp\":\"19.6\",\"weather\":\"阴\"}",
                CmaWeatherSource.extractVar(html, "dataSK"));
    }

    @Test
    public void handlesNestedBraces() {
        // dataSK 里确实有嵌套（aqi 之类会是对象），必须按配对取，不能取到第一个 }
        String html = "var dataSK={\"a\":{\"b\":{\"c\":1}},\"d\":2};";
        assertEquals("{\"a\":{\"b\":{\"c\":1}},\"d\":2}",
                CmaWeatherSource.extractVar(html, "dataSK"));
    }

    @Test
    public void ignoresBracesInsideStrings() {
        // 字符串里的花括号不能参与配对 —— 这是这类解析最经典的错法
        String html = "var dataSK={\"t\":\"}{ 不平衡\",\"n\":1};";
        assertEquals("{\"t\":\"}{ 不平衡\",\"n\":1}",
                CmaWeatherSource.extractVar(html, "dataSK"));
    }

    @Test
    public void ignoresEscapedQuotesInsideStrings() {
        String html = "var dataSK={\"t\":\"a\\\"}\\\"b\",\"n\":1};";
        assertEquals("{\"t\":\"a\\\"}\\\"b\",\"n\":1}",
                CmaWeatherSource.extractVar(html, "dataSK"));
    }

    @Test
    public void doesNotMatchALongerVariableName() {
        // 页面上真有 fc 与 fc40 这种同前缀的变量；"var fc" 不该匹配到 "var fc40"
        String html = "var fc40=[1,2];var fc={\"ok\":1};";
        assertEquals("{\"ok\":1}", CmaWeatherSource.extractVar(html, "fc"));
        assertNull("找 fc40 时不该拿到 fc", CmaWeatherSource.extractVar(html, "fc40x"));
    }

    @Test
    public void toleratesSpacesAroundTheEquals() {
        String html = "var  dataSK  =  {\"a\":1} ;";
        assertEquals("{\"a\":1}", CmaWeatherSource.extractVar(html, "dataSK"));
    }

    @Test
    public void returnsNullWhenMissingOrUnbalanced() {
        assertNull("变量不存在", CmaWeatherSource.extractVar("var other=1;", "dataSK"));
        assertNull("括号不配对", CmaWeatherSource.extractVar("var dataSK={\"a\":1;", "dataSK"));
        assertNull(null, CmaWeatherSource.extractVar(null, "dataSK"));
    }

    // ---- unwrapJsonp ----

    @Test
    public void unwrapsTheJsonpCallback() {
        String body = "getData({\"status\":\"success\",\"data\":{\"station\":{\"areaid\":\"101010100\"}}});";
        assertEquals("{\"status\":\"success\",\"data\":{\"station\":{\"areaid\":\"101010100\"}}}",
                CmaWeatherSource.unwrapJsonp(body));
    }

    @Test
    public void unwrapKeepsParenthesesInsideThePayload() {
        // 取第一个 ( 与最后一个 )：正文里带括号也不会截错
        assertEquals("{\"a\":\"(括号)\"}", CmaWeatherSource.unwrapJsonp("getData({\"a\":\"(括号)\"});"));
    }

    @Test
    public void unwrapRejectsNonJsonp() {
        assertNull(CmaWeatherSource.unwrapJsonp("{}"));
        assertNull(CmaWeatherSource.unwrapJsonp("getData()"));
        assertNull(CmaWeatherSource.unwrapJsonp(null));
    }

    // ---- 天气码 ----

    @Test
    public void codePrefixIsStrippedAndPadded() {
        // 实测两种都出现过：dataSK 里是 "d02"（两位），cityDZ 里是 "n7"（一位）
        assertEquals("02", CmaWeatherSource.normalizeCode("d02"));
        assertEquals("07", CmaWeatherSource.normalizeCode("n7"));
        assertEquals("02", CmaWeatherSource.normalizeCode("02"));
        assertNull(CmaWeatherSource.normalizeCode("x02"));
        assertNull(CmaWeatherSource.normalizeCode(""));
        assertNull(CmaWeatherSource.normalizeCode(null));
    }

    @Test
    public void nightIsReadFromTheCodePrefix() {
        // d/n 就是观测时刻的昼夜 —— 这套数据里唯一诚实的昼夜信号
        assertTrue(CmaWeatherSource.isNightObservation("n7"));
        assertFalse(CmaWeatherSource.isNightObservation("d02"));
        assertFalse(CmaWeatherSource.isNightObservation("02"));
    }

    /**
     * 四十个码全表 —— 抄自参考实现里那张表，一个不漏地映射到现有九档。
     *
     * <p>逐条写不是为了啰嗦：漏一个码就会静默落到兜底的"多云"，
     * 而"明明下暴雨却显示多云"在画面上是看得出来的，在日志里看不出来。
     */
    @Test
    public void theWholeCodeTable() {
        Object[][] table = {
                { "00", WeatherCondition.D1_CLEAR },          // 晴
                { "01", WeatherCondition.D2_CLOUDY },         // 多云
                { "02", WeatherCondition.D3_DREARY },         // 阴
                { "03", WeatherCondition.D5_RAIN_SHOWERS },   // 阵雨
                { "04", WeatherCondition.D6_THUNDERSTORMS },  // 雷阵雨
                { "05", WeatherCondition.D6_THUNDERSTORMS },  // 雷阵雨伴冰雹
                { "06", WeatherCondition.D9_SLEET },          // 雨夹雪
                { "07", WeatherCondition.D5_RAIN_SHOWERS },   // 小雨
                { "08", WeatherCondition.D5_RAIN_SHOWERS },   // 中雨
                { "09", WeatherCondition.D5_RAIN_SHOWERS },   // 大雨
                { "10", WeatherCondition.D5_RAIN_SHOWERS },   // 暴雨
                { "11", WeatherCondition.D5_RAIN_SHOWERS },   // 大暴雨
                { "12", WeatherCondition.D5_RAIN_SHOWERS },   // 特大暴雨
                { "13", WeatherCondition.D7_FLURRIES_SNOW },  // 阵雪
                { "14", WeatherCondition.D7_FLURRIES_SNOW },  // 小雪
                { "15", WeatherCondition.D7_FLURRIES_SNOW },  // 中雪
                { "16", WeatherCondition.D7_FLURRIES_SNOW },  // 大雪
                { "17", WeatherCondition.D7_FLURRIES_SNOW },  // 暴雪
                { "18", WeatherCondition.D4_FOG },            // 雾
                { "19", WeatherCondition.D8_ICE_COLD },       // 冻雨
                { "20", WeatherCondition.D4_FOG },            // 沙尘暴
                { "21", WeatherCondition.D5_RAIN_SHOWERS },   // 小到中雨
                { "22", WeatherCondition.D5_RAIN_SHOWERS },   // 中到大雨
                { "23", WeatherCondition.D5_RAIN_SHOWERS },   // 大到暴雨
                { "24", WeatherCondition.D5_RAIN_SHOWERS },   // 暴雨到大暴雨
                { "25", WeatherCondition.D5_RAIN_SHOWERS },   // 大暴雨到特大暴雨
                { "26", WeatherCondition.D7_FLURRIES_SNOW },  // 小到中雪
                { "27", WeatherCondition.D7_FLURRIES_SNOW },  // 中到大雪
                { "28", WeatherCondition.D7_FLURRIES_SNOW },  // 大到暴雪
                { "29", WeatherCondition.D4_FOG },            // 浮尘
                { "30", WeatherCondition.D4_FOG },            // 扬沙
                { "31", WeatherCondition.D4_FOG },            // 强沙尘暴
                { "53", WeatherCondition.D4_FOG },            // 霾
                { "99", WeatherCondition.D2_CLOUDY },         // 无
        };
        for (Object[] row : table) {
            String code = (String) row[0];
            WeatherCondition expected = (WeatherCondition) row[1];
            assertEquals("码 " + code, expected, CmaWeatherSource.toCondition("d" + code));
            assertEquals("夜间码 " + code, expected, CmaWeatherSource.toCondition("n" + code));
        }
    }

    @Test
    public void unknownCodeFallsBackToCloudy() {
        assertEquals(WeatherCondition.D2_CLOUDY, CmaWeatherSource.toCondition("d77"));
        assertEquals(WeatherCondition.D2_CLOUDY, CmaWeatherSource.toCondition(""));
        assertEquals(WeatherCondition.D2_CLOUDY, CmaWeatherSource.toCondition(null));
    }

    // ---- 大陆判定 ----

    @Test
    public void onlyMainlandChinaIsAllowed() {
        assertTrue(CmaWeatherSource.isAvailableIn(Locale.SIMPLIFIED_CHINESE));
        assertTrue(CmaWeatherSource.isAvailableIn(new Locale("en", "CN")));
        // 港澳台不算大陆：那个站虽然查得到，但这一路只对大陆开放
        assertFalse(CmaWeatherSource.isAvailableIn(Locale.TAIWAN));
        assertFalse(CmaWeatherSource.isAvailableIn(new Locale("zh", "HK")));
        assertFalse(CmaWeatherSource.isAvailableIn(new Locale("zh", "MO")));
        assertFalse(CmaWeatherSource.isAvailableIn(Locale.US));
        assertFalse("地区未知就不算", CmaWeatherSource.isAvailableIn(new Locale("zh")));
        assertFalse(CmaWeatherSource.isAvailableIn(null));
    }
}
