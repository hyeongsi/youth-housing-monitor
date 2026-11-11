package kr.seoulhousing.monitor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
class PdfTextLayoutExtractor {
    DocumentLayout extract(byte[] bytes) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) throw new IllegalArgumentException("암호화된 PDF는 지원하지 않습니다.");
            List<PageLayout> pages = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                CapturingStripper stripper = new CapturingStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = stripper.getText(pdf);
                pages.add(new PageLayout(pageNumber, pdf.getPage(pageNumber - 1).getMediaBox().getWidth(),
                        pdf.getPage(pageNumber - 1).getMediaBox().getHeight(), text, List.copyOf(stripper.chunks)));
            }
            int visibleCharacters = pages.stream().map(PageLayout::text).mapToInt(value -> value.replaceAll("\\s", "").length()).sum();
            return new DocumentLayout(pdf.getNumberOfPages(), visibleCharacters >= 20, List.copyOf(pages));
        }
    }

    record DocumentLayout(int pageCount, boolean hasTextLayer, List<PageLayout> pages) {
        String text() { return pages.stream().map(PageLayout::text).reduce("", (left, right) -> left + "\n" + right); }
    }
    record PageLayout(int pageNumber, float width, float height, String text, List<TextChunk> chunks) {}
    record TextChunk(String text, float x, float y, float width, float height) {}

    private static final class CapturingStripper extends PDFTextStripper {
        private final List<TextChunk> chunks = new ArrayList<>();
        private CapturingStripper() throws IOException {}
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (positions.isEmpty() || text.isBlank()) return;
            TextPosition first = positions.getFirst();
            TextPosition last = positions.getLast();
            float width = Math.max(first.getWidthDirAdj(), last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj());
            float height = positions.stream().map(TextPosition::getHeightDir).max(Float::compare).orElse(0f);
            chunks.add(new TextChunk(text, first.getXDirAdj(), first.getYDirAdj() - height, width, height));
        }
    }
}
