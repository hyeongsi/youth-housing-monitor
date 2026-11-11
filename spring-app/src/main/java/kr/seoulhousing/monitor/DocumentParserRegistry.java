package kr.seoulhousing.monitor;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
class DocumentParserRegistry {
    private final Map<Key,DocumentParser> parsers;
    DocumentParserRegistry(List<DocumentParser> parserList){
        Map<Key,DocumentParser> found=new LinkedHashMap<>();
        for(DocumentParser parser:parserList){
            for(Key key:parser.supportedKeys()){
                if(found.put(key,parser)!=null)throw new IllegalStateException("중복 PDF 파서: "+key);
            }
        }
        parsers=Map.copyOf(found);
    }
    Optional<DocumentParser> find(String siteCode,String documentType){return Optional.ofNullable(parsers.get(new Key(siteCode,documentType)));}
    record Key(String siteCode,String documentType){}
}
