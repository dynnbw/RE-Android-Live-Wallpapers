package com.reandroid.update;

/**
 * 版本信息实体，对应 version.json
 */
public class VersionInfo {
    public int versionCode;
    public String versionName;
    public long apkSize;
    public String changelogEn;
    public String changelogZh;
    public boolean forceUpdate;
    public int minVersionCode;
}
