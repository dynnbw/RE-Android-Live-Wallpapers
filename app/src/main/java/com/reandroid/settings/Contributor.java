package com.reandroid.settings;

/** 一位 GitHub 贡献者。字段直接对应 API 的 {@code login} / {@code avatar_url} / {@code html_url} / {@code contributions}。 */
public final class Contributor {

    public final String login;
    public final String avatarUrl;
    public final String htmlUrl;
    public final int contributions;

    public Contributor(String login, String avatarUrl, String htmlUrl, int contributions) {
        this.login = login;
        this.avatarUrl = avatarUrl;
        this.htmlUrl = htmlUrl;
        this.contributions = contributions;
    }
}
