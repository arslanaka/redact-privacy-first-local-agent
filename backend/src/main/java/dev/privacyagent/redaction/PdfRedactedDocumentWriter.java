package dev.privacyagent.redaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Renders already-redacted text into a brand-new PDF.
 *
 * <p>Why a new PDF instead of drawing boxes over the original: a black rectangle on top of a PDF page
 * leaves the text underneath in the content stream, where copy-paste or any text extractor recovers
 * it. Here the original PDF's content streams, fonts, images, annotations and metadata are not
 * copied at all. The writer only receives text in which approved spans were already replaced, so the
 * sensitive values never reach it. Redaction markers are drawn as filled boxes (BLOCK style) or as
 * white-on-black labels (LABEL style).
 *
 * <p>Trade-off (documented limitation): layout, fonts, images and tables of the source PDF are not
 * preserved; the output is a clean text rendering of the extracted text.
 */
@Component
public class PdfRedactedDocumentWriter {

    private static final float FONT_SIZE = 10f;
    private static final float LEADING = 14f;
    private static final float MARGIN = 56f;
    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;

    public byte[] write(String text, List<int[]> redactedRegions, boolean labelStyle) {
        boolean[] redacted = new boolean[text.length()];
        for (int[] r : redactedRegions) {
            for (int i = r[0]; i < r[1]; i++) {
                redacted[i] = true;
            }
        }
        try (PDDocument pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Redacted document");
            info.setProducer("Local Privacy Agent");
            info.setCreationDate(Calendar.getInstance());
            pdf.setDocumentInformation(info);
            new Layout(pdf, labelStyle).render(text, redacted);
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed", e);
        }
    }

    /** Minimal line-wrapping text layout: words wrap at the right margin, pages break at the bottom margin. */
    private static final class Layout {
        private final PDDocument pdf;
        private final boolean labelStyle;
        private final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final Map<Character, Character> encodable = new HashMap<>();
        private final float maxX = PAGE_SIZE.getWidth() - MARGIN;
        private PDPageContentStream cs;
        private float x;
        private float y;
        private int pageNumber;

        Layout(PDDocument pdf, boolean labelStyle) {
            this.pdf = pdf;
            this.labelStyle = labelStyle;
        }

        void render(String text, boolean[] redacted) throws IOException {
            newPage();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\n') {
                    newLine();
                    i++;
                } else if (c == ' ' || c == '\t') {
                    float w = width(c == '\t' ? "    " : " ");
                    if (x + w > maxX) {
                        newLine();
                    } else {
                        x += w;
                    }
                    i++;
                } else {
                    int end = i;
                    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                        end++;
                    }
                    drawWord(text, redacted, i, end);
                    i = end;
                }
            }
            closePage();
        }

        private void drawWord(String text, boolean[] redacted, int start, int end) throws IOException {
            float wordWidth = 0;
            for (int i = start; i < end; i++) {
                wordWidth += charWidth(text.charAt(i));
            }
            if (x + wordWidth > maxX && x > MARGIN) {
                newLine();
            }
            int i = start;
            while (i < end) {
                int runEnd = i;
                while (runEnd < end && redacted[runEnd] == redacted[i]) {
                    runEnd++;
                }
                drawRun(text.substring(i, runEnd), redacted[i]);
                i = runEnd;
            }
        }

        /** Draws a run of same-kind characters, wrapping character by character if it is wider than the line. */
        private void drawRun(String run, boolean isRedaction) throws IOException {
            var chunk = new StringBuilder();
            float chunkWidth = 0;
            for (char c : run.toCharArray()) {
                float w = charWidth(c);
                if (x + chunkWidth + w > maxX && (chunkWidth > 0 || x > MARGIN)) {
                    flush(chunk.toString(), chunkWidth, isRedaction);
                    newLine();
                    chunk.setLength(0);
                    chunkWidth = 0;
                }
                chunk.append(c);
                chunkWidth += w;
            }
            flush(chunk.toString(), chunkWidth, isRedaction);
        }

        private void flush(String s, float w, boolean isRedaction) throws IOException {
            if (s.isEmpty()) {
                return;
            }
            if (isRedaction) {
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.addRect(x, y - 3, w, FONT_SIZE + 2);
                cs.fill();
                if (labelStyle) {
                    cs.setNonStrokingColor(1f, 1f, 1f);
                    showText(s);
                    cs.setNonStrokingColor(0f, 0f, 0f);
                }
            } else {
                showText(s);
            }
            x += w;
        }

        private void showText(String s) throws IOException {
            cs.beginText();
            cs.setFont(font, FONT_SIZE);
            cs.newLineAtOffset(x, y);
            cs.showText(sanitize(s));
            cs.endText();
        }

        private float charWidth(char c) throws IOException {
            if (c == TextRedactor.BLOCK) {
                return width("0"); // fixed-width box per redacted character
            }
            return width(String.valueOf(encodableChar(c)));
        }

        private float width(String s) throws IOException {
            return font.getStringWidth(s) / 1000f * FONT_SIZE;
        }

        /** The standard Helvetica font only encodes WinAnsi; anything else is shown as '?'. */
        private char encodableChar(char c) {
            return encodable.computeIfAbsent(c, ch -> {
                try {
                    font.encode(String.valueOf(ch));
                    return ch;
                } catch (IOException | IllegalArgumentException e) {
                    return '?';
                }
            });
        }

        private String sanitize(String s) {
            var sb = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                sb.append(c == TextRedactor.BLOCK ? ' ' : encodableChar(c));
            }
            return sb.toString();
        }

        private void newLine() throws IOException {
            x = MARGIN;
            y -= LEADING;
            if (y < MARGIN) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            closePage();
            PDPage page = new PDPage(PAGE_SIZE);
            pdf.addPage(page);
            cs = new PDPageContentStream(pdf, page);
            pageNumber++;
            cs.beginText();
            cs.setFont(font, 7);
            cs.setNonStrokingColor(0.45f, 0.45f, 0.45f);
            cs.newLineAtOffset(MARGIN, MARGIN / 2);
            cs.showText("Redacted copy generated by Local Privacy Agent. Original layout not preserved. Page " + pageNumber);
            cs.endText();
            cs.setNonStrokingColor(0f, 0f, 0f);
            x = MARGIN;
            y = PAGE_SIZE.getHeight() - MARGIN;
        }

        private void closePage() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }
    }
}
