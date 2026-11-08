package com.easyshell.server.workflow.expression;

import com.easyshell.server.workflow.engine.NodeFailedException;
import com.easyshell.server.workflow.model.RenderResult;
import com.easyshell.server.workflow.model.ValueRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L2 引用渲染（§2.4.4，v3 修正后伪代码的逐行落地）。
 *
 * <p>三条关键性质：① 默认把值<b>单引号包裹</b>（{@code '} 经 {@code '\''} 转义后仍是字面量），
 * 于是 {@code $}、反引号、{@code ${}}、{@code \} 全部失去语义；② 区间簿记改为手工追加，
 * 区间<b>只覆盖替换值本身</b>（N2 修正——旧版区间覆盖前文，会把用户脚本自带的
 * {@code $(hostname)} 一起送进 L3）；③ 任何"像槽位但不合规格"的 {@code {{...}}}
 * 一律 PARAM_SLOT_MALFORMED——否则 {@code TaskServiceImpl} 的宽松
 * {@code substituteParameters}（{@code \{\{\s*(\w+)\s*\}\}}）会在我们渲染完之后做一次
 * <b>无引用</b>裸替换，把 L1/L2/L3 全部绕开（MF-3）。</p>
 */
public final class ShellSafeRenderer {

    /**
     * 安全值字符集：Unicode 字母/数字/组合记号 + 显式审计过的 ASCII 标点
     * （覆盖路径、版本号、主机名、IP、端口、百分比、数字、日期、CJK、含撇号文本）。
     * shell 结构字符（; | &amp; &lt; &gt; ( ) { }）与替换原语（$ ` \）一律不在表内。
     */
    private static final Pattern SAFE_VALUE =
            Pattern.compile("^[\\p{L}\\p{N}\\p{M}._:@%/=,+'\\- ]{1,256}$");

    /** 槽位：与 §2.3 契约 ② 同一正则（大写开头），:raw 后缀可选 */
    private static final Pattern SLOT =
            Pattern.compile("\\{\\{\\s*([A-Z][A-Z0-9_]*)(?::(raw))?\\s*}}");

    /** 宽松槽位（与 ScriptParameterUtils.PARAM_PATTERN 同口径 \w+），只用于"发现不合规槽位" */
    private static final Pattern LOOSE_SLOT = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private ShellSafeRenderer() {
    }

    public static RenderResult render(String script, Map<String, String> values, Set<String> rawParams) {
        if (script == null) {
            return new RenderResult(null, List.of());
        }
        // 0) fail-closed：任何"像槽位但不合规格"的 {{...}} 一律拒绝
        Matcher loose = LOOSE_SLOT.matcher(script);
        while (loose.find()) {
            if (!SLOT.matcher(loose.group(0)).matches()) {
                throw new NodeFailedException("PARAM_SLOT_MALFORMED",
                        "占位符 " + loose.group(0) + " 不合规：NAME 必须匹配 ^[A-Z][A-Z0-9_]*$");
            }
        }

        Matcher m = SLOT.matcher(script);
        StringBuilder sb = new StringBuilder();
        List<ValueRange> valueRanges = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            sb.append(script, lastEnd, m.start());           // ① 先追加槽位之间的原文（N2 修正）
            String name = m.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new NodeFailedException("PARAM_MISSING", "缺少参数 " + name);
            }
            boolean raw = m.group(2) != null;
            String out = raw ? value : singleQuote(value);    // ② L2：默认单引号包裹；raw 为裸值
            int start = sb.length();                         // ③ 区间只覆盖替换值本身
            sb.append(out);
            valueRanges.add(new ValueRange(start, sb.length(), raw));
            lastEnd = m.end();
        }
        sb.append(script, lastEnd, script.length());
        return new RenderResult(sb.toString(), valueRanges);
    }

    /** 单引号包裹：$、`、${}、\ 全部变成字面量；' 经 '\'' 转义后仍是字面量 */
    public static String singleQuote(String v) {
        return "'" + v.replace("'", "'\\''") + "'";
    }

    /** 脚本里出现的 {{NAME}} 名集合（按出现顺序，含 :raw 后缀形式） */
    public static Set<String> slotNames(String script) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (script == null) {
            return names;
        }
        Matcher m = SLOT.matcher(script);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** 脚本里出现的 {{NAME:raw}} 名集合 */
    public static Set<String> rawSlotNames(String script) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (script == null) {
            return names;
        }
        Matcher m = SLOT.matcher(script);
        while (m.find()) {
            if (m.group(2) != null) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * 该 {{...}} 是否是合规格位（NAME 匹配 ^[A-Z][A-Z0-9_]*$，可带 :raw 后缀）。
     * 只有"像槽位但不合规格"的才拒绝——合规格的不拦。
     */
    public static boolean isWellFormedSlot(String slot) {
        return slot != null && SLOT.matcher(slot).matches();
    }

    /** 宽松口径的 {{...}} 原文集合（供发布期校验"像槽位但不合规格"） */
    public static List<String> looseSlots(String script) {
        List<String> slots = new ArrayList<>();
        if (script == null) {
            return slots;
        }
        Matcher m = LOOSE_SLOT.matcher(script);
        while (m.find()) {
            slots.add(m.group(0));
        }
        return slots;
    }
}
