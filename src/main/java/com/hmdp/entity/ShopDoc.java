package com.hmdp.entity;

import com.hmdp.utils.PinyinUtil;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/**
 * ES 商户文档
 */
@Data
@NoArgsConstructor
public class ShopDoc {

    private Long id;
    private String name;
    /** 名称拼音（全拼） */
    private String namePinyin;
    /** 商户名称首字母，如海底捞为 hdl。 */
    private String nameInitials;
    private Long typeId;
    private String area;
    private String address;
    private Long avgPrice;
    private Integer score;
    private Integer sold;
    private Integer comments;
    private String images;
    /** 地理位置：纬度,经度 */
    private String location;
    /** 自动补全建议词 */
    private List<String> suggestion;
    /** 距离（搜索结果返回时填充） */
    private Double distance;

    public static ShopDoc from(Shop shop) {
        ShopDoc doc = new ShopDoc();
        doc.setId(shop.getId());
        doc.setName(shop.getName());
        // 拼音
        doc.setNamePinyin(PinyinUtil.toPinyin(shop.getName()));
        doc.setNameInitials(PinyinUtil.toFirstChar(shop.getName()));
        doc.setTypeId(shop.getTypeId());
        doc.setArea(shop.getArea());
        doc.setAddress(shop.getAddress());
        doc.setAvgPrice(shop.getAvgPrice());
        doc.setScore(shop.getScore());
        doc.setSold(shop.getSold());
        doc.setComments(shop.getComments());
        doc.setImages(shop.getImages());
        if (shop.getX() != null && shop.getY() != null) {
            doc.setLocation(shop.getY() + "," + shop.getX());
        }
        // 补全建议词：名称、商圈
        // completion 不接受 null 输入；提前生成中文、全拼和首字母，并去重。
        Set<String> inputs = new LinkedHashSet<>();
        addSuggestion(inputs, shop.getName());
        addSuggestion(inputs, doc.getNamePinyin());
        addSuggestion(inputs, doc.getNameInitials());
        addSuggestion(inputs, shop.getArea());
        addSuggestion(inputs, PinyinUtil.toPinyin(shop.getArea()));
        addSuggestion(inputs, PinyinUtil.toFirstChar(shop.getArea()));
        doc.setSuggestion(new ArrayList<>(inputs));
        return doc;
    }

    private static void addSuggestion(Set<String> inputs, String value) {
        if (value != null && !value.trim().isEmpty()) inputs.add(value.trim());
    }
}
