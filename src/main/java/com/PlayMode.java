package com;

public enum PlayMode {
    /** 正向播放（顺序播放） */
    FORWARD("正向播放"),
    /** 逆向播放（倒序播放） */
    REVERSE("逆向播放"),
    /** 单曲循环 */
    SINGLE("单曲循环"),
    /** 随机播放 */
    SHUFFLE("随机播放");

    private final String displayName;

    PlayMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PlayMode fromDisplayName(String name) {
        for (PlayMode mode : values()) {
            if (mode.displayName.equals(name)) {
                return mode;
            }
        }
        return FORWARD;
    }

    public static PlayMode fromOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= values().length) {
            return FORWARD;
        }
        return values()[ordinal];
    }
}