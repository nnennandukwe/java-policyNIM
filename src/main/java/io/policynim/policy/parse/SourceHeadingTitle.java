package io.policynim.policy.parse;

import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.List;
import java.util.StringJoiner;

/** Reads the literal inline source of CommonMark headings before any rendering or unescaping. */
final class SourceHeadingTitle {
    private String first = "";
    private String firstH1 = "";

    static String read(String body) {
        String source = body.replace('\0', '\ufffd');
        var titles = new SourceHeadingTitle();
        Parser.builder().maxOpenBlockParsers(20).includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .inlineParserFactory(context -> (lines, node) -> {
                if (node instanceof Heading heading && titles.firstH1.isEmpty()) {
                    List<SourceSpan> spans = heading.getSourceSpans();
                    String content = spans.size() > 1 ? setext(source, heading, spans) : lines.getContent();
                    String title = SourceMarkdownParser.strip(content);
                    if (titles.first.isEmpty()) {
                        titles.first = title;
                    }
                    if (heading.getLevel() == 1) {
                        titles.firstH1 = title;
                    }
                }
            }).build().parse(source);
        return titles.firstH1.isEmpty() ? titles.first : titles.firstH1;
    }

    private static String setext(String source, Heading heading, List<SourceSpan> spans) {
        var title = new StringJoiner("\n");
        for (var span : spans.subList(0, spans.size() - 1)) {
            int offset = span.getInputIndex();
            String line = source.substring(offset, offset + span.getLength());
            if (line.startsWith("\t") && heading.getParent() instanceof ListItem item) {
                // A list marker can consume part of a leading tab. Keep its remaining columns as spaces.
                var itemSpans = item.getSourceSpans();
                int index = java.util.Collections.binarySearch(itemSpans, span,
                    java.util.Comparator.comparingInt(SourceSpan::getLineIndex));
                if (index >= 0) {
                    int start = offset - span.getColumnIndex();
                    int actual = column(source, start, offset);
                    int required = column(source, start, itemSpans.get(index).getInputIndex()) + item.getContentIndent();
                    int nextStop = actual + 4 - actual % 4;
                    if (actual < required && nextStop > required) {
                        line = " ".repeat(nextStop - required) + line.substring(1);
                    }
                }
            }
            title.add(line);
        }
        return title.toString();
    }

    private static int column(String text, int start, int end) {
        int col = 0;
        for (int i = start; i < end; i++) {
            col += text.charAt(i) == '\t' ? 4 - col % 4 : 1;
        }
        return col;
    }
}
