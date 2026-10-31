package com.hmdp.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import java.util.Locale;

/**
 * 拼音工具类
 */
public class PinyinUtil {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    /**
     * 中文转全拼（如：海底捞 -> haidilao）
     */
    public static String toPinyin(String chinese) {
        return convert(chinese, false);
    }

    /**
     * 中文转首字母（如：海底捞 -> hdl）
     */
    public static String toFirstChar(String chinese) {
        return convert(chinese, true);
    }

    /** 统一大小写并去除空格、括号等，输入 HAI DI 与索引 haidi 使用相同格式。 */
    public static String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String convert(String chinese, boolean initials) {
        if (chinese == null || chinese.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : chinese.toCharArray()) {
            try {
                String[] values = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
                if (values != null && values.length > 0) {
                    sb.append(initials ? values[0].substring(0, 1) : values[0]);
                } else {
                    sb.append(c);
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                // 输出格式固定，出现格式错误时停止转换，避免静默产生错误索引。
                throw new IllegalStateException("拼音输出格式错误", e);
            }
        }
        // pinyin4j 对多音字取第一个读音；品牌的特殊读音后续可按真实需求维护。
        return normalize(sb.toString());
    }
}
