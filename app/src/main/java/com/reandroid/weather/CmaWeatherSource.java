package com.reandroid.weather;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 中国气象局（中国天气网）的数据源。**不需要任何密钥** —— 这正是它相对于 OpenWeather 的价值。
 *
 * <p>取数是**两步网页抓取**，不是 API：
 *
 * <ol>
 *   <li>{@code d4.weather.com.cn/geong/v1/api?params=<URL 编码的 JSON>} → JSONP，
 *       取 {@code data.station.areaid}（站点编号）</li>
 *   <li>{@code d1.weather.com.cn/weather_index/<areaid>.html} → HTML 里的
 *       {@code var dataSK={...}}</li>
 * </ol>
 *
 * <p>两次都必须带 {@code Referer: https://www.weather.com.cn/}，否则被拒。
 *
 * <p><b>只有大陆才有数据。</b> 实测：站点查询全球可用（纽约给 401110101），
 * 但出了国界那个页面只剩 675 字节的短预报、**没有 dataSK**，也没有实时观测。
 * 所以这条路既在界面上限大陆，也在 {@link WeatherManager#resolveSource()} 里再判一次。
 */
final class CmaWeatherSource implements WeatherSource {

    static final String ID = "cma";

    private static final String STATION_URL = "https://d4.weather.com.cn/geong/v1/api";
    private static final String REALTIME_URL = "https://d1.weather.com.cn/weather_index/";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";

    /**
     * 这个站是**按 Referer 防盗链**的：不带这个头，请求会被拒。
     * 实测过：带上就正常返回，否则拿不到正文。
     */
    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://www.weather.com.cn/");
        return headers;
    }

    /**
     * 这一路在哪些地区成立：**只认大陆**。
     *
     * <p>港澳台的地区码是 HK/MO/TW，自然落到 false —— 它们不算大陆，
     * 而且那三地的数据完整度也与大陆不同。
     *
     * <p>纯函数、只吃 Locale，所以能在 JVM 上测。
     */
    static boolean isAvailableIn(Locale locale) {
        return locale != null && "CN".equalsIgnoreCase(locale.getCountry());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WeatherState fetch(double lat, double lon) throws Exception {
        String areaId = fetchStationAreaId(lat, lon);
        String html = WeatherHttp.get(REALTIME_URL + areaId + ".html", headers());

        String raw = extractVar(html, "dataSK");
        if (raw == null) {
            // 界面/数据层都限了大陆，走到这里通常是：那站改版了，或者 IP 在境外
            throw new IOException("实时页里没有 dataSK（多半是不在大陆，或那个站改版了）");
        }
        JSONObject data = new JSONObject(raw);
        String code = data.optString("weathercode", "");

        /*
         * 昼夜取自天气码的前缀（d 白天 / n 夜间）—— 这是这套数据里唯一诚实的昼夜信号，
         * 比接口自带的时区字段靠谱。实测 "d02" 与 "n7" 两种都出现过。
         */
        boolean isNight = isNightObservation(code);

        /*
         * 最低/最高温与日出日落**留 0**：只有 40 天预报页（wap_40d）带这几项，
         * 而全应用没有任何地方读它们（真正被消费的只有 condition）。
         * 为它们多打一个请求、多养一份 HTML 解析不划算 —— 要用了再补。
         */
        return new WeatherState(toCondition(code), isNight, 0.0f, 0.0f, 0L, 0L,
                System.currentTimeMillis() / 1000L);
    }

    /** 第一步：经纬度 → 站点编号。 */
    private String fetchStationAreaId(double lat, double lon) throws Exception {
        String payload = String.format(Locale.US,
                "{\"method\":\"stationinfo\",\"lat\":%.6f,\"lng\":%.6f,\"callback\":\"getData\"}",
                lat, lon);
        String url = STATION_URL + "?params=" + URLEncoder.encode(payload, "UTF-8");
        String body = WeatherHttp.get(url, headers());

        String json = unwrapJsonp(body);
        if (json == null) {
            throw new IOException("站点查询的返回不是 JSONP");
        }
        JSONObject data = new JSONObject(json).optJSONObject("data");
        JSONObject station = data != null ? data.optJSONObject("station") : null;
        String areaId = station != null ? station.optString("areaid", "") : "";
        if (areaId.isEmpty()) {
            throw new IOException("站点查询里没有 areaid");
        }
        return areaId;
    }

    /**
     * 从 HTML 里抠出 {@code var <name> = {...};} 的那个对象。
     *
     * <p>返回 null 表示"没抠到" —— 绝不给半个对象：抓取最怕的就是页面结构变了、
     * 我们却解析出一个看起来正常的东西。
     *
     * <p>名字要按边界匹配：那个页面里本来就有 {@code fc} 与 {@code fc40} 这种同前缀的变量。
     */
    static String extractVar(String text, String varName) {
        if (text == null || varName == null) {
            return null;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf("var", from);
            if (at < 0) {
                return null;
            }
            from = at + 3;
            int nameAt = at + 3;
            while (nameAt < text.length() && Character.isWhitespace(text.charAt(nameAt))) {
                nameAt++;
            }
            if (!text.startsWith(varName, nameAt)) {
                continue;
            }
            int after = nameAt + varName.length();
            // 名字后面必须是 '=' 或空白 —— 否则是 fc40 这种更长的名字
            if (after < text.length() && text.charAt(after) != '='
                    && !Character.isWhitespace(text.charAt(after))) {
                continue;
            }
            int open = text.indexOf('{', after);
            if (open >= 0) {
                String found = matchBalanced(text, open, '{', '}');
                if (found != null) {
                    return found;
                }
            }
        }
    }

    /**
     * 从 {@code start} 处的左括号取到配对的右括号（含两端）。
     *
     * <p><b>字符串里的括号不算数</b>，转义引号也不算 —— 这是这类解析最经典的错法，
     * 天气描述里完全可能出现花括号。不配对则返回 null。
     */
    static String matchBalanced(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 剥掉 JSONP 外壳。
     *
     * <p>取**第一个** {@code (} 与**最后一个** {@code )}：正文里带括号也不会截错
     * （正文里的括号一定在更里面）。
     */
    static String unwrapJsonp(String text) {
        if (text == null) {
            return null;
        }
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return null;
        }
        return text.substring(open + 1, close);
    }

    /**
     * 天气码归一成两位数字。
     *
     * <p>实测两种前缀都出现过：{@code dataSK} 里是 {@code "d02"}（已两位），
     * {@code cityDZ} 里是 {@code "n7"}（**一位，没补零**）。所以去掉首字母后还要左补零。
     */
    static String normalizeCode(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String code = rawCode.trim();
        if (code.isEmpty()) {
            return null;
        }
        char first = code.charAt(0);
        if (first == 'd' || first == 'D' || first == 'n' || first == 'N') {
            code = code.substring(1);
        }
        if (code.isEmpty() || code.length() > 2) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return null;
            }
        }
        return code.length() == 1 ? "0" + code : code;
    }

    /** 天气码是不是夜间观测（前缀 {@code n}）。 */
    static boolean isNightObservation(String rawCode) {
        if (rawCode == null) {
            return false;
        }
        String code = rawCode.trim();
        return !code.isEmpty() && (code.charAt(0) == 'n' || code.charAt(0) == 'N');
    }

    /**
     * 两位天气码 → 本应用的九档。
     *
     * <p>表来自参考实现里那张四十项的表（晴/多云/阴/阵雨/雷阵雨/雨夹雪/各级雨雪/
     * 雾霾沙尘/冻雨…），一个不漏地折到现有九个枚举上。认不出的码一律**多云**兜底 ——
     * 宁可显示得保守，也不要因为一个没见过的码就整条数据源判失败。
     */
    static WeatherCondition toCondition(String rawCode) {
        String code = normalizeCode(rawCode);
        if (code == null) {
            return WeatherCondition.D2_CLOUDY;
        }
        switch (code) {
            case "00":
                return WeatherCondition.D1_CLEAR;
            case "01":
                return WeatherCondition.D2_CLOUDY;
            case "02":
                return WeatherCondition.D3_DREARY;
            case "03":
            case "07":
            case "08":
            case "09":
            case "10":
            case "11":
            case "12":
            case "21":
            case "22":
            case "23":
            case "24":
            case "25":
                return WeatherCondition.D5_RAIN_SHOWERS;
            case "04":
            case "05":
                return WeatherCondition.D6_THUNDERSTORMS;
            case "06":
                return WeatherCondition.D9_SLEET;
            case "13":
            case "14":
            case "15":
            case "16":
            case "17":
            case "26":
            case "27":
            case "28":
                return WeatherCondition.D7_FLURRIES_SNOW;
            case "19":
                return WeatherCondition.D8_ICE_COLD;
            case "18":
            case "20":
            case "29":
            case "30":
            case "31":
            case "53":
                return WeatherCondition.D4_FOG;
            default:
                // 含 "99"（无）与任何没见过的码
                return WeatherCondition.D2_CLOUDY;
        }
    }
}
