package com.reandroid.update;

import java.util.ArrayList;
import java.util.List;

/** 版本信息实体，对应 version.json */
public class VersionInfo {
    public int versionCode;
    public String versionName;
    public String changelogEn;
    public String changelogZh;

    /**
     * 清单是从哪一类源取到的 —— 这同时是一次地区探测。
     *
     * <p>能连境外源的用户不需要网盘（国内网盘对他多半也不可用）；只有国内源能取到的用户，
     * 直连又大概率下不动。所以下载入口按它筛，见 {@link #downloadOptionsFor(Source)}。
     */
    public Source source = Source.UNKNOWN;

    /** 清单来源的可达性分类。 */
    public enum Source {
        /** 从境外源取到 —— 这个网络能连境外。 */
        FOREIGN,
        /** 从国内源取到 —— 直连境外大概率不通。 */
        DOMESTIC,
        /** 判不出来（所有源都不按预期应答）：两种入口都给，让用户自己挑。 */
        UNKNOWN,
    }

    /** 清单里给出的下载入口，按清单顺序。 */
    public final List<Download> downloads = new ArrayList<>();

    /** 一个下载入口。 */
    public static final class Download {
        /** 应用内直接下载并安装。 */
        public static final String KIND_DIRECT = "direct";
        /** 跳到外部（网盘、应用商店）由用户自己下。 */
        public static final String KIND_EXTERNAL = "external";

        public final String kind;
        public final String url;
        /** 外部入口显示的名字（"蓝奏云"）；direct 不用。 */
        public final String label;

        public Download(String kind, String url, String label) {
            this.kind = kind;
            this.url = url;
            this.label = label;
        }

        public boolean isDirect() {
            return KIND_DIRECT.equals(kind);
        }
    }

    /**
     * 该给这个用户哪些下载入口。
     *
     * <p>能连境外的：只给直连 —— 网盘对他不可用，摆出来只会让人点了没反应。
     * 只能连国内的：只给外部入口（网盘）—— 直连那个地址他大概率下不动。
     * 判不出来的：两种都给。
     */
    public List<Download> downloadOptionsFor(Source source) {
        List<Download> out = new ArrayList<>();
        for (Download d : downloads) {
            boolean direct = d.isDirect();
            if (source == Source.UNKNOWN || (source == Source.FOREIGN) == direct) {
                out.add(d);
            }
        }
        return out;
    }
}
