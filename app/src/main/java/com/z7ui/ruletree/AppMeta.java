package com.z7ui.ruletree;

/** ruletree 的项目信息：清单 key、博客、源码、要备份的内容。 */
public final class AppMeta {

    public static final String KEY = "ruletree";
    public static final String BLOG_URL = "https://www.z7ui.cn/ruletree/";
    public static final String REPO_URL = "https://github.com/z7ui/RuleTree";
    public static final String MORE_URL = "https://www.z7ui.cn/apps/";

    /** 规则存在文件里，不在 SharedPreferences */
    public static final String[] PREFS = {};
    public static final String[] FILES = {"rules.json"};

    private AppMeta() {
    }
}
