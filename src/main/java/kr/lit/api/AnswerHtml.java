package kr.lit.api;

import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;

/** Store editor HTML without executable markup. Legacy plain text stays plain text. */
final class AnswerHtml {
    static final int MAX_BYTES=1024*1024;
    private AnswerHtml() {}
    static String clean(String value) {
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new ApiError.Failure(HttpStatus.PAYLOAD_TOO_LARGE,"ANSWER_TOO_LARGE","답안 HTML은 UTF-8 기준 1MiB 이하여야 합니다.");
        if(!value.matches("(?s).*<\\s*(?:/?[A-Za-z]|!).*")) return value;
        var safe=Safelist.relaxed().addTags("colgroup","col","s","mark","span","div")
                .addAttributes("td","colspan","rowspan","colwidth")
                .addAttributes("th","colspan","rowspan","colwidth")
                .addAttributes("col","span","width","colwidth")
                .addAttributes("colgroup","span","width")
                .addProtocols("img","src","data");
        String cleaned=Jsoup.clean(value,"",safe,new Document.OutputSettings().prettyPrint(false));
        var document=Jsoup.parseBodyFragment(cleaned);
        document.outputSettings().prettyPrint(false);
        // Retain old raster images, but disallow SVG/data HTML and executable URLs.
        for(var img:document.select("img")) {
            String src=img.attr("src");
            if(!src.matches("(?is)(https?://.+|data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+)")) img.remove();
        }
        return document.body().html();
    }
}
