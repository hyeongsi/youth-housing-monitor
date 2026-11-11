package kr.seoulhousing.monitor;

import java.util.List;
import java.util.Map;

interface DocumentParser {
    String parserId();
    default List<DocumentParserRegistry.Key> supportedKeys(){return List.of();}
    ParseResult parse(PdfTextLayoutExtractor.DocumentLayout document);
    default ParseResult parse(PdfTextLayoutExtractor.DocumentLayout document,DocumentParserRegistry.Key key){return parse(document);}

    record ParseResult(Map<String,Object> draft, List<Evidence> evidence, List<String> warnings, boolean supported) {}
    record Evidence(String fieldPath, int pageNumber, Map<String,Object> boundingBox, String rawText, String method, String reviewStatus) {}
}
