package com.reandroid.weather;

/**
 * 一路天气数据。
 *
 * <p>抽出来的原因见 {@link WeatherManager}：那里原本从 URL、密钥、JSON 解析到调度、缓存、
 * 回调全写死成 OpenWeather，想加第二个源只能往同一个类里塞分支。
 *
 * <p><b>失败必须抛异常，不许"返回上一次的值"。</b> 原来的实现有六处在失败时静默返回旧状态，
 * 于是"没更新"和"更新成功"在外面完全分不开 —— 回退、日志、界面提示都无从谈起。
 * 保留旧状态是 {@link WeatherManager} 的决定，不是数据源的决定。
 */
public interface WeatherSource {

    /** 日志与设置界面用，同时也是 prefs 里 {@code weather_source} 的取值。 */
    String id();

    /**
     * 取一次天气。
     *
     * @param lat 纬度，来源是设备定位、调试覆盖值或上次存下的坐标
     * @param lon 经度
     * @throws Exception 网络、密钥、解析等任何取不到的情形 —— 一律抛
     */
    WeatherState fetch(double lat, double lon) throws Exception;
}
