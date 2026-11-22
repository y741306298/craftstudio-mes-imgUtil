package com.craftstudio.imgutil.infra.graphics;

import com.craftstudio.imgutil.domain.graphics.RasterSpec;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GraphicsTextFonts {

    private static final List<String> FALLBACKS = List.of(
            RasterSpec.DEFAULT_LABEL_FONT_FAMILY, "Source Han Sans SC", "Noto Sans CJK SC", "Noto Sans SC",
            "Microsoft YaHei", "SimHei", "WenQuanYi Zen Hei");

    private GraphicsTextFonts() {
    }

    /** Uses the same family and style as element F for all production text marks. */
    public static Font productionMark(String text, int size) {
        return resolve(new Font(RasterSpec.DEFAULT_LABEL_FONT_FAMILY, Font.PLAIN, size), text);
    }

    public static Font resolve(Font preferred, String text) {
        int missing = preferred.canDisplayUpTo(text);
        if (missing < 0) {
            return preferred;
        }

        // Java-registered families also work when a logical font's system fallback does not see them.
        Set<String> families = new LinkedHashSet<>(FALLBACKS);
        Collections.addAll(families, GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT));
        for (String family : families) {
            Font candidate = new Font(family, preferred.getStyle(), preferred.getSize());
            if (family.equalsIgnoreCase(candidate.getFamily(Locale.ROOT)) && candidate.canDisplayUpTo(text) < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException(String.format(Locale.ROOT,
                "找不到可完整显示文字标记的字体（原字体首个缺字 U+%04X）", text.codePointAt(missing)));
    }
}
